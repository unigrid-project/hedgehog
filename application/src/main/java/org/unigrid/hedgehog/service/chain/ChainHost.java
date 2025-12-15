/*
    Unigrid Hedgehog
    Copyright © 2021-2025 Stiftelsen The Unigrid Foundation

    Stiftelsen The Unigrid Foundation (org. nr: 802482-2408)

    This program is free software: you can redistribute it and/or modify it under the terms of the
    addended GNU Affero General Public License as published by the The Unigrid Foundation and
    the Free Software Foundation, version 3 of the License (see COPYING and COPYING.addendum).

    This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
    even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU Affero General Public License and the addendum for more details.

    You should have received an addended copy of the GNU Affero General Public License with this program.
    If not, see <http://www.gnu.org/licenses/> and <https://github.com/unigrid-project/hedgehog>.
 */

package org.unigrid.hedgehog.service.chain;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.unigrid.hedgehog.model.gridnode.Gridnode;
import org.unigrid.hedgehog.model.network.codec.ChainCodecs;
import org.unigrid.hedgehog.model.network.packet.ChainEntry;
import org.unigrid.hedgehog.model.network.packet.ChainHeads;
import org.unigrid.hedgehog.model.network.packet.EntryRefused;
import org.unigrid.hedgehog.model.network.packet.FetchEntries;
import org.unigrid.hedgehog.model.network.packet.JoinChain;
import org.unigrid.hedgehog.model.network.packet.JoinReply;
import org.unigrid.hedgehog.model.network.packet.LeaveChain;
import org.unigrid.hedgehog.model.storage.placement.GridnodeDirectory;
import org.unigrid.hedgehog.proxy.chain.Admission;
import org.unigrid.hedgehog.proxy.chain.Chain;
import org.unigrid.hedgehog.proxy.chain.ChainId;
import org.unigrid.hedgehog.proxy.chain.ChainLimits;
import org.unigrid.hedgehog.proxy.chain.Entry;
import org.unigrid.hedgehog.proxy.chain.Head;
import org.unigrid.hedgehog.proxy.chain.Offer;
import org.unigrid.hedgehog.proxy.chain.WriterId;
import org.unigrid.hedgehog.proxy.group.GroupView;
import org.unigrid.hedgehog.proxy.group.ProxySettings;

/* Hosts the chains of this node's shard group while a valid communication spork says the proxy is on */
@Slf4j
@ApplicationScoped
@NoArgsConstructor(access = AccessLevel.PROTECTED, force = true) /* The proxy of a normal scoped bean needs it */
public class ChainHost implements ChainService {
	/* A client keeps one writer on a chain, and a few chains at most, so more is a stream hoarding places */
	static final int MAX_WRITERS_PER_STREAM = 4;

	/* Set on a stream once its close is awaited, so that however often it joins and leaves it is awaited once */
	private static final AttributeKey<Boolean> CLOSE_AWAITED = AttributeKey.valueOf("chainHostCloseAwaited");

	private final ProxySettingsSource settings;
	private final GridnodeDirectory directory;
	private final ChainTransport transport;
	private final LongSupplier clock;
	private final Object joins = new Object();

	/* Volatile so that the write which swaps in a finished hosting is seen whole by the netty threads that read it
	   while the refresh thread works on the next one */
	private volatile Hosting hosting = Hosting.OFF;

	@Inject
	public ChainHost(final ProxySettingsSource settings, final GridnodeDirectory directory,
		final ChainTransport transport) {

		this(settings, directory, transport, System::currentTimeMillis);
	}

	ChainHost(final ProxySettingsSource settings, final GridnodeDirectory directory, final ChainTransport transport,
		final LongSupplier clock) {

		this.settings = settings;
		this.directory = directory;
		this.transport = transport;
		this.clock = clock;
	}

	/* The chains of this node's group in the current epoch, in index order. Each call reads the hosting once, so
	   this and group() may answer from two different hostings; a caller that needs both takes one read of it. */
	public List<ChainId> hostedChains() {
		return List.copyOf(hosting.current().keySet());
	}

	Optional<HostedChain> hosted(final ChainId id) {
		return hosting.find(id);
	}

	public Optional<Chain> chain(final ChainId id) {
		return hosted(id).map(HostedChain::getChain);
	}

	public OptionalInt group() {
		return hosting.group();
	}

	/* Follows the spork, the topology and the clock. Refreshes run one at a time and swap in a hosting whose
	   chains and map are never changed afterwards, so the other calls read it without a lock. The streams to the
	   other members are the exception: they are looked up again on every refresh, also when the hosting is
	   reused, and handed to the chains before a rebuilt hosting is swapped in so that no reader sees a chain
	   without them. */
	public synchronized void refresh() {
		final Hosting previous = hosting;
		final Hosting next = settings.current().map(current -> hostingFor(current, previous)).orElse(Hosting.OFF);

		holdStreams(next);
		hosting = next;
	}

	/* Not private so that a test can tell how often the groups are ranked */
	GroupView viewOf(final ProxySettings current, final long now, final Collection<String> ids) {
		return new GroupView(current, now, ids);
	}

	/* Not private so that a test can hold the hosting itself; nothing in the host calls it */
	Hosting snapshot() {
		return hosting;
	}

	private Hosting hostingFor(final ProxySettings current, final Hosting previous) {
		final long now = clock.getAsLong();

		/* A clock that reads before the origin has no epoch, so it hosts nothing instead of failing the refresh */
		if (now < 0) {
			return Hosting.OFF;
		}

		final Hosting.Basis basis = new Hosting.Basis(current, current.epochAt(now), activeIds(), directory.self());

		/* Ranking every gridnode and hashing every chain id is the dear part of a refresh, which runs about
		   once a second, so a hosting stays while the settings, the epoch and the gridnodes stay what they were */
		return previous.isBuiltFrom(basis) ? previous : rebuilt(basis, now, previous);
	}

	private Hosting rebuilt(final Hosting.Basis basis, final long now, final Hosting previous) {
		final GroupView view = viewOf(basis.settings(), now, basis.activeIds());
		final ChainLimits limits = basis.settings().chainLimits();
		final OptionalInt group = basis.own().map(id -> view.groups().groupOf(id)).orElse(OptionalInt.empty());
		final Map<ChainId, HostedChain> chains = new LinkedHashMap<>();

		for (final ChainId id : basis.own().map(view::chainsOf).orElse(List.of())) {
			chains.put(id, previous.reusable(id, limits)
				.orElseGet(() -> new HostedChain(new Chain(id, limits, clock))));
		}

		return new Hosting(Optional.of(basis), group, peersOf(view, group, basis.own()),
			Collections.unmodifiableMap(chains));
	}

	private static List<String> peersOf(final GroupView view, final OptionalInt group, final Optional<String> own) {
		return group.stream().boxed().flatMap(index -> view.groups().members(index).stream())
			.filter(member -> !own.equals(Optional.of(member))).toList();
	}

	/* Looked up once per refresh and not per packet, because finding a connection scans the topology and resolves
	   the address of the gridnode: the cost is bounded by the size of the group. The chains share one immutable
	   list, which the netty threads read through a volatile field while this thread writes the next one. A stream
	   that is not open is no stream of a member; the list is as old as the refresh, so a stream that closes
	   later is dropped by the next one. */
	private void holdStreams(final Hosting next) {
		final List<Channel> streams = next.peers().stream().map(this::lookUp).flatMap(Optional::stream)
			.filter(Channel::isActive).toList();

		next.current().values().forEach(hosted -> hosted.setMemberStreams(streams));
	}

	/* One broken lookup leaves that member without a stream, not every member, and the refresh which runs about
	   once a second must not fail for it. Only the class of the failure is logged, as its message may name an
	   address. */
	private Optional<Channel> lookUp(final String member) {
		try {
			return Objects.requireNonNullElse(transport.streamTo(member), Optional.empty());
		} catch (RuntimeException ex) {
			log.warn("The lookup of a member stream failed with {}", ex.getClass().getName());
			return Optional.empty();
		}
	}

	private Set<String> activeIds() {
		/* The ranking throws for a gridnode without an id, which would fail every refresh */
		return directory.active().stream().map(Gridnode::getId).filter(Objects::nonNull)
			.collect(Collectors.toUnmodifiableSet());
	}

	/* Client feeds are only ever added in here, one join at a time, so no chain gets past its limit. They are
	   removed by leaves and closed streams without the lock, which can only make room. The lock is not the one
	   of refresh(), which waits for stream lookups that a join must not wait for. */
	@Override
	public JoinReply join(final Channel peer, final JoinChain request) {
		final JoinReply.JoinReplyBuilder reply = JoinReply.builder().requestId(request.getRequestId());
		final WriterId writer = request.getWriter();

		synchronized (joins) {
			final Hosting current = hosting;

			if (current.settings().isEmpty()) {
				return reply.status(JoinReply.Status.DISABLED).build();
			}

			if (writer == null) {
				return reply.status(JoinReply.Status.FULL).build();
			}

			return current.placement(writer, request.getChainId())
				.map(hosted -> admit(current, hosted, peer, writer, reply))
				.orElseGet(() -> reply.status(JoinReply.Status.UNKNOWN_CHAIN).build());
		}
	}

	private JoinReply admit(final Hosting current, final HostedChain hosted, final Channel peer,
		final WriterId writer, final JoinReply.JoinReplyBuilder reply) {

		final HostedChain.Attachment attachment = attach(current, hosted, peer, writer);

		if (attachment == HostedChain.Attachment.REFUSED) {
			return reply.status(JoinReply.Status.FULL).build();
		}

		final Map<WriterId, Head> heads = hosted.getChain().heads();

		/* The reply carries every head and a packet holds only so many, so a chain with more takes no clients */
		if (heads.size() > ChainCodecs.MAX_HEADS_PER_PACKET) {
			hosted.detach(writer, peer);
			return reply.status(JoinReply.Status.FULL).build();
		}

		if (attachment == HostedChain.Attachment.NEW) {
			awaitClose(peer);
		}

		return reply.status(JoinReply.Status.JOINED).chainId(hosted.getChain().id()).heads(heads).build();
	}

	/* A stream that is not open is no place to attach: its close has run or is running, and the attachment would
	   outlive it. A stream closes on the event loop that reads its packets, so it cannot close during a join. */
	private HostedChain.Attachment attach(final Hosting current, final HostedChain hosted, final Channel peer,
		final WriterId writer) {

		if (!peer.isActive()) {
			return HostedChain.Attachment.REFUSED;
		}

		final boolean streamHasRoom = current.clientsOn(peer) < MAX_WRITERS_PER_STREAM;

		return hosted.attach(writer, peer, current.settings().orElseThrow().clientsPerChain(), streamHasRoom);
	}

	private void awaitClose(final Channel peer) {
		if (peer.attr(CLOSE_AWAITED).setIfAbsent(Boolean.TRUE) == null) {
			peer.closeFuture().addListener(closed -> hosting.detachAll(peer));
		}
	}

	/* Duplicates and entries of chains this node does not host are dropped without a word, like everything
	   else a chain turns away: only a writer that is told the chain is full has something to do about it.
	   An entry from a stream that may not write it is dropped before anything is verified. */
	@Override
	public List<EntryRefused> receive(final Channel peer, final ChainEntry entries) {
		final Hosting current = hosting;
		final List<EntryRefused> refusals = new ArrayList<>();

		for (final Entry entry : Objects.requireNonNullElse(entries.getEntries(), List.<Entry>of())) {
			final Optional<Offer> offer = current.find(entry.header().chainId())
				.filter(hosted -> hosted.accepts(peer, entry.header().writer()))
				.flatMap(hosted -> hosted.offer(entry));

			if (offer.isPresent() && offer.get().admission() == Admission.FULL) {
				refusals.add(EntryRefused.of(entry, Admission.FULL));
			}
		}

		return refusals;
	}

	@Override
	public Optional<FetchEntries> reconcile(final Channel peer, final ChainHeads heads) {
		return Optional.empty();
	}

	@Override
	public List<Entry> fetch(final Channel peer, final FetchEntries request) {
		return List.of();
	}

	@Override
	public void refused(final Channel peer, final EntryRefused refusal) {
		/* No writer of this node is on a chain to pause */
	}

	/* A leave is unsigned, so it is only believed from the stream the writer attached through */
	@Override
	public void leave(final Channel peer, final LeaveChain leave) {
		if (leave.getChainId() != null && leave.getWriter() != null) {
			hosted(leave.getChainId()).ifPresent(hosted -> hosted.detach(leave.getWriter(), peer));
		}
	}
}

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
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
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
import org.unigrid.hedgehog.model.gridnode.Gridnode;
import org.unigrid.hedgehog.model.network.packet.ChainEntry;
import org.unigrid.hedgehog.model.network.packet.ChainHeads;
import org.unigrid.hedgehog.model.network.packet.EntryRefused;
import org.unigrid.hedgehog.model.network.packet.FetchEntries;
import org.unigrid.hedgehog.model.network.packet.JoinChain;
import org.unigrid.hedgehog.model.network.packet.JoinReply;
import org.unigrid.hedgehog.model.network.packet.LeaveChain;
import org.unigrid.hedgehog.model.storage.placement.GridnodeDirectory;
import org.unigrid.hedgehog.proxy.chain.Chain;
import org.unigrid.hedgehog.proxy.chain.ChainId;
import org.unigrid.hedgehog.proxy.chain.ChainLimits;
import org.unigrid.hedgehog.proxy.chain.Entry;
import org.unigrid.hedgehog.proxy.group.GroupView;
import org.unigrid.hedgehog.proxy.group.ProxySettings;

/* Hosts the chains of this node's shard group while a valid communication spork says the proxy is on */
@ApplicationScoped
@NoArgsConstructor(access = AccessLevel.PROTECTED, force = true) /* The proxy of a normal scoped bean needs it */
public class ChainHost implements ChainService {
	private final ProxySettingsSource settings;
	private final GridnodeDirectory directory;
	private final LongSupplier clock;

	/* Volatile so that the write which swaps in a finished hosting is seen whole by the netty threads that read it
	   while the refresh thread works on the next one */
	private volatile Hosting hosting = Hosting.OFF;

	@Inject
	public ChainHost(final ProxySettingsSource settings, final GridnodeDirectory directory) {
		this(settings, directory, System::currentTimeMillis);
	}

	ChainHost(final ProxySettingsSource settings, final GridnodeDirectory directory, final LongSupplier clock) {
		this.settings = settings;
		this.directory = directory;
		this.clock = clock;
	}

	/* The chains of this node's group in the current epoch, in index order. Each call reads the hosting once, so
	   this and group() may answer from two different hostings; a caller that needs both takes one read of it. */
	public List<ChainId> hostedChains() {
		return List.copyOf(hosting.current().keySet());
	}

	public Optional<Chain> chain(final ChainId id) {
		return hosting.find(id).map(HostedChain::getChain);
	}

	public OptionalInt group() {
		return hosting.group();
	}

	/* Follows the spork, the topology and the clock. Refreshes run one at a time and swap in a hosting that is
	   never changed afterwards, so the other calls read it without a lock. */
	public synchronized void refresh() {
		final Hosting previous = hosting;

		hosting = settings.current().map(current -> hostingFor(current, previous)).orElse(Hosting.OFF);
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

		return new Hosting(Optional.of(basis), group, Collections.unmodifiableMap(chains));
	}

	private Set<String> activeIds() {
		/* The ranking throws for a gridnode without an id, which would fail every refresh */
		return directory.active().stream().map(Gridnode::getId).filter(Objects::nonNull)
			.collect(Collectors.toUnmodifiableSet());
	}

	@Override
	public JoinReply join(final Channel peer, final JoinChain request) {
		return JoinReply.builder().requestId(request.getRequestId()).status(JoinReply.Status.DISABLED).build();
	}

	@Override
	public List<EntryRefused> receive(final Channel peer, final ChainEntry entries) {
		return List.of();
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

	@Override
	public void leave(final Channel peer, final LeaveChain leave) {
		/* No client is attached to detach */
	}
}

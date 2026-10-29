/*
    Unigrid Hedgehog
    Copyright © 2021-2026 Stiftelsen The Unigrid Foundation

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
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.random.RandomGenerator;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.unigrid.hedgehog.model.geo.CountryLookup;
import org.unigrid.hedgehog.model.gridnode.Gridnode;
import org.unigrid.hedgehog.model.network.PendingRequests;
import org.unigrid.hedgehog.model.network.packet.JoinChain;
import org.unigrid.hedgehog.model.network.packet.JoinReply;
import org.unigrid.hedgehog.model.network.packet.LeaveChain;
import org.unigrid.hedgehog.model.storage.placement.GridnodeDirectory;
import org.unigrid.hedgehog.proxy.chain.ChainId;
import org.unigrid.hedgehog.proxy.chain.FeedWriter;
import org.unigrid.hedgehog.proxy.chain.Head;
import org.unigrid.hedgehog.proxy.chain.WriterId;
import org.unigrid.hedgehog.proxy.chain.WriterKey;
import org.unigrid.hedgehog.proxy.group.GroupChoice;
import org.unigrid.hedgehog.proxy.group.GroupView;
import org.unigrid.hedgehog.proxy.group.ProxySettings;
import org.unigrid.hedgehog.proxy.session.ExitCandidate;
import org.unigrid.hedgehog.proxy.session.NoiseNk;

/* The client side of the shard groups: finds a group that takes this node, asks a member to let a writer of this
   node write on one of its chains, attaches to a second member of the group on the same chain and follows the
   chain through both. A group that refuses is passed over for another, and a new epoch brings a new chain, so
   the attachment is joined again when the epoch has moved on.

   The monitor of the client guards the join in use and may take the lock of the claims, never the other way
   round; neither is held across a wait. What the replies of the members set going runs on the threads that
   complete them and takes the lock of the claims alone, for a put or an unfollow. */
@Slf4j
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public final class ChainClient {
	private final ProxySettingsSource settings;
	private final GridnodeDirectory directory;
	private final CountryLookup countries;
	private final ChainTransport transport;
	private final ChainHost host;
	private final PendingRequests pending;
	private final LongSupplier clock;
	private final RandomGenerator random;
	private final Executor joins;
	private final SecureRandom keys = new SecureRandom();

	/* The chain of every writer of this node, from just before the chain is followed for it until its attachment
	   is released: a chain is unfollowed with the last writer on it */
	private final Map<WriterId, ChainId> claims = new HashMap<>();
	private CompletableFuture<ChainAttachment> current = CompletableFuture.failedFuture(
		new IllegalStateException("No chain is joined yet"));

	/* The joins run on threads of their own, as a caller may be an event loop and a join looks the streams up, which
	   can resolve names: not the common pool, whose few CPU-bound workers a blocked lookup would hold */
	@SuppressWarnings("checkstyle:ParameterNumber")
	public ChainClient(final ProxySettingsSource settings, final GridnodeDirectory directory,
		final CountryLookup countries, final ChainTransport transport, final ChainHost host,
		final PendingRequests pending, final LongSupplier clock, final RandomGenerator random) {

		this(settings, directory, countries, transport, host, pending, clock, random, joinThreads());
	}

	private static Executor joinThreads() {
		return Executors.newCachedThreadPool(body -> {
			final Thread thread = new Thread(body, "chain-client-join");

			thread.setDaemon(true);
			return thread;
		});
	}

	/* The chain to write on, joined first if this node has none for the epoch or lost the one it had. Callers that
	   come while a join is under way share it, and a join that failed is made again by the next caller. Each caller
	   gets a copy, so that none can complete or cancel the join of the others. What a caller chains on it runs on a
	   thread of the join executor, never on the event loop that completed the last reply, so it may do real work. */
	public synchronized CompletableFuture<ChainAttachment> attachment() {
		if (current.isDone() && inUse().isEmpty()) {
			current = CompletableFuture.supplyAsync(this::anyGroup, joins).thenCompose(Function.identity())
				.thenApplyAsync(Function.identity(), joins);
		}

		return current.copy();
	}

	public synchronized boolean isCurrent(final ChainAttachment attachment) {
		return inUse().filter(held -> held == attachment).isPresent();
	}

	/* Leaves the chain on every stream the attachment was joined through, retired or not, as a stream has room for a
	   few writers only, then unfollows it unless another writer of this node is on it, as when the chain was lost
	   and joined again within the epoch. An attachment keeps its chain followed until it is released, and only its
	   first release counts. */
	public void release(final ChainAttachment attachment) {
		if (attachment != null && unclaimed(attachment.writerId(), attachment.chainId())) {
			attachment.streams().forEach(stream -> leave(stream, attachment.chainId(), attachment.writerId()));
			unfollowUnclaimed(attachment.chainId());
		}
	}

	/* The attachment held, while its epoch is the current one and its chain is still followed. The members drop
	   their writers when the proxy turns off, so the replicas are dropped then too; isCurrent notices it as well,
	   for an owner that keeps asking about its attachments without asking for a new one. Settings that cannot be
	   read and a clock before the origin, which has no epoch, leave nothing in use, and the join that follows
	   fails with them. */
	private Optional<ChainAttachment> inUse() {
		try {
			final Optional<ProxySettings> proxy = settings.current();
			final long now = clock.getAsLong();

			if (proxy.isEmpty()) {
				unfollowAll();
			}

			return held().filter(attachment -> claimed(attachment) && proxy.map(on -> on.epochAt(now))
				.equals(Optional.of(attachment.epoch())) && host.chain(attachment.chainId()).isPresent());
		} catch (RuntimeException ex) {
			return Optional.empty();
		}
	}

	/* Not once it was released, even when an older writer of the node keeps the same chain followed */
	private boolean claimed(final ChainAttachment attachment) {
		synchronized (claims) {
			return attachment.chainId().equals(claims.get(attachment.writerId()));
		}
	}

	private Optional<ChainAttachment> held() {
		return current.isDone() && !current.isCompletedExceptionally() ? Optional.of(current.join())
			: Optional.empty();
	}

	/* A gridnode in a group hosts the chains of that group, so it never joins it as a client. Its group is taken
	   from the view the candidates come from, as the hosting may be a refresh behind at the turn of an epoch. */
	private CompletableFuture<ChainAttachment> anyGroup() {
		final ProxySettings proxy = settings.current()
			.orElseThrow(() -> new IllegalStateException("The proxy is off"));
		final GroupView view = new GroupView(proxy, clock.getAsLong(), directory.active().stream()
			.map(Gridnode::getId).filter(Objects::nonNull).toList());

		return nextGroup(view, directory.self().stream().flatMapToInt(own -> view.groups().groupOf(own).stream())
			.boxed().collect(Collectors.toCollection(HashSet::new)));
	}

	/* A group that refuses is passed over until one takes the node: one without an open stream to any member at
	   once, the others once they answered. The next group is looked for on the executor, as an answer arrives on
	   an event loop or on the timer of the timeouts. */
	private CompletableFuture<ChainAttachment> nextGroup(final GroupView view, final Set<Integer> refused) {
		for (OptionalInt group = view.joinCandidate(random, refused); group.isPresent();
			group = view.joinCandidate(random, refused)) {

			final Map<Channel, String> connected = connected(view.groups().members(group.getAsInt()));

			refused.add(group.getAsInt());

			if (!connected.isEmpty()) {
				return attach(view, group.getAsInt(), connected)
					.exceptionallyComposeAsync(refusal -> nextGroup(view, refused), joins);
			}
		}

		return CompletableFuture.failedFuture(new IllegalStateException("No shard group took this node"));
	}

	/* The open streams to the members, each with its member, in a random order, so that the member asked first is
	   any of those this node holds an open stream to */
	private Map<Channel, String> connected(final List<String> members) {
		final List<String> shuffled = new ArrayList<>(members);
		final Map<Channel, String> connected = new LinkedHashMap<>();

		Collections.shuffle(shuffled, new Random(random.nextLong()));
		shuffled.forEach(member -> streamTo(member).ifPresent(stream -> connected.putIfAbsent(stream, member)));
		return connected;
	}

	/* A stream that is not open is none: its close has run or is running. A broken lookup leaves that member out,
	   not the group, and only the class of the failure is logged, as its message may name an address. */
	private Optional<Channel> streamTo(final String member) {
		try {
			return Objects.requireNonNullElse(transport.streamTo(member), Optional.<Channel>empty())
				.filter(Channel::isActive);
		} catch (RuntimeException ex) {
			log.warn("The lookup of a member stream failed with {}", ex.getClass().getName());
			return Optional.empty();
		}
	}

	/* A fresh writer for every attachment, as it is the identity of this node on that chain and nowhere else */
	private CompletableFuture<ChainAttachment> attach(final GroupView view, final int group,
		final Map<Channel, String> connected) {

		final WriterKey key = WriterKey.generate(keys);
		final List<Channel> streams = List.copyOf(connected.keySet());
		final Channel first = streams.get(0);
		final Optional<Channel> second = streams.stream().skip(1).findFirst();
		final List<String> asked = Stream.concat(Stream.of(first), second.stream()).map(connected::get).toList();
		final List<ChainId> chains = view.chains(group);
		final List<String> members = view.groups().members(group);

		return asked(first, key.id(), null, reply -> followedFirst(first, key.id(), chains, reply))
			.thenCompose(chainId -> spare(first, second, key.id(), chainId).thenApply(attached ->
				new ChainAttachment(group, view.epoch(), new FeedWriter(key, chainId), members, attached,
					exitsAmong(members, asked))));
	}

	/* A member of the group is a candidate exit while it is active and announces a sealing key, unless the node
	   asked it to attach: that member sees the node's address and learned its writer, whether it took the node or
	   not, and as the exit it would see both ends of a session. Each session draws its exit at random among the
	   candidates in the country it names that it did not pass over. */
	private ChainAttachment.Exits exitsAmong(final List<String> members, final List<String> askedMembers) {
		return (country, excluded) -> {
			final Map<String, Gridnode> candidates = directory.active().stream()
				.filter(gridnode -> members.contains(gridnode.getId()))
				.filter(gridnode -> !askedMembers.contains(gridnode.getId()))
				.filter(ChainClient::hasSealingKey)
				.collect(Collectors.toMap(Gridnode::getId, Function.identity(), (first, second) -> first));

			return GroupChoice.exit(members.stream().filter(candidates::containsKey).toList(), country,
				member -> countries.countryOfHost(candidates.get(member).getHostName()), excluded, random)
				.map(candidates::get)
				.map(gridnode -> new ExitCandidate(gridnode.getId(), gridnode.getSealingKey()));
		};
	}

	private static boolean hasSealingKey(final Gridnode gridnode) {
		return Objects.nonNull(gridnode.getSealingKey()) && gridnode.getSealingKey().length == NoiseNk.KEY_SIZE;
	}

	/* The continuation is in place before the request goes out, so that it runs on the thread that completes the
	   reply, the event loop of the stream, before the next packet of that stream: the entries a member floods after
	   the heads of its reply then find the chain followed. A request that cannot be written fails its reply. */
	private <T> CompletableFuture<T> asked(final Channel stream, final WriterId writer, final ChainId chainId,
		final Function<JoinReply, T> onReply) {

		final long requestId = pending.nextRequestId();
		final CompletableFuture<JoinReply> reply = pending.register(requestId, stream, JoinReply.class);
		final CompletableFuture<T> answered = reply.thenApply(onReply);

		stream.writeAndFlush(JoinChain.builder().requestId(requestId).writer(writer).chainId(chainId).build())
			.addListener(sent -> {
				if (!sent.isSuccess()) {
					reply.completeExceptionally(sent.cause());
				}
			});

		return answered;
	}

	/* The first member names the chain, which must be one of the group's: a member that names another pins the node
	   to a chain no other member of the group has. The writer claims it before the node follows it, so that the
	   release of an older attachment on the same chain cannot unfollow it from under this join; a member that
	   admitted the writer to a chain this node does not follow is told it left. */
	private ChainId followedFirst(final Channel stream, final WriterId writer, final List<ChainId> chains,
		final JoinReply reply) {

		final ChainId chainId = joined(reply);

		if (!chains.contains(chainId)) {
			leave(stream, chainId, writer);
			throw new IllegalStateException("The member named a chain outside its group");
		}

		synchronized (claims) {
			claims.put(writer, chainId);
		}

		if (!follows(chainId, stream, reply, writer)) {
			unclaimed(writer, chainId);
			unfollowUnclaimed(chainId);
			leave(stream, chainId, writer);
			throw new IllegalStateException("The chain cannot be followed through the member");
		}

		return chainId;
	}

	/* A second member is a spare path on another stream: the node stays with the first when it cannot attach to
	   one, and tells it to leave, as the member may have admitted the writer all the same */
	private CompletableFuture<List<Channel>> spare(final Channel first, final Optional<Channel> second,
		final WriterId writer, final ChainId chainId) {

		if (second.isEmpty()) {
			return CompletableFuture.completedFuture(List.of(first));
		}

		return asked(second.get(), writer, chainId, reply -> followedSecond(second.get(), writer, chainId, reply))
			.handle((attached, refusal) -> {
				if (refusal != null) {
					leave(second.get(), chainId, writer);
					return List.of(first);
				}

				return List.of(first, attached);
			});
	}

	/* A member that names another chain than the one asked for is no path to it */
	private Channel followedSecond(final Channel stream, final WriterId writer, final ChainId chainId,
		final JoinReply reply) {

		if (!chainId.equals(joined(reply)) || !follows(chainId, stream, reply, writer)) {
			throw new IllegalStateException("The member is no second path to the chain");
		}

		return stream;
	}

	private static ChainId joined(final JoinReply reply) {
		return Optional.ofNullable(reply).filter(answer -> answer.getStatus() == JoinReply.Status.JOINED)
			.flatMap(JoinReply::getChainId).orElseThrow(() -> new IllegalStateException("The member refused"));
	}

	/* The heads of a member this node chose anchor the feeds, but none of the node's own writer, which is new and
	   has no entry anywhere: a head named for it would pin the feed of the node itself. A follow that fails in any
	   way is a member this node does not follow through. */
	private boolean follows(final ChainId chainId, final Channel stream, final JoinReply reply,
		final WriterId writer) {

		try {
			final Map<WriterId, Head> anchors = new HashMap<>(Objects.requireNonNullElse(reply.getHeads(),
				Map.of()));

			anchors.remove(writer);
			return host.follow(chainId, stream, anchors);
		} catch (RuntimeException ex) {
			log.warn("Following a chain failed with {}", ex.getClass().getName());
			return false;
		}
	}

	private static void leave(final Channel stream, final ChainId chainId, final WriterId writer) {
		stream.writeAndFlush(LeaveChain.builder().chainId(chainId).writer(writer).build());
	}

	private boolean unclaimed(final WriterId writer, final ChainId chainId) {
		synchronized (claims) {
			return claims.remove(writer, chainId);
		}
	}

	/* Checked and unfollowed under one lock, so that a join which claims the chain meanwhile keeps it followed */
	private void unfollowUnclaimed(final ChainId chainId) {
		synchronized (claims) {
			if (!claims.containsValue(chainId)) {
				host.unfollow(chainId);
			}
		}
	}

	private void unfollowAll() {
		synchronized (claims) {
			claims.values().forEach(host::unfollow);
		}
	}
}

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
import java.util.Comparator;
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
import org.unigrid.hedgehog.proxy.group.Epoch;
import org.unigrid.hedgehog.proxy.group.GroupChoice;
import org.unigrid.hedgehog.proxy.group.GroupView;
import org.unigrid.hedgehog.proxy.group.ProxySettings;
import org.unigrid.hedgehog.proxy.session.ExitCandidate;
import org.unigrid.hedgehog.proxy.session.NoiseNk;

/* The client side of the shard groups: finds a group that takes this node, asks a member to let a writer of this
   node write on one of its chains, attaches to a second member of the group on the same chain and follows the
   chain through both. The group is chosen by its members in the country the exit of a session is to be in, so the
   node attaches to one group for each country, up to MAX_ATTACHMENTS of them. A group that refuses is passed over
   for another, and a new epoch brings new chains, so an attachment is joined again when the epoch has moved on.

   The monitor of the client guards the joins in use and may take the lock of the claims, never the other way
   round; neither is held across a wait. What the replies of the members set going runs on the threads that
   complete them and takes the lock of the claims alone, for a put or an unfollow. */
@Slf4j
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public final class ChainClient {
	/* The most groups the node attaches to at once, one for each country asked for until then */
	static final int MAX_ATTACHMENTS = 4;

	private static final String NO_GROUP_IN_THE_COUNTRY = "No shard group has a gridnode in the country";

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

	/* The joins under way and the attachments in use, at most one for each group chosen and MAX_ATTACHMENTS in all,
	   and the join each country was given, the empty one for any. The next call for an attachment lets go of a join
	   that failed or whose attachment is no longer in use, and of the countries given it. */
	private final List<Join> joined = new ArrayList<>();
	private final Map<Optional<String>, Join> given = new HashMap<>();

	/* A join of this node, made or under way: the epoch, and the group it was asked of with the members of that
	   group, which its attachment tells instead once it is made, as another group may have taken the node */
	private record Join(Epoch epoch, int group, List<String> members, CompletableFuture<ChainAttachment> attachment) {
		int joinedGroup() {
			return held().map(ChainAttachment::group).orElse(group);
		}

		List<String> joinedMembers() {
			return held().map(ChainAttachment::members).orElse(members);
		}

		Optional<ChainAttachment> held() {
			return attachment.isDone() && !attachment.isCompletedExceptionally() ? Optional.of(attachment.join())
				: Optional.empty();
		}
	}

	/* What the groups of a join are drawn from: the view, the country and where each member is, and the groups that
	   refused the node or that it hosts */
	private record Draw(GroupView view, Optional<String> country, Function<String, Optional<String>> countryOf,
		Set<Integer> passedOver) {

		OptionalInt next(final RandomGenerator random) {
			return GroupChoice.group(view.groups(), country, countryOf, passedOver, random);
		}
	}

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

	/* The chain to write on for a session whose exit is to be in the country, in any for none. A country keeps the
	   join it was given while that is under way or in use. Otherwise a group is drawn by its members in the country,
	   and the join of that group is taken, or made while the node has fewer than MAX_ATTACHMENTS joins; beyond
	   that, the country is given the join whose group has the most members there. Callers that come while a join is
	   under way share it, and a join that failed is made again by the next caller. Each caller gets a copy, so that
	   none can complete or cancel the join of the others. What a caller chains on it runs on a thread of the join
	   executor, never on the event loop that completed the last reply, so it may do real work. */
	public CompletableFuture<ChainAttachment> attachment(final Optional<String> country) {
		return country.isPresent() ? withExitIn(country, new HashSet<>()) : joinFor(country, Set.of());
	}

	/* A join made for this country never asks a pair that holds all of the country, but an attachment made for
	   another country or for none may, and the exits of any may have gone while it was joined. Such an attachment is
	   passed over for the country, which is drawn again among the other groups. Each draw passes over one group
	   more, so the draws end once no group with a member in the country is left. The join passed over stays in use
	   for the other countries and for the sessions that name none. */
	private CompletableFuture<ChainAttachment> withExitIn(final Optional<String> country,
		final Set<Integer> passedOver) {

		return joinFor(country, passedOver).thenComposeAsync(attachment -> {
			if (attachment.exit(country, Set.of()).isPresent()) {
				return CompletableFuture.completedFuture(attachment);
			}

			return passedOver.add(attachment.group()) ? withExitIn(country, passedOver)
				: CompletableFuture.failedFuture(new IllegalStateException(NO_GROUP_IN_THE_COUNTRY));
		}, joins);
	}

	private synchronized CompletableFuture<ChainAttachment> joinFor(final Optional<String> country,
		final Set<Integer> passedOver) {

		dropUnused();

		try {
			return given.compute(country, (key, join) -> join != null && !passedOver.contains(join.joinedGroup())
				? join : chosen(key, passedOver)).attachment().copy();
		} catch (RuntimeException ex) {
			return CompletableFuture.failedFuture(ex);
		}
	}

	/* An attachment that is not in use is let go of by the next call for an attachment, not here: until then it is in
	   use again when the clock steps back over the turn of the epoch */
	public synchronized boolean isCurrent(final ChainAttachment attachment) {
		final Optional<Epoch> epoch = currentEpoch();

		return joined.stream().anyMatch(join -> join.held().filter(held -> held == attachment).isPresent())
			&& inUse(attachment, epoch);
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

	/* Lets go of the joins that failed and of the attachments no longer in use, and of the countries given them */
	private void dropUnused() {
		final Optional<Epoch> epoch = currentEpoch();

		joined.removeIf(join -> join.attachment().isDone() && join.held().filter(attachment ->
			inUse(attachment, epoch)).isEmpty());
		given.values().removeIf(join -> !joined.contains(join));
	}

	/* The members drop their writers when the proxy turns off, so the replicas are dropped then too; isCurrent notices
	   it as well, for an owner that keeps asking about its attachments without asking for a new one. Settings that
	   cannot be read and a clock before the origin have no epoch, which leaves nothing in use, and the join that
	   follows fails with them. */
	private Optional<Epoch> currentEpoch() {
		try {
			final Optional<ProxySettings> proxy = settings.current();
			final long now = clock.getAsLong();

			if (proxy.isEmpty()) {
				unfollowAll();
			}

			return proxy.map(on -> on.epochAt(now));
		} catch (RuntimeException ex) {
			return Optional.empty();
		}
	}

	/* While its epoch is the current one and its chain is still followed */
	private boolean inUse(final ChainAttachment attachment, final Optional<Epoch> epoch) {
		try {
			return claimed(attachment) && epoch.equals(Optional.of(attachment.epoch()))
				&& host.chain(attachment.chainId()).isPresent();
		} catch (RuntimeException ex) {
			return false;
		}
	}

	/* Not once it was released, even when an older writer of the node keeps the same chain followed */
	private boolean claimed(final ChainAttachment attachment) {
		synchronized (claims) {
			return attachment.chainId().equals(claims.get(attachment.writerId()));
		}
	}

	/* A gridnode in a group hosts the chains of that group, so it never joins it as a client. Its group is taken
	   from the view the candidates come from, as the hosting may be a refresh behind at the turn of an epoch. A
	   country that no gridnode of another group is in, as every country is without a GeoIP database, fails at once:
	   no member is asked to take the node for a session that no exit could carry. */
	private Join chosen(final Optional<String> country, final Set<Integer> passedOver) {
		final ProxySettings proxy = settings.current()
			.orElseThrow(() -> new IllegalStateException("The proxy is off"));
		final List<Gridnode> active = directory.active();
		final GroupView view = new GroupView(proxy, clock.getAsLong(), active.stream().map(Gridnode::getId)
			.filter(Objects::nonNull).toList());
		final Draw draw = new Draw(view, country, countriesOf(active), Stream.concat(directory.self().stream()
			.flatMapToInt(own -> view.groups().groupOf(own).stream()).boxed(), passedOver.stream())
			.collect(Collectors.toCollection(HashSet::new)));
		final int group = draw.next(random).orElseThrow(() -> new IllegalStateException(country.isPresent()
			? NO_GROUP_IN_THE_COUNTRY : "No shard group took this node"));

		return joined.stream().filter(join -> join.epoch().equals(view.epoch()) && join.joinedGroup() == group)
			.findFirst().orElseGet(() -> joined.size() < MAX_ATTACHMENTS ? started(draw, group)
			: closest(draw));
	}

	/* Joins the group drawn on the executor, or the next one drawn the same way when it refuses */
	private Join started(final Draw draw, final int group) {
		final Join join = new Join(draw.view().epoch(), group, draw.view().groups().members(group),
			CompletableFuture.supplyAsync(() -> nextGroup(draw, OptionalInt.of(group)), joins)
			.thenCompose(Function.identity()).thenApplyAsync(Function.identity(), joins));

		joined.add(join);
		return join;
	}

	/* The earliest of the joins not passed over whose groups have the most members in the country, the largest group
	   for none */
	private Join closest(final Draw draw) {
		return joined.stream().filter(join -> !draw.passedOver().contains(join.joinedGroup()))
			.max(Comparator.comparingLong(join -> GroupChoice.weight(join.joinedMembers(), draw.country(),
			draw.countryOf()))).orElseThrow(() -> new IllegalStateException(NO_GROUP_IN_THE_COUNTRY));
	}

	/* Where each active gridnode is by the address it announces, and nowhere for a member the directory lacks */
	private Function<String, Optional<String>> countriesOf(final List<Gridnode> active) {
		final Map<String, String> hostNames = new HashMap<>();

		active.stream().filter(gridnode -> Objects.nonNull(gridnode.getId()))
			.forEach(gridnode -> hostNames.putIfAbsent(gridnode.getId(), gridnode.getHostName()));

		return member -> countries.countryOfHost(hostNames.get(member));
	}

	/* A group that refuses is passed over for another drawn the same way, until one takes the node: one without an
	   open stream to any member, or with no exit in the country beside the pair it would attach through, at once,
	   the others once they answered. The next group is looked for on the executor, as an answer arrives on an event
	   loop or on the timer of the timeouts. */
	private CompletableFuture<ChainAttachment> nextGroup(final Draw draw, final OptionalInt first) {
		for (OptionalInt group = first; group.isPresent(); group = draw.next(random)) {
			final List<String> members = draw.view().groups().members(group.getAsInt());
			final Map<Channel, String> connected = connected(members);

			draw.passedOver().add(group.getAsInt());

			if (!connected.isEmpty() && hasExitBeside(members, connected.values().stream().limit(2).toList(),
				draw.country())) {
				return attach(draw.view(), group.getAsInt(), connected)
					.exceptionallyComposeAsync(refusal -> nextGroup(draw, draw.next(random)), joins);
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

	/* The pair is drawn uniformly among the members the node holds open streams to, whatever the country. When it
	   holds every member in the country that could be the exit, the group is passed over before either is asked:
	   asked and then left without a session, the two would learn that the country is theirs. No country needs no
	   such check, as any member may be the exit then. */
	private boolean hasExitBeside(final List<String> members, final List<String> pair, final Optional<String> country) {
		if (country.isEmpty()) {
			return true;
		}

		final Map<String, Gridnode> candidates = exitCandidates(members, pair);

		return GroupChoice.weight(List.copyOf(candidates.keySet()), country,
			member -> countries.countryOfHost(candidates.get(member).getHostName())) > 0;
	}

	/* A member of the group is a candidate exit while it is active and announces a sealing key, unless the node
	   asked it to attach: that member sees the node's address and learned its writer, whether it took the node or
	   not, and as the exit it would see both ends of a session */
	private Map<String, Gridnode> exitCandidates(final List<String> members, final List<String> askedMembers) {
		return directory.active().stream().filter(gridnode -> members.contains(gridnode.getId()))
			.filter(gridnode -> !askedMembers.contains(gridnode.getId())).filter(ChainClient::hasSealingKey)
			.collect(Collectors.toMap(Gridnode::getId, Function.identity(), (first, second) -> first));
	}

	/* Each session draws its exit at random among the candidates in the country it names that it did not pass
	   over */
	private ChainAttachment.Exits exitsAmong(final List<String> members, final List<String> askedMembers) {
		return (country, excluded) -> {
			final Map<String, Gridnode> candidates = exitCandidates(members, askedMembers);

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

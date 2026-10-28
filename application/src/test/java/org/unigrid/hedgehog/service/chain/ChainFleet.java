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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.netty.channel.Channel;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.embedded.EmbeddedChannel;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.random.RandomGenerator;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.slf4j.LoggerFactory;
import org.unigrid.hedgehog.model.gridnode.Gridnode;
import org.unigrid.hedgehog.model.gridnode.SealingKey;
import org.unigrid.hedgehog.model.network.PendingRequests;
import org.unigrid.hedgehog.model.network.codec.ChainCodecs;
import org.unigrid.hedgehog.model.network.packet.ChainEntry;
import org.unigrid.hedgehog.model.network.packet.ChainHeads;
import org.unigrid.hedgehog.model.network.packet.EntryRefused;
import org.unigrid.hedgehog.model.network.packet.FetchEntries;
import org.unigrid.hedgehog.model.network.packet.JoinChain;
import org.unigrid.hedgehog.model.network.packet.JoinReply;
import org.unigrid.hedgehog.model.network.packet.LeaveChain;
import org.unigrid.hedgehog.model.storage.placement.GridnodeDirectory;
import org.unigrid.hedgehog.model.storage.placement.TopologyGridnodeDirectory;
import org.unigrid.hedgehog.proxy.chain.ChainId;
import org.unigrid.hedgehog.proxy.chain.Entry;
import org.unigrid.hedgehog.proxy.chain.EntryKind;
import org.unigrid.hedgehog.proxy.chain.FeedWriter;
import org.unigrid.hedgehog.proxy.chain.Fingerprint;
import org.unigrid.hedgehog.proxy.chain.WriterId;
import org.unigrid.hedgehog.proxy.chain.WriterKey;
import org.unigrid.hedgehog.proxy.group.GroupView;
import org.unigrid.hedgehog.proxy.group.ProxySettings;
import org.unigrid.hedgehog.proxy.session.Direction;
import org.unigrid.hedgehog.proxy.session.NoiseNk;
import org.unigrid.hedgehog.proxy.session.NoiseNk.Responder;
import org.unigrid.hedgehog.proxy.session.Tags;

/* Gridnodes that share one clock, one topology and one set of settings, each with its own host. A stream
   between two hosts is an embedded channel at each end. Threads may share a fleet once its hosts are made: any of
   them may write to the streams, look them up and read what was delivered, and one at a time delivers. */
final class ChainFleet {
	static final long NOW = 1_750_000_000_000L;
	static final int MEMBERS = 4;
	private static final int MAX_ROUNDS = 100;
	private static final int MAX_PACKETS_PER_ROUND = 1_000;

	record Delivery(String from, String to, Object packet) {
		/* One packet that went from a host to another */
	}

	/* Each end of a stream is a channel of its own, so the key tells whose end it is */
	private record Link(String owner, String peer) {
	}

	final AtomicLong clock = new AtomicLong(NOW);
	final AtomicReference<Optional<ProxySettings>> settings = new AtomicReference<>(Optional.of(ProxySettings.DEFAULTS));
	final List<Gridnode> gridnodes = new CopyOnWriteArrayList<>();
	final Random random = new Random(1);
	final List<Delivery> deliveries = new CopyOnWriteArrayList<>();
	private final Map<String, ChainHost> hosts = new LinkedHashMap<>();
	private final Map<String, ManualScheduler> schedulers = new LinkedHashMap<>();
	private final Map<String, PendingRequests> pendings = new ConcurrentHashMap<>();
	private final Map<Link, EmbeddedChannel> streams = Collections.synchronizedMap(new LinkedHashMap<>());
	private final Set<String> offline = new HashSet<>();

	ChainFleet(final int size) {
		IntStream.range(0, size).forEach(number -> gridnodes.add(gridnode("gn-" + number, Gridnode.Status.ACTIVE)));
	}

	/* Each gridnode announces the public half of a sealing key drawn from its id, so that a replaced entry keeps it
	   and its exit can rebuild the private half */
	static Gridnode gridnode(final String id, final Gridnode.Status status) {
		return Gridnode.builder().id(id).status(status).hostName(id + ":1")
			.sealingKey(sealingKey(id).publicKey().orElseThrow()).build();
	}

	static SealingKey sealingKey(final String id) {
		try {
			return SealingKey.of(MessageDigest.getInstance("SHA-256").digest(("sealing " + id)
				.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is mandatory on every Java platform", ex);
		}
	}

	/* The member among the given ones whose sealing key opens the entry as an opening, with the chain and the writer
	   of the entry in the prologue */
	static Optional<String> openerOf(final Entry entry, final Collection<String> members) {
		return members.stream().filter(member -> responderOf(entry, member).isPresent()).findFirst();
	}

	static Optional<Responder> responderOf(final Entry entry, final String member) {
		final byte[] prologue = ByteBuffer.allocate(ChainId.SIZE + WriterId.SIZE)
			.put(entry.header().chainId().bytes()).put(entry.header().writer().bytes()).array();

		return NoiseNk.respond(sealingKey(member).privateKey().orElseThrow(), prologue,
			Arrays.copyOf(entry.payload(), NoiseNk.MESSAGE_SIZE));
	}

	/* The tag of the answer to an opening, in the fingerprint field where entries carry it */
	static Fingerprint answerTagOf(final Entry opening, final String member) {
		return new Fingerprint(Tags.of(responderOf(opening, member).orElseThrow().answerTagKey(),
			Direction.EXIT_TO_CLIENT, 0).bytes());
	}

	static ProxySettings settings(final int groupSize, final int chainsPerGroup, final int clientsPerChain,
		final int retentionSeconds, final int flags) {

		return new ProxySettings(groupSize, 3600, chainsPerGroup, clientsPerChain, 32L * 1024 * 1024,
			retentionSeconds, 16 * 1024, 300, flags);
	}

	/* Chains of 1 KiB hold five of the small entries `entries` writes */
	static ProxySettings tinyChains() {
		return new ProxySettings(8, 3600, 4, 16, 1024, 30, 1024, 300, 3);
	}

	/* Chains that hold the given number of entries of 1 KiB, which is both the smallest size class and the largest
	   entry, so that every entry of a session takes the same room */
	static ProxySettings smallChains(final int entries) {
		return new ProxySettings(8, 3600, 4, 16, entries * 1024L, 30, 1024, 300, 3);
	}

	/* Four gridnodes in one group, so each of them hosts the same chains */
	static ChainFleet group(final ProxySettings settings) {
		final ChainFleet fleet = new ChainFleet(MEMBERS);

		fleet.settings.set(Optional.of(settings));
		IntStream.range(0, MEMBERS).forEach(member -> fleet.host("gn-" + member));
		fleet.refreshAll();
		return fleet;
	}

	static ChainFleet group() {
		return group(settings(MEMBERS, 4, 16, 30, 3));
	}

	static WriterKey key(final int number) {
		return new WriterKey(ByteBuffer.allocate(WriterKey.SEED_SIZE).putInt(number).array());
	}

	static WriterId writer(final int number) {
		return key(number).id();
	}

	/* The first entries of one writer's feed, each timestamped at the given time */
	static List<Entry> entries(final WriterKey key, final ChainId chainId, final int count, final long timestamp) {
		return write(new FeedWriter(key, chainId), count, timestamp);
	}

	/* The next entries of a feed, so that one writer can write at different times */
	static List<Entry> write(final FeedWriter feed, final int count, final long timestamp) {
		return IntStream.range(0, count).mapToObj(number -> feed.write(timestamp, Fingerprint.NONE, EntryKind.SEALED,
			new byte[] { (byte) number })).toList();
	}

	ChainHost host(final String id) {
		return hosts.computeIfAbsent(id, own -> hostOver(own, transport(own)));
	}

	GridnodeDirectory directory(final String own) {
		return new TopologyGridnodeDirectory(() -> gridnodes, () -> own);
	}

	ChainTransport transport(final String own) {
		return peer -> streamBetween(own, peer);
	}

	/* What the packet handlers of a node complete with the replies it receives */
	PendingRequests pending(final String id) {
		return pendings.computeIfAbsent(id, own -> new PendingRequests());
	}

	/* A host that is not one of the fleet, for a transport the test supplies */
	ChainHost hostOver(final String id, final ChainTransport transport) {
		return hostOver(id, transport, random);
	}

	/* The same, for a test that decides which member the host picks */
	ChainHost hostOver(final String id, final ChainTransport transport, final RandomGenerator picks) {
		return new ChainHost(settings::get, directory(id), transport, clock::get, picks,
			schedulers.computeIfAbsent(id, scheduler -> new ManualScheduler()));
	}

	ManualScheduler scheduler(final String id) {
		return schedulers.get(id);
	}

	/* The first chain of the group, which every member hosts when the fleet is a single group */
	ChainId chain() {
		return host("gn-0").hostedChains().get(0);
	}

	/* A client that joins that chain through a stream of its own at a member */
	EmbeddedChannel client(final String member, final int writer) {
		final EmbeddedChannel stream = new EmbeddedChannel();

		host(member).join(stream, JoinChain.builder().requestId(writer).writer(writer(writer))
			.chainId(chain()).build());
		return stream;
	}

	/* A stream that has more written to it than it takes, and so stays unwritable until it is flushed */
	static EmbeddedChannel unwritable(final EmbeddedChannel stream) {
		stream.config().setWriteBufferWaterMark(new WriteBufferWaterMark(1, 2));
		stream.write("Not flushed");
		return stream;
	}

	/* The members whose streams from the node an attachment of the node holds */
	List<String> attachMembers(final String node, final ChainAttachment attachment) {
		return gridnodes.stream().map(Gridnode::getId).filter(id -> Optional.ofNullable(streams.get(new Link(node, id)))
			.filter(attachment.streams()::contains).isPresent()).toList();
	}

	/* An offline host gets no stream from the others until it is back */
	void offline(final String id) {
		offline.add(id);
	}

	void online(final String id) {
		offline.remove(id);
	}

	EmbeddedChannel stream(final String owner, final String peer) {
		return streams.computeIfAbsent(new Link(owner, peer), link -> new SynchronizedChannel());
	}

	/* One second passes: every online host does what it does each second, and what that sets going is
	   delivered */
	void round() {
		clock.addAndGet(1000);
		hosts.forEach((id, host) -> {
			if (!offline.contains(id)) {
				host.tick();
			}
		});

		deliver();
	}

	/* Whether every online host holds the same heads and the same entries of a chain */
	boolean agree(final ChainId chainId) {
		return hosts.entrySet().stream().filter(host -> !offline.contains(host.getKey())).map(host -> host.getValue()
			.chain(chainId).orElseThrow()).map(chain -> List.of(chain.heads(), chain.entries())).distinct().count() == 1;
	}

	/* Hands over what the hosts have written to each other, a round of hops at a time so that the order does not
	   depend on the streams, until the fleet is quiet. A fleet that talks for many rounds passes the same packets
	   around in circles, which a test must fail on and not wait for. */
	synchronized void deliver() {
		for (int round = 0; round < MAX_ROUNDS; round++) {
			if (!hop()) {
				return;
			}
		}

		throw new IllegalStateException("The fleet keeps passing packets around");
	}

	/* One round of hops: hands over what the hosts have written so far and answers whether there was any. A thread
	   that delivers while others write calls this, as the fleet is then never quiet for long. A round that holds
	   more than any test sends at once is packets that keep growing as they go round, and it throws rather than
	   drop them, so the caller must not swallow it. */
	synchronized boolean hop() {
		final List<Map.Entry<Link, Object>> written = drain();

		if (written.size() > MAX_PACKETS_PER_ROUND) {
			throw new IllegalStateException("A round of the fleet holds more packets than a test sends");
		}

		written.forEach(sent -> route(sent.getKey(), sent.getValue()));
		return !written.isEmpty();
	}

	/* In the order the streams were made, whichever thread made them */
	private List<Map.Entry<Link, Object>> drain() {
		final List<Map.Entry<Link, Object>> written = new ArrayList<>();
		final Map<Link, EmbeddedChannel> made;

		synchronized (streams) {
			made = new LinkedHashMap<>(streams);
		}

		made.forEach((link, stream) -> {
			for (Object packet = stream.readOutbound(); packet != null; packet = stream.readOutbound()) {
				written.add(Map.entry(link, packet));
			}
		});

		return written;
	}

	private Optional<Channel> streamBetween(final String owner, final String peer) {
		return hosts.containsKey(peer) && !offline.contains(peer) ? Optional.of(stream(owner, peer)) : Optional.empty();
	}

	private void route(final Link from, final Object packet) {
		if (hosts.containsKey(from.peer()) && !offline.contains(from.peer())) {
			deliveries.add(new Delivery(from.owner(), from.peer(), packet));
			dispatch(from.peer(), stream(from.peer(), from.owner()), packet);
		}
	}

	/* What the packet handlers do with each packet: hand it to the host and write back what it answers */
	private void dispatch(final String id, final Channel stream, final Object packet) {
		final ChainHost host = hosts.get(id);

		if (packet instanceof EntryRefused refusal) {
			host.refused(stream, refusal);
		} else if (packet instanceof JoinReply reply) {
			pending(id).complete(stream, reply);
		} else if (packet instanceof LeaveChain leave) {
			host.leave(stream, leave);
		} else {
			answer(host, stream, packet);
		}
	}

	/* The packets that the receiving host answers on the stream they came in on */
	private static void answer(final ChainHost host, final Channel stream, final Object packet) {
		if (packet instanceof ChainEntry entries) {
			host.receive(stream, entries).forEach(stream::writeAndFlush);
		} else if (packet instanceof ChainHeads heads) {
			host.reconcile(stream, heads).ifPresent(stream::writeAndFlush);
		} else if (packet instanceof FetchEntries request) {
			ChainCodecs.batches(host.fetch(stream, request)).forEach(stream::writeAndFlush);
		} else if (packet instanceof JoinChain request) {
			stream.writeAndFlush(host.join(stream, request));
		}
	}

	List<String> activeIds() {
		return gridnodes.stream().filter(gridnode -> gridnode.getStatus() == Gridnode.Status.ACTIVE)
			.map(Gridnode::getId).toList();
	}

	GroupView view() {
		return new GroupView(settings.get().orElseThrow(), clock.get(), activeIds());
	}

	void refreshAll() {
		new ArrayList<>(hosts.values()).forEach(ChainHost::refresh);
	}

	/* What the hosts and their listeners warn about while the action runs. The module descriptor reads
	   logback-classic only, so the in-memory appender of logback-core is out of reach until the module is made
	   to read it. */
	static List<ILoggingEvent> warningsWhile(final Runnable action) {
		ChainFleet.class.getModule().addReads(ModuleLayer.boot().findModule("ch.qos.logback.core").orElseThrow());

		final List<Logger> loggers = Stream.of(ChainHost.class, Listeners.class)
			.map(logged -> (Logger) LoggerFactory.getLogger(logged)).toList();
		final ListAppender<ILoggingEvent> appender = new ListAppender<>();

		appender.start();
		loggers.forEach(logger -> logger.addAppender(appender));

		try {
			action.run();
		} finally {
			loggers.forEach(logger -> logger.detachAppender(appender));
		}

		return appender.list.stream().filter(event -> event.getLevel().isGreaterOrEqual(Level.WARN)).toList();
	}
}

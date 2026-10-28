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

package org.unigrid.hedgehog.proxy.session;

import io.netty.handler.codec.socksx.v5.Socks5AddressType;
import io.netty.handler.codec.socksx.v5.Socks5CommandStatus;
import io.netty.handler.codec.socksx.v5.Socks5CommandType;
import java.lang.reflect.Field;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import javax.crypto.spec.SecretKeySpec;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import org.unigrid.hedgehog.proxy.Expect;
import org.unigrid.hedgehog.proxy.chain.Entry;
import org.unigrid.hedgehog.proxy.chain.EntryKind;
import org.unigrid.hedgehog.proxy.chain.FeedWriter;
import org.unigrid.hedgehog.proxy.chain.Fingerprint;
import org.unigrid.hedgehog.proxy.chain.Gap;
import org.unigrid.hedgehog.proxy.chain.SizeClasses;
import org.unigrid.hedgehog.proxy.chain.Tag;
import org.unigrid.hedgehog.proxy.chain.WriterId;
import org.unigrid.hedgehog.proxy.group.ProxySettings;
import org.unigrid.hedgehog.proxy.session.NoiseNk.SessionSecrets;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

public class SessionTest {
	private static final Tag TAG = new Tag(SessionFixtures.filled(Tag.SIZE, 5));
	private static final SessionSecrets SECRETS = new SessionSecrets(SessionFixtures.filled(32, 3),
		SessionFixtures.filled(32, 4), SessionFixtures.filled(32, 5), SessionFixtures.filled(32, 6),
		SessionFixtures.filled(32, 7));
	private static final SocksAddress TARGET = new SocksAddress(Socks5AddressType.DOMAIN, "example.org", 53);
	private static final Close BROKEN = new Close(CloseReason.BROKEN);
	private static final Data DATA_ONE = new Data(new byte[] { 1 });
	/* TARGET on the wire: the type, the length, the name and the port */
	private static final int TARGET_ADDRESS_BYTES = Byte.BYTES + Byte.BYTES + "example.org".length() + Short.BYTES;
	private static final int SMALLEST_CLASS = 1024;
	/* What a short message is sealed to: the payload of an entry of the smallest class */
	private static final int SEALED_BYTES = SMALLEST_CLASS - Entry.OVERHEAD;
	private static final String OUTBOX_FULL = "The outbox is full";
	private static final int THREADS = 8;
	private static final int SENDS = 50;
	private static final int WAIT_SECONDS = 10;
	private static final Map<Tag, Long> CLIENT_TAGS = LongStream.range(0, THREADS * SENDS + Session.WINDOW).boxed()
		.collect(Collectors.toMap(SessionTest::clientTag, Function.identity()));

	/* A client and an exit session keyed from one handshake, as the handshake would leave them */
	private static final class Pair {
		private final AtomicLong clock = new AtomicLong(SessionFixtures.NOW);
		private final List<Entry> fromClient = new ArrayList<>();
		private final List<Entry> fromExit = new ArrayList<>();
		private final SessionContext clientContext = SessionFixtures.context(1, fromClient, clock::get);
		private final SessionContext exitContext = SessionFixtures.context(2, fromExit, clock::get);
		private final Session client = new Session(clientContext, exitContext.writer().writer(),
			Direction.CLIENT_TO_EXIT, SECRETS);
		private final Session exit = new Session(exitContext, clientContext.writer().writer(),
			Direction.EXIT_TO_CLIENT, SECRETS);

		private static List<SealedMessage> deliver(List<Entry> entries, Session session) {
			final List<SealedMessage> received = entries.stream().map(session::receive).flatMap(Optional::stream)
				.collect(Collectors.toList());

			entries.clear();
			return received;
		}

		private List<SealedMessage> toExit() {
			return deliver(fromClient, exit);
		}

		private List<SealedMessage> toClient() {
			return deliver(fromExit, client);
		}
	}

	/* What a peer that holds the keys can post: records sealed outside its Session, and so outside the limits
	   the Session keeps, each under the client's next tag */
	private static final class ClientSealer {
		private final Pair pair;
		private final SealingCipher.Sealer sealer;
		private long counter;

		private ClientSealer(Pair pair, byte[] key, Direction direction, byte[] binding) {
			this.pair = pair;
			sealer = SealingCipher.sealer(key, direction, binding);
		}

		private ClientSealer(Pair pair) {
			this(pair, SECRETS.clientToExit(), Direction.CLIENT_TO_EXIT, SECRETS.binding());
		}

		/* Sealed to its class where it fits one, and to exactly its size where it does not */
		private Entry post(byte[] plaintext) {
			final int sealedSize = plaintext.length + SealingCipher.OVERHEAD;
			final int padded = SizeClasses.fit(Entry.OVERHEAD + sealedSize, SMALLEST_CLASS * 16)
				.orElse(Entry.OVERHEAD + sealedSize) - Entry.OVERHEAD;

			return pair.clientContext.post(clientTag(counter++), sealer.seal(plaintext, padded));
		}
	}

	/* An outbox that keeps every entry it is given and, while told to, throws after taking it */
	private static final class FlakyOutbox implements Consumer<Entry> {
		private final List<Entry> taken = new ArrayList<>();
		private boolean refusing;

		@Override
		public void accept(Entry entry) {
			taken.add(entry);

			if (refusing) {
				throw new IllegalStateException(OUTBOX_FULL);
			}
		}
	}

	/* An action on a thread of its own, whose failure surfaces when it is finished */
	private static final class Background {
		private final FutureTask<Object> task;
		private final Thread thread;

		private Background(Runnable action) {
			task = new FutureTask<>(action, null);
			thread = new Thread(task);
			thread.setDaemon(true);
			thread.start();
		}

		private void awaitBlocked() throws InterruptedException {
			final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);

			while (thread.isAlive() && thread.getState() != Thread.State.BLOCKED
				&& System.nanoTime() < deadline) {
				Thread.sleep(1);
			}

			assertThat(thread.getState(), is(Thread.State.BLOCKED));
		}

		private void finish() throws Exception {
			task.get(WAIT_SECONDS, TimeUnit.SECONDS);
		}
	}

	private static Tag clientTag(long counter) {
		return Tags.of(SECRETS.clientTags(), Direction.CLIENT_TO_EXIT, counter);
	}

	/* The counter of each entry, read back from its tag */
	private static List<Long> counters(List<Entry> entries) {
		return entries.stream().map(entry -> CLIENT_TAGS.get(Session.tagOf(entry))).collect(Collectors.toList());
	}

	/* A session of one end of the pair on the same writer, as the pair's own session is but on another outbox */
	private static Session over(Pair pair, SessionContext own, SessionContext other, Direction sending,
		Consumer<Entry> outbox) {
		return new Session(new SessionContext(own.writer(), outbox, pair.clock::get, new SecureRandom(),
			ProxySettings.DEFAULTS), other.writer().writer(), sending, SECRETS);
	}

	private static List<Entry> sent(Pair pair, int count) {
		IntStream.range(0, count).forEach(i -> pair.client.send(new Data(new byte[] { (byte) i })));

		final List<Entry> entries = new ArrayList<>(pair.fromClient);

		pair.fromClient.clear();
		return entries;
	}

	private static Optional<SealedMessage> afterDelivering(Pair pair, List<Entry> entries, int... indexes) {
		Optional<SealedMessage> last = Optional.empty();

		for (int index : indexes) {
			last = pair.exit.receive(entries.get(index));
		}

		return last;
	}

	/* The exit closed and said so once, and the client took that Close and closed without an answer */
	private static void assertExitBrokeAndTold(Pair pair) {
		assertThat(pair.exit.isOpen(), is(false));
		assertThat(pair.fromExit, hasSize(1));
		pair.fromClient.clear();
		assertThat(pair.toClient(), equalTo(List.of(BROKEN)));
		assertThat(pair.client.isOpen(), is(false));
		assertThat(pair.fromClient, is(empty()));
	}

	private static Optional<SealedMessage> exitReceivesSealedWith(byte[] key, Direction direction, byte[] binding) {
		final Pair pair = new Pair();

		return pair.exit.receive(new ClientSealer(pair, key, direction, binding).post(SealedCodec.encode(DATA_ONE)));
	}

	/* SealingCipher raises an IllegalStateException for any provider failure but a bad tag, which a key of the wrong
	   size on the receiving side stands in for */
	private static void failToOpen(Session session) throws ReflectiveOperationException {
		final Object opener = field(Session.class, "opener").get(session);
		final Object cipher = field(SealingCipher.Opener.class, "cipher").get(opener);

		field(SealingCipher.class, "key").set(cipher, new SecretKeySpec(new byte[1], "AES"));
	}

	private static Field field(Class<?> type, String name) throws NoSuchFieldException {
		final Field field = type.getDeclaredField(name);

		field.setAccessible(true);
		return field;
	}

	/* A first action that has sealed and waits for the writer holds the session, so that whatever else touches the
	   session waits behind it and nothing seals ahead of it: the session has still sealed only one message */
	private static void assertHeldWhileWaitingForWriter(BiConsumer<Pair, Session> first,
		List<BiConsumer<Pair, Session>> others) throws Exception {
		for (BiConsumer<Pair, Session> other : others) {
			final Pair pair = new Pair();
			final Session client = over(pair, pair.clientContext, pair.exitContext, Direction.CLIENT_TO_EXIT,
				pair.fromClient::add);
			final Background holding;
			final Background waiting;

			pair.exit.send(new Data(new byte[] { 9 }));

			synchronized (pair.clientContext.writer()) {
				holding = new Background(() -> first.accept(pair, client));
				holding.awaitBlocked();
				waiting = new Background(() -> other.accept(pair, client));
				waiting.awaitBlocked();
				assertThat(field(Session.class, "sent").getLong(client), is(1L));
			}

			holding.finish();
			waiting.finish();
		}
	}

	private static Session exitFailingToOpen(Pair pair, Consumer<Entry> outbox) throws ReflectiveOperationException {
		final Session exit = over(pair, pair.exitContext, pair.clientContext, Direction.EXIT_TO_CLIENT, outbox);

		failToOpen(exit);
		return exit;
	}

	private static Session exitOnClock(Pair pair, LongSupplier clock) {
		final SessionContext context = new SessionContext(pair.exitContext.writer(), pair.fromExit::add, clock,
			new SecureRandom(), ProxySettings.DEFAULTS);

		return new Session(context, pair.clientContext.writer().writer(), Direction.EXIT_TO_CLIENT, SECRETS);
	}

	private static void awaitUninterruptibly(CountDownLatch latch) {
		try {
			latch.await(WAIT_SECONDS, TimeUnit.SECONDS);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

	@Provide
	public Arbitrary<List<SealedMessage>> traffic() {
		final Arbitrary<byte[]> bytes = Arbitraries.bytes().array(byte[].class).ofMaxSize(500);

		return Arbitraries.<SealedMessage>oneOf(bytes.map(Data::new), bytes.map(payload -> new Datagram(TARGET,
			payload))).list().ofMaxSize(15);
	}

	@Property(tries = 30)
	public void shouldCarryMessagesBothWaysInOrder(@ForAll("traffic") List<SealedMessage> toExit,
		@ForAll("traffic") List<SealedMessage> toClient) {
		final Pair pair = new Pair();

		toExit.forEach(pair.client::send);
		toClient.forEach(pair.exit::send);
		assertThat(pair.toExit(), equalTo(toExit));
		assertThat(pair.toClient(), equalTo(toClient));
	}

	@Example
	public void shouldWriteSealedEntriesOfTheSession() {
		final Pair pair = new Pair();

		pair.client.send(new Data(new byte[] { 1, 2, 3 }));
		assertThat(pair.fromClient, hasSize(1));
		assertThat(pair.fromClient.get(0).header().kind(), is(EntryKind.SEALED));
		assertThat(Session.tagOf(pair.fromClient.get(0)), equalTo(clientTag(0)));
		assertThat(pair.fromClient.get(0).header().writer(), equalTo(pair.clientContext.writer().writer()));
		assertThat(pair.fromClient.get(0).size(), is(SMALLEST_CLASS));
	}

	/* A message takes the smallest class that holds it, so its size tells no more than the class */
	@Example
	public void shouldPadEveryMessageToTheSmallestClassThatHoldsIt() {
		final int overhead = Entry.OVERHEAD + SealingCipher.OVERHEAD + SealedCodec.DATA_OVERHEAD;
		final Map<Integer, Integer> classOf = Map.of(0, SMALLEST_CLASS, SMALLEST_CLASS - overhead, SMALLEST_CLASS,
			SMALLEST_CLASS - overhead + 1, 4096, 4096 - overhead, 4096, 4096 - overhead + 1, 16 * 1024);

		classOf.forEach((bytes, size) -> {
			final Pair pair = new Pair();

			pair.client.send(new Data(new byte[bytes]));
			assertThat(pair.fromClient.get(0).size(), is(size));
			assertThat(pair.toExit(), equalTo(List.of(new Data(new byte[bytes]))));
		});
	}

	@Example
	public void shouldIgnoreEntriesThatAreNotThePeersForThisSession() {
		final Pair pair = new Pair();

		pair.client.send(new Data(new byte[] { 1 }));

		final Entry own = pair.fromClient.get(0);
		final Entry otherSession = pair.exitContext.post(TAG, new byte[30]);
		final Entry otherDirection = pair.exitContext.post(Tags.of(SECRETS.exitTags(), Direction.CLIENT_TO_EXIT, 0),
			new byte[32]);

		assertThat(pair.client.receive(own), equalTo(Optional.empty()));
		assertThat(pair.client.receive(otherSession), equalTo(Optional.empty()));
		assertThat(pair.client.receive(otherDirection), equalTo(Optional.empty()));
		assertThat(pair.client.isOpen(), is(true));
	}

	@Example
	public void shouldEndBothSidesWithAClose() {
		final Pair pair = new Pair();

		assertThat(pair.client.close(CloseReason.DONE), equalTo(Optional.of(new Close(CloseReason.DONE))));
		assertThat(pair.client.isOpen(), is(false));
		assertThat(pair.client.close(CloseReason.DONE), equalTo(Optional.empty()));
		assertThat(pair.toExit(), equalTo(List.of(new Close(CloseReason.DONE))));
		assertThat(pair.exit.isOpen(), is(false));
		Expect.assertThrows(IllegalStateException.class, () -> pair.exit.send(new Data(new byte[] { 1 })));
		assertThat(pair.fromExit, is(empty()));
	}

	@Example
	public void shouldBreakOnAnEntryThatDoesNotOpen() {
		final Pair pair = new Pair();
		final Entry forged = pair.clientContext.post(clientTag(0), new byte[40]);

		assertThat(pair.exit.receive(forged), equalTo(Optional.of(new Close(CloseReason.BROKEN))));
		assertThat(pair.exit.isOpen(), is(false));
		assertThat(pair.toClient(), equalTo(List.of(new Close(CloseReason.BROKEN))));
		assertThat(pair.client.isOpen(), is(false));
	}

	@Example
	public void shouldFitTheLargestDataInOneEntry() {
		final Pair pair = new Pair();
		final int largest = pair.client.maxDataBytes();

		assertThat(largest, is(16 * 1024 - Entry.OVERHEAD - SealingCipher.OVERHEAD - 1));
		Expect.assertThrows(IllegalArgumentException.class, () -> pair.client.send(new Data(new byte[largest + 1])));
		pair.client.send(new Data(new byte[largest]));
		assertThat(pair.fromClient.get(0).size(), is(16 * 1024));
		assertThat(pair.toExit(), equalTo(List.of(new Data(new byte[largest]))));
	}

	@Example
	public void shouldNameItsPeer() {
		final Pair pair = new Pair();

		assertThat(pair.client.peer(), equalTo(pair.exitContext.writer().writer()));
		assertThat(pair.exit.peer(), equalTo(pair.clientContext.writer().writer()));
		assertThat(pair.client.isOpen(), is(true));
	}

	@Example
	public void shouldCarryEveryKindOfMessageBothWays() {
		final Pair pair = new Pair();
		final SocksAddress bound = new SocksAddress(Socks5AddressType.IPv6, "2001:db8::1", 443);
		final List<SealedMessage> toExit = List.of(new Connect(Socks5CommandType.CONNECT, TARGET),
			new Connect(Socks5CommandType.UDP_ASSOCIATE, SocksAddress.UNSPECIFIED),
			new Datagram(bound, new byte[] { 9 }), new Data(new byte[0]), new Data(new byte[] { 1, 2, 3 }));
		final List<SealedMessage> toClient = List.of(new Connected(Socks5CommandStatus.SUCCESS, bound),
			new Connected(Socks5CommandStatus.HOST_UNREACHABLE, TARGET), new Datagram(TARGET, new byte[0]),
			new Data(new byte[] { 4 }));

		toExit.forEach(pair.client::send);
		toClient.forEach(pair.exit::send);
		assertThat(pair.toExit(), equalTo(toExit));
		assertThat(pair.toClient(), equalTo(toClient));
	}

	@Property
	public void shouldTellThePeerOnceWhateverTheReason(@ForAll CloseReason reason) {
		final Pair pair = new Pair();

		assertThat(pair.client.close(reason), equalTo(Optional.of(new Close(reason))));
		assertThat(pair.client.close(reason), equalTo(Optional.empty()));
		assertThat(pair.fromClient, hasSize(1));
		assertThat(pair.toExit(), equalTo(List.of(new Close(reason))));
		assertThat(pair.exit.close(reason), equalTo(Optional.empty()));
		assertThat(pair.exit.isOpen(), is(false));
		assertThat(pair.fromExit, is(empty()));
	}

	@Property
	public void shouldCloseOnSendingACloseOfAnyReason(@ForAll CloseReason reason) {
		final Pair pair = new Pair();

		pair.client.send(new Close(reason));
		assertThat(pair.client.isOpen(), is(false));
		assertThat(pair.client.close(reason), equalTo(Optional.empty()));
		assertThat(pair.toExit(), equalTo(List.of(new Close(reason))));
		assertThat(pair.exit.isOpen(), is(false));
		assertThat(pair.fromClient, is(empty()));
		assertThat(pair.fromExit, is(empty()));
	}

	@Example
	public void shouldReportBrokenToTheCallerAndThePeerOnlyOnce() {
		final Pair pair = new Pair();
		final Entry first = pair.clientContext.post(clientTag(0), new byte[40]);
		final Entry second = pair.clientContext.post(clientTag(1), new byte[41]);

		assertThat(pair.exit.receive(first), equalTo(Optional.of(BROKEN)));
		assertThat(pair.exit.receive(second), equalTo(Optional.empty()));
		assertThat(pair.exit.close(CloseReason.BROKEN), equalTo(Optional.empty()));
		assertExitBrokeAndTold(pair);
	}

	@Example
	public void shouldRefuseToSendOnceClosed() {
		final Pair pair = new Pair();

		pair.client.close(CloseReason.DONE);
		Expect.assertThrows(IllegalStateException.class, () -> pair.client.send(new Data(new byte[] { 1 })));
		Expect.assertThrows(IllegalStateException.class, () -> pair.client.send(new Close(CloseReason.DONE)));
		assertThat(pair.fromClient, hasSize(1));
	}

	@Example
	public void shouldIgnoreWhatArrivesOnceClosed() {
		final Pair pair = new Pair();
		final ClientSealer peer = new ClientSealer(pair);
		final Entry close = peer.post(SealedCodec.encode(new Close(CloseReason.DONE)));
		final Entry next = peer.post(SealedCodec.encode(new Data(new byte[] { 1 })));
		final Entry garbage = pair.clientContext.post(clientTag(2), new byte[40]);

		assertThat(pair.exit.receive(close), equalTo(Optional.of(new Close(CloseReason.DONE))));
		assertThat(pair.exit.receive(next), equalTo(Optional.empty()));
		assertThat(pair.exit.receive(close), equalTo(Optional.empty()));
		assertThat(pair.exit.receive(garbage), equalTo(Optional.empty()));
		assertThat(pair.exit.isOpen(), is(false));
		assertThat(pair.fromExit, is(empty()));
	}

	@Example
	public void shouldConsumeExactlyOneCounterPerSend() {
		final Pair pair = new Pair();

		pair.client.send(new Data(new byte[] { 1 }));
		pair.client.send(new Datagram(TARGET, new byte[] { 2 }));
		pair.client.send(new Connect(Socks5CommandType.CONNECT, TARGET));
		pair.client.close(CloseReason.DONE);
		assertThat(counters(pair.fromClient), equalTo(List.of(0L, 1L, 2L, 3L)));
		assertThat(pair.toExit(), hasSize(4));
	}

	@Example
	public void shouldConsumeNoCounterForARefusedSend() {
		final Pair pair = new Pair();
		final int tooLarge = pair.client.maxDataBytes() + 1;

		pair.client.send(new Data(new byte[] { 1 }));
		Expect.assertThrows(IllegalArgumentException.class, () -> pair.client.send(new Data(new byte[tooLarge])));
		pair.client.send(new Data(new byte[] { 2 }));
		assertThat(counters(pair.fromClient), equalTo(List.of(0L, 1L)));
		assertThat(pair.toExit(), equalTo(List.of(new Data(new byte[] { 1 }), new Data(new byte[] { 2 }))));
	}

	@Example
	public void shouldFitTheLargestDatagramInOneEntry() {
		final Pair pair = new Pair();
		final int largest = pair.client.maxDataBytes() - TARGET_ADDRESS_BYTES;

		Expect.assertThrows(IllegalArgumentException.class,
			() -> pair.client.send(new Datagram(TARGET, new byte[largest + 1])));
		assertThat(pair.fromClient, is(empty()));
		pair.client.send(new Datagram(TARGET, new byte[largest]));
		assertThat(pair.fromClient.get(0).size(), is(16 * 1024));
		assertThat(pair.toExit(), equalTo(List.of(new Datagram(TARGET, new byte[largest]))));
	}

	@Example
	public void shouldOpenAMessageOfExactlyTheLimitAndBreakOnOneByteMore() {
		final Pair pair = new Pair();
		final int limit = pair.exitContext.maxPayloadBytes();
		final ClientSealer peer = new ClientSealer(pair);
		final Data atTheLimit = new Data(new byte[limit - SealedCodec.DATA_OVERHEAD]);
		final Data pastTheLimit = new Data(new byte[limit - SealedCodec.DATA_OVERHEAD + 1]);

		assertThat(pair.exit.receive(peer.post(SealedCodec.encode(atTheLimit))), equalTo(Optional.of(atTheLimit)));
		assertThat(pair.exit.receive(peer.post(SealedCodec.encode(pastTheLimit))), equalTo(Optional.of(BROKEN)));
		assertExitBrokeAndTold(pair);
	}

	@Example
	public void shouldBreakOnAHostileOversizedRecord() {
		final int mebibyte = 1024 * 1024;
		final Pair sealed = new Pair();
		final Pair unsealed = new Pair();
		final Entry keyed = new ClientSealer(sealed).post(SealedCodec.encode(new Data(new byte[mebibyte])));
		final Entry keyless = unsealed.clientContext.post(clientTag(0), new byte[mebibyte]);

		assertThat(sealed.exit.receive(keyed), equalTo(Optional.of(BROKEN)));
		assertExitBrokeAndTold(sealed);
		assertThat(unsealed.exit.receive(keyless), equalTo(Optional.of(BROKEN)));
		assertExitBrokeAndTold(unsealed);
	}

	/* A record that was opened already is behind the next tag, as is a copy of it that another writer posted:
	   either is passed over, and the next record still opens */
	@Example
	public void shouldPassOverARecordThatArrivesAgain() {
		for (int[] delivered : new int[][] { { 0, 0 }, { 0, 1, 0 }, { 0, 1, 1 }, { 0, 1, 1, 0 } }) {
			final Pair pair = new Pair();
			final List<Entry> entries = sent(pair, 3);

			assertThat(afterDelivering(pair, entries, delivered), equalTo(Optional.empty()));
			assertThat(pair.exit.isOpen(), is(true));
			assertThat(pair.fromExit, is(empty()));
			final int next = Arrays.stream(delivered).max().orElseThrow() + 1;

			assertThat(pair.exit.receive(entries.get(next)), equalTo(Optional.of(new Data(new byte[] { (byte) next }))));
		}
	}

	/* Every writer sees every entry, so a tag that another writer copies, the next one or one already opened, is
	   nothing the peer said and ends nothing */
	@Example
	public void ignoresATagThatAnotherWriterReplays() {
		final Pair pair = new Pair();
		final List<Entry> entries = sent(pair, 2);
		final SessionContext stranger = SessionFixtures.context(3, new ArrayList<>(), pair.clock::get);

		assertThat(pair.exit.receive(entries.get(0)), equalTo(Optional.of(new Data(new byte[] { 0 }))));

		final Entry past = stranger.post(Session.tagOf(entries.get(0)), entries.get(0).payload());
		final Entry next = stranger.post(Session.tagOf(entries.get(1)), entries.get(1).payload());

		for (Entry copy : List.of(past, next)) {
			assertThat(pair.exit.expects(copy), is(false));
			assertThat(pair.exit.recognises(copy), is(false));
			assertThat(pair.exit.receive(copy), equalTo(Optional.empty()));
		}

		assertThat(pair.exit.isOpen(), is(true));
		assertThat(pair.fromExit, is(empty()));
		assertThat(pair.exit.receive(entries.get(1)), equalTo(Optional.of(new Data(new byte[] { 1 }))));
	}

	/* An entry of another writer under the next tag, or one past it, that does not open breaks nothing either */
	@Example
	public void ignoresAForgedEntryOfAnotherWriterUnderATagOfTheSession() {
		final Pair pair = new Pair();
		final SessionContext stranger = SessionFixtures.context(3, new ArrayList<>(), pair.clock::get);

		for (long counter : new long[] { 0, 1 }) {
			assertThat(pair.exit.receive(stranger.post(clientTag(counter), new byte[SEALED_BYTES])),
				equalTo(Optional.empty()));
			assertThat(pair.client.receive(stranger.post(Tags.of(SECRETS.exitTags(), Direction.EXIT_TO_CLIENT,
				counter), new byte[SEALED_BYTES])), equalTo(Optional.empty()));
		}

		assertThat(pair.exit.isOpen(), is(true));
		assertThat(pair.client.isOpen(), is(true));
		assertThat(pair.fromExit, is(empty()));
		assertThat(pair.fromClient, is(empty()));
		pair.client.send(DATA_ONE);
		pair.exit.send(DATA_ONE);
		assertThat(pair.toExit(), equalTo(List.of(DATA_ONE)));
		assertThat(pair.toClient(), equalTo(List.of(DATA_ONE)));
	}

	@Example
	public void shouldBreakOnASkippedRecord() {
		for (int[] delivered : new int[][] { { 1 }, { 2 }, { 0, 2 }, { 0, 1, 3 } }) {
			final Pair pair = new Pair();

			assertThat(afterDelivering(pair, sent(pair, 4), delivered), equalTo(Optional.of(BROKEN)));
			assertExitBrokeAndTold(pair);
		}
	}

	@Example
	public void shouldTakeTheRecordsOfTheSessionInOrder() {
		final Pair pair = new Pair();

		assertThat(afterDelivering(pair, sent(pair, 3), 0, 1, 2), equalTo(Optional.of(new Data(new byte[] { 2 }))));
		assertThat(pair.exit.isOpen(), is(true));
		assertThat(pair.fromExit, is(empty()));
	}

	@Property(tries = 300)
	public void shouldBreakOnARecordTamperedWithAnywhere(
		@ForAll @IntRange(min = 0, max = SEALED_BYTES - 1) int index,
		@ForAll @IntRange(min = 0, max = Byte.SIZE - 1) int bit) {
		final Pair pair = new Pair();
		final byte[] sealed = SealingCipher.sealer(SECRETS.clientToExit(), Direction.CLIENT_TO_EXIT,
			SECRETS.binding()).seal(SealedCodec.encode(new Data(new byte[] { 1, 2, 3 })), SEALED_BYTES);

		sealed[index] ^= 1 << bit;
		assertThat(pair.exit.receive(pair.clientContext.post(clientTag(0), sealed)), equalTo(Optional.of(BROKEN)));
		assertExitBrokeAndTold(pair);
	}

	@Example
	public void shouldBreakOnARecordOfTheWrongLength() {
		final List<UnaryOperator<byte[]>> changes = List.of(sealed -> Arrays.copyOf(sealed, sealed.length - 1),
			sealed -> Arrays.copyOf(sealed, sealed.length + 1),
			sealed -> Arrays.copyOf(sealed, SealingCipher.OVERHEAD - 1), sealed -> new byte[0]);

		for (UnaryOperator<byte[]> change : changes) {
			final Pair pair = new Pair();
			final byte[] sealed = SealingCipher.sealer(SECRETS.clientToExit(), Direction.CLIENT_TO_EXIT,
				SECRETS.binding()).seal(SealedCodec.encode(new Data(new byte[] { 1, 2, 3 })), SEALED_BYTES);
			final Entry record = pair.clientContext.post(clientTag(0), change.apply(sealed));

			assertThat(pair.exit.receive(record), equalTo(Optional.of(BROKEN)));
			assertExitBrokeAndTold(pair);
		}
	}

	@Example
	public void shouldBreakOnARecordSealedUnderAnotherKeyOrHandshake() {
		assertThat(exitReceivesSealedWith(SECRETS.clientToExit(), Direction.CLIENT_TO_EXIT, SECRETS.binding()),
			equalTo(Optional.of(new Data(new byte[] { 1 }))));
		assertThat(exitReceivesSealedWith(SECRETS.exitToClient(), Direction.CLIENT_TO_EXIT, SECRETS.binding()),
			equalTo(Optional.of(BROKEN)));
		assertThat(exitReceivesSealedWith(SECRETS.clientToExit(), Direction.CLIENT_TO_EXIT,
			SessionFixtures.filled(32, 8)), equalTo(Optional.of(BROKEN)));
	}

	@Example
	public void shouldBreakOnARecordSealedInTheOtherDirection() {
		assertThat(exitReceivesSealedWith(SECRETS.clientToExit(), Direction.EXIT_TO_CLIENT, SECRETS.binding()),
			equalTo(Optional.of(BROKEN)));
	}

	@Example
	public void shouldLeaveNoTraceOfTheEntriesItIgnores() {
		final Pair pair = new Pair();
		final byte[] sealed = SealingCipher.sealer(SECRETS.clientToExit(), Direction.CLIENT_TO_EXIT,
			SECRETS.binding()).seal(SealedCodec.encode(new Data(new byte[] { 1 })), SEALED_BYTES);
		final Entry otherTag = pair.clientContext.post(TAG, sealed);
		final Entry pastTheWindow = pair.clientContext.post(clientTag(Session.WINDOW), sealed);
		final Entry ownDirection = pair.clientContext.post(Tags.of(SECRETS.exitTags(), Direction.EXIT_TO_CLIENT, 0),
			sealed);
		final Entry genuine = pair.clientContext.post(clientTag(0), sealed);

		assertThat(pair.exit.receive(otherTag), equalTo(Optional.empty()));
		assertThat(pair.exit.receive(pastTheWindow), equalTo(Optional.empty()));
		assertThat(pair.exit.receive(ownDirection), equalTo(Optional.empty()));
		assertThat(pair.exit.receive(genuine), equalTo(Optional.of(new Data(new byte[] { 1 }))));
		assertThat(pair.exit.isOpen(), is(true));
		assertThat(pair.fromExit, is(empty()));
	}

	@Example
	public void shouldBreakWhenTheCipherFailsUnexpectedly() throws ReflectiveOperationException {
		final Pair pair = new Pair();
		final Session exit = exitFailingToOpen(pair, pair.fromExit::add);
		final Entry record = new ClientSealer(pair).post(SealedCodec.encode(DATA_ONE));

		assertThat(exit.receive(record), equalTo(Optional.of(BROKEN)));
		assertThat(exit.isOpen(), is(false));
		assertThat(exit.receive(record), equalTo(Optional.empty()));
		assertThat(pair.fromExit, hasSize(1));
		assertThat(pair.toClient(), equalTo(List.of(BROKEN)));
		assertThat(pair.client.isOpen(), is(false));
	}

	@Example
	public void shouldTellThePeerNothingButTheReasonWhenTheCipherFailsUnexpectedly() throws Exception {
		final Pair pair = new Pair();
		final Session exit = exitFailingToOpen(pair, pair.fromExit::add);

		exit.receive(new ClientSealer(pair).post(SealedCodec.encode(DATA_ONE)));
		assertThat(pair.fromExit, hasSize(1));
		assertThat(pair.fromExit.get(0).size(), is(SMALLEST_CLASS));
		assertThat(SealingCipher.opener(SECRETS.exitToClient(), Direction.EXIT_TO_CLIENT, SECRETS.binding())
			.open(pair.fromExit.get(0).payload()), equalTo(SealedCodec.encode(BROKEN)));
	}

	@Example
	public void shouldCloseWhenTheCipherFailsUnexpectedlyAndTheOutboxRefusesTheClose()
		throws ReflectiveOperationException {
		final Pair pair = new Pair();
		final FlakyOutbox outbox = new FlakyOutbox();
		final Session exit = exitFailingToOpen(pair, outbox);
		final Entry record = new ClientSealer(pair).post(SealedCodec.encode(DATA_ONE));

		outbox.refusing = true;
		assertThat(Expect.assertThrows(IllegalStateException.class, () -> exit.receive(record)).getMessage(),
			is(OUTBOX_FULL));
		assertThat(exit.isOpen(), is(false));
		assertThat(outbox.taken, hasSize(1));
	}

	@Example
	public void shouldNotCatchAnErrorWhileOpening() {
		final Pair pair = new Pair();
		final AtomicBoolean failing = new AtomicBoolean();
		final Session exit = exitOnClock(pair, () -> {
			if (failing.get()) {
				throw new AssertionError("The clock of an unreliable host");
			}

			return SessionFixtures.NOW;
		});
		final Entry record = new ClientSealer(pair).post(SealedCodec.encode(DATA_ONE));

		failing.set(true);
		Expect.assertThrows(AssertionError.class, () -> exit.receive(record));
		assertThat(exit.isOpen(), is(true));
		assertThat(pair.fromExit, is(empty()));
	}

	@Example
	public void shouldBreakOnAnyRuntimeExceptionWhileOpening() {
		final Pair pair = new Pair();
		final AtomicBoolean failing = new AtomicBoolean();
		final Session exit = exitOnClock(pair, () -> {
			if (failing.getAndSet(false)) {
				throw new UnsupportedOperationException("The clock of an unreliable host");
			}

			return SessionFixtures.NOW;
		});
		final Entry record = new ClientSealer(pair).post(SealedCodec.encode(DATA_ONE));

		failing.set(true);
		assertThat(exit.receive(record), equalTo(Optional.of(BROKEN)));
		assertThat(exit.isOpen(), is(false));
		assertThat(pair.toClient(), equalTo(List.of(BROKEN)));
	}

	@Example
	public void shouldConsumeOneCounterWhenThePostThrowsAndNeverReuseOne() {
		final Pair pair = new Pair();
		final FlakyOutbox outbox = new FlakyOutbox();
		final Session client = over(pair, pair.clientContext, pair.exitContext, Direction.CLIENT_TO_EXIT, outbox);

		outbox.refusing = true;
		assertThat(Expect.assertThrows(IllegalStateException.class, () -> client.send(new Data(new byte[] { 1 })))
			.getMessage(), is(OUTBOX_FULL));
		outbox.refusing = false;
		client.send(new Data(new byte[] { 2 }));
		assertThat(client.isOpen(), is(true));
		assertThat(counters(outbox.taken), equalTo(List.of(0L, 1L)));
		assertThat(outbox.taken.stream().map(entry -> entry.header().seq()).collect(Collectors.toList()),
			equalTo(List.of(0L, 1L)));
		assertThat(Pair.deliver(new ArrayList<>(outbox.taken), pair.exit), equalTo(List.of(
			new Data(new byte[] { 1 }), new Data(new byte[] { 2 }))));
	}

	@Example
	public void shouldBreakOnTheCounterAThrowingPostLeftOut() {
		final Pair pair = new Pair();
		final FlakyOutbox outbox = new FlakyOutbox();
		final Session client = over(pair, pair.clientContext, pair.exitContext, Direction.CLIENT_TO_EXIT, outbox);

		outbox.refusing = true;
		Expect.assertThrows(IllegalStateException.class, () -> client.send(new Data(new byte[] { 1 })));
		outbox.refusing = false;
		client.send(new Data(new byte[] { 2 }));
		assertThat(pair.exit.receive(outbox.taken.get(1)), equalTo(Optional.of(BROKEN)));
		assertThat(pair.exit.isOpen(), is(false));
	}

	@Example
	public void shouldCloseEvenWhenTheOutboxRefusesTheClose() {
		final Pair pair = new Pair();
		final FlakyOutbox outbox = new FlakyOutbox();
		final Session client = over(pair, pair.clientContext, pair.exitContext, Direction.CLIENT_TO_EXIT, outbox);

		outbox.refusing = true;
		Expect.assertThrows(IllegalStateException.class, () -> client.close(CloseReason.DONE));
		assertThat(client.isOpen(), is(false));
		assertThat(client.close(CloseReason.DONE), equalTo(Optional.empty()));
		Expect.assertThrows(IllegalStateException.class, () -> client.send(new Data(new byte[] { 1 })));
		assertThat(outbox.taken, hasSize(1));
	}

	@Example
	public void shouldCloseEvenWhenTheOutboxRefusesTheBroken() {
		final Pair pair = new Pair();
		final FlakyOutbox outbox = new FlakyOutbox();
		final Session exit = over(pair, pair.exitContext, pair.clientContext, Direction.EXIT_TO_CLIENT, outbox);
		final Entry forged = pair.clientContext.post(clientTag(0), new byte[40]);
		final Entry genuine = new ClientSealer(pair).post(SealedCodec.encode(DATA_ONE));

		outbox.refusing = true;
		Expect.assertThrows(IllegalStateException.class, () -> exit.receive(forged));
		assertThat(exit.isOpen(), is(false));
		assertThat(exit.receive(genuine), equalTo(Optional.empty()));
		assertThat(outbox.taken, hasSize(1));
	}

	@Example
	public void shouldPostInTheOrderOfSealingWhenSendersRace() throws Exception {
		final Pair pair = new Pair();
		final ExecutorService pool = Executors.newFixedThreadPool(THREADS);
		final CyclicBarrier start = new CyclicBarrier(THREADS);

		try {
			final List<Future<Object>> sends = IntStream.range(0, THREADS).mapToObj(thread -> pool.submit(() -> {
				start.await();
				IntStream.range(0, SENDS).forEach(i -> pair.client.send(new Data(new byte[] { (byte) i })));
				return null;
			})).collect(Collectors.toList());

			for (Future<Object> send : sends) {
				send.get();
			}
		} finally {
			pool.shutdownNow();
		}

		assertThat(counters(pair.fromClient), equalTo(LongStream.range(0, THREADS * SENDS).boxed()
			.collect(Collectors.toList())));
		assertThat(pair.toExit(), hasSize(THREADS * SENDS));
	}

	@Example
	public void shouldHoldTheSessionWhileASenderWaitsForTheWriter() throws Exception {
		final BiConsumer<Pair, Session> send = (pair, client) -> client.send(new Data(new byte[] { 2 }));
		final BiConsumer<Pair, Session> receive = (pair, client) -> client.receive(pair.fromExit.get(0));
		final BiConsumer<Pair, Session> close = (pair, client) -> client.close(CloseReason.IDLE);
		final BiConsumer<Pair, Session> isOpen = (pair, client) -> client.isOpen();

		assertHeldWhileWaitingForWriter((pair, client) -> client.send(new Data(new byte[] { 1 })),
			List.of(send, receive, close, isOpen));
		assertHeldWhileWaitingForWriter((pair, client) -> client.close(CloseReason.DONE),
			List.of(receive, close, isOpen));
	}

	@Example
	public void shouldHandTheOutboxTheFeedInSequenceOrder() throws Exception {
		final List<Long> seen = Collections.synchronizedList(new ArrayList<>());
		final CountDownLatch inOutbox = new CountDownLatch(1);
		final CountDownLatch release = new CountDownLatch(1);
		final AtomicBoolean first = new AtomicBoolean(true);
		final SessionContext context = new SessionContext(new FeedWriter(SessionFixtures.key(1),
			SessionFixtures.CHAIN), entry -> {
			seen.add(entry.header().seq());

			if (first.getAndSet(false)) {
				inOutbox.countDown();
				awaitUninterruptibly(release);
			}
		}, () -> SessionFixtures.NOW, new SecureRandom(), ProxySettings.DEFAULTS);
		final Background holder = new Background(() -> context.post(TAG, new byte[1]));

		assertThat(inOutbox.await(WAIT_SECONDS, TimeUnit.SECONDS), is(true));

		final Background waiter = new Background(() -> context.post(TAG, new byte[2]));

		waiter.awaitBlocked();
		assertThat(seen, equalTo(List.of(0L)));
		release.countDown();
		holder.finish();
		waiter.finish();
		assertThat(seen, equalTo(List.of(0L, 1L)));
	}

	@Example
	public void shouldPostTheEntryItWritesToTheOutbox() {
		final List<Entry> taken = new ArrayList<>();
		final SessionContext context = new SessionContext(new FeedWriter(SessionFixtures.key(1),
			SessionFixtures.CHAIN), taken::add, () -> SessionFixtures.NOW, new SecureRandom(),
			ProxySettings.DEFAULTS);
		final Entry posted = context.post(TAG, new byte[] { 1 });

		assertThat(taken, equalTo(List.of(posted)));
		assertThat(posted.header().timestamp(), is(SessionFixtures.NOW));
		assertThat(posted.header().kind(), is(EntryKind.SEALED));
		assertThat(Session.tagOf(posted), equalTo(TAG));
		assertThat(posted.payload(), equalTo(new byte[] { 1 }));
		assertThat(posted.isAuthentic(), is(true));
	}

	@Example
	public void shouldReadTheClockAndTheLimitsOfTheSpork() {
		final SessionContext context = SessionFixtures.context(1, new ArrayList<>(), () -> SessionFixtures.NOW + 7);

		assertThat(context.now(), is(SessionFixtures.NOW + 7));
		assertThat(context.maxPayloadBytes(), is(16 * 1024 - Entry.OVERHEAD));
		assertThat(context.smallestPayloadBytes(), is(SMALLEST_CLASS - Entry.OVERHEAD));
	}

	/* The next tag of the peer opens; the ones after it, up to the window, mean records were lost */
	@Example
	public void shouldExpectTheNextTagAndRecogniseTheWindowAfterIt() {
		final Pair pair = new Pair();
		final List<Entry> entries = sent(pair, 3);
		final Entry farAhead = pair.clientContext.post(clientTag(Session.WINDOW), new byte[1]);

		assertThat(pair.exit.expects(entries.get(0)), is(true));
		assertThat(pair.exit.expects(entries.get(1)), is(false));
		assertThat(pair.exit.recognises(entries.get(1)), is(true));
		assertThat(pair.exit.recognises(farAhead), is(false));
		pair.exit.receive(entries.get(0));
		assertThat(pair.exit.expects(entries.get(0)), is(false));
		assertThat(pair.exit.recognises(entries.get(0)), is(false));
		assertThat(pair.exit.expects(entries.get(1)), is(true));
		assertThat(pair.exit.recognises(farAhead), is(true));
		pair.exit.close(CloseReason.DONE);
		assertThat(pair.exit.expects(entries.get(1)), is(false));
		assertThat(pair.exit.recognises(entries.get(1)), is(false));
	}

	/* A gap names the session when it skipped an entry of the peer that carried one of the tags the session looks
	   out for */
	@Example
	public void shouldFindItselfInAGapOnlyByTheTagsItLooksOutFor() {
		final Pair pair = new Pair();
		final WriterId client = pair.clientContext.writer().writer();

		pair.exit.receive(sent(pair, 1).get(0));
		assertThat(pair.exit.isNamedIn(gapOf(client, clientTag(1))), is(true));
		assertThat(pair.exit.isNamedIn(gapOf(client, clientTag(Session.WINDOW))), is(true));
		assertThat(pair.exit.isNamedIn(gapOf(client, clientTag(0))), is(false));
		assertThat(pair.exit.isNamedIn(gapOf(client, clientTag(Session.WINDOW + 1))), is(false));
		assertThat(pair.exit.isNamedIn(gapOf(client, TAG)), is(false));
		assertThat(pair.exit.isNamedIn(gapOf(pair.exitContext.writer().writer(), clientTag(1))), is(false));
		assertThat(pair.exit.isNamedIn(gapOf(SessionFixtures.key(3).id(), clientTag(1))), is(false));
		pair.exit.close(CloseReason.DONE);
		assertThat(pair.exit.isNamedIn(gapOf(client, clientTag(1))), is(false));
	}

	private static Gap gapOf(WriterId writer, Tag tag) {
		return new Gap(writer, 0, 0, Set.of(new Fingerprint(tag.bytes())));
	}

	@Example
	public void shouldRefuseAContextWithAPartMissing() {
		final FeedWriter writer = new FeedWriter(SessionFixtures.key(1), SessionFixtures.CHAIN);
		final Consumer<Entry> outbox = entry -> { };
		final SecureRandom random = new SecureRandom();

		assertThat(Expect.assertThrows(NullPointerException.class, () -> new SessionContext(null, outbox, () -> 0,
			random, ProxySettings.DEFAULTS)).getMessage(), is("writer"));
		assertThat(Expect.assertThrows(NullPointerException.class, () -> new SessionContext(writer, null, () -> 0,
			random, ProxySettings.DEFAULTS)).getMessage(), is("outbox"));
		assertThat(Expect.assertThrows(NullPointerException.class, () -> new SessionContext(writer, outbox, null,
			random, ProxySettings.DEFAULTS)).getMessage(), is("clock"));
		assertThat(Expect.assertThrows(NullPointerException.class, () -> new SessionContext(writer, outbox, () -> 0,
			null, ProxySettings.DEFAULTS)).getMessage(), is("random"));
		assertThat(Expect.assertThrows(NullPointerException.class, () -> new SessionContext(writer, outbox, () -> 0,
			random, null)).getMessage(), is("settings"));
	}
}

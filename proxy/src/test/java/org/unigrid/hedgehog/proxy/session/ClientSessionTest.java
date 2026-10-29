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
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters;
import org.unigrid.hedgehog.proxy.Expect;
import org.unigrid.hedgehog.proxy.Padding;
import org.unigrid.hedgehog.proxy.chain.ChainId;
import org.unigrid.hedgehog.proxy.chain.Entry;
import org.unigrid.hedgehog.proxy.chain.FeedWriter;
import org.unigrid.hedgehog.proxy.chain.Tag;
import org.unigrid.hedgehog.proxy.group.ProxySettings;
import org.unigrid.hedgehog.proxy.session.NoiseNk.Responder;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

public class ClientSessionTest {
	private static final String EXIT_ID = "gn-exit";
	private static final ExitSelector ONLY_EXIT = excluded -> excluded.contains(EXIT_ID) ? Optional.empty()
		: Optional.of(SessionFixtures.exit(EXIT_ID));
	private static final Connect CONNECT = new Connect(Socks5CommandType.CONNECT, new SocksAddress(
		Socks5AddressType.DOMAIN, "example.org", 443));
	private static final Connected CONNECTED = new Connected(Socks5CommandStatus.SUCCESS, new SocksAddress(
		Socks5AddressType.IPv4, "192.0.2.10", 40_000));
	private static final ChainId OTHER_CHAIN = new ChainId(SessionFixtures.filled(ChainId.SIZE, 8));
	private static final int SMALLEST_CLASS = 1024;

	/* Points of low order: the secret agreed with any of them is all zeros */
	private static final List<String> LOW_ORDER = List.of(
		"0000000000000000000000000000000000000000000000000000000000000000",
		"0100000000000000000000000000000000000000000000000000000000000000",
		"e0eb7a7c3b41b8ae1656e3faf19fc46ada098deb9c32b1fd866205165f49b800",
		"5f9c95bca3508c24b1d0b1559c83ef5b04445cc4581c8e86d8224eddd09f1157");

	/* Draws that depend only on how many bytes are asked for, so that the test can build the client's private key.
	   The step between bytes is the length too, so that a long draw for padding never holds a run of a key's. */
	private static final class KnownRandom extends SecureRandom {
		@Override
		public void nextBytes(byte[] bytes) {
			for (int i = 0; i < bytes.length; i++) {
				bytes[i] = (byte) (bytes.length * 3 + i * bytes.length);
			}
		}
	}

	@Provide
	public Arbitrary<List<byte[]>> chunks() {
		return Arbitraries.bytes().array(byte[].class).ofMinSize(1).ofMaxSize(2000).list().ofMaxSize(10);
	}

	/* The exit's side of an opening, as the holder of the exit's sealing key opens it */
	private static Responder responderTo(Entry opening, String exitId) {
		return NoiseNk.respond(SessionFixtures.sealingPrivate(exitId), Session.prologue(opening.header().chainId(),
			opening.header().writer()), Arrays.copyOf(opening.payload(), NoiseNk.MESSAGE_SIZE)).orElseThrow();
	}

	private static Tag answerTagOf(Responder responder) {
		return Tags.of(responder.answerTagKey(), Direction.EXIT_TO_CLIENT, 0);
	}

	/* The exit's answer to an opening, taken off the wire before anyone else sees it */
	private static Entry answerTo(SessionWire wire, Entry opening) {
		Session.tryAccept(wire.exitContext, opening, SessionFixtures.sealingPrivate(EXIT_ID)).orElseThrow();
		return wire.posted.pollLast();
	}

	/* The client as writer 1 of the chain, with an outbox and randomness of the test's choosing */
	static ClientSession clientOf(SessionWire wire, Consumer<Entry> outbox, SecureRandom random,
		ExitSelector selector) {

		return new ClientSession(new SessionContext(new FeedWriter(SessionFixtures.key(1), SessionFixtures.CHAIN),
			outbox, wire.clock::get, random, ProxySettings.DEFAULTS), selector, CONNECT);
	}

	private static byte[] knownPrivateKey() {
		return new X25519PrivateKeyParameters(new KnownRandom()).getEncoded();
	}

	/* Whether the bytes are held by an object that the root reaches through the fields of this project's classes
	   and of BouncyCastle's */
	static boolean holds(Object root, byte[] secret) throws IllegalAccessException {
		final Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		final Deque<Object> pending = new ArrayDeque<>(List.of(root));

		while (!pending.isEmpty()) {
			final Object object = pending.poll();

			if (object instanceof byte[] bytes && Arrays.equals(bytes, secret)) {
				return true;
			}

			if (seen.add(object)) {
				pending.addAll(referencedBy(object));
			}
		}

		return false;
	}

	private static boolean isWalked(Class<?> type) {
		return !type.isHidden() && (type.getName().startsWith("org.unigrid.")
			|| type.getName().startsWith("org.bouncycastle."));
	}

	private static List<Object> referencedBy(Object object) throws IllegalAccessException {
		final List<Object> referenced = new ArrayList<>();

		for (Class<?> type = object.getClass(); isWalked(type); type = type.getSuperclass()) {
			for (Field field : type.getDeclaredFields()) {
				if (!Modifier.isStatic(field.getModifiers()) && !field.getType().isPrimitive()) {
					field.setAccessible(true);
					Optional.ofNullable(field.get(object)).ifPresent(referenced::add);
				}
			}
		}

		return referenced;
	}

	private static boolean contains(byte[] bytes, byte[] part) {
		return IntStream.rangeClosed(0, bytes.length - part.length)
			.anyMatch(from -> Arrays.equals(bytes, from, from + part.length, part, 0, part.length));
	}

	private static void assertIgnored(String why, ClientSession client, Entry entry) {
		assertThat(why, client.receive(entry), equalTo(Optional.empty()));
		assertThat(why, client.state(), is(SessionState.OPENING));
	}

	@Property(tries = 20)
	public void shouldRelayAStreamThroughTwoReplicasOfTheChain(@ForAll("chunks") List<byte[]> up,
		@ForAll("chunks") List<byte[]> down) {
		final SessionWire wire = new SessionWire(EXIT_ID);
		final ClientSession client = wire.client(ONLY_EXIT, CONNECT);

		assertThat(client.open(), is(true));
		wire.pump();
		assertThat(client.state(), is(SessionState.OPEN));
		assertThat(wire.atExit, equalTo(List.of(CONNECT)));

		wire.exitSession().send(CONNECTED);
		up.forEach(bytes -> client.send(new Data(bytes)));
		down.forEach(bytes -> wire.exitSession().send(new Data(bytes)));
		wire.pump();
		assertThat(wire.atExit.subList(1, wire.atExit.size()), equalTo(up.stream().map(Data::new).toList()));
		assertThat(wire.atClient.get(0), equalTo(CONNECTED));
		assertThat(wire.atClient.subList(1, wire.atClient.size()), equalTo(down.stream().map(Data::new).toList()));
		assertThat(wire.clientChain.entries(), equalTo(wire.exitChain.entries()));
	}

	@Example
	public void shouldOpenTowardsTheChosenExit() {
		final SessionWire wire = new SessionWire(EXIT_ID);
		final ClientSession client = wire.client(ONLY_EXIT, CONNECT);

		assertThat(client.state(), is(SessionState.NEW));
		client.open();

		final Entry opening = wire.posted.peek();

		assertThat(client.state(), is(SessionState.OPENING));
		assertThat(client.exit(), equalTo(Optional.of(EXIT_ID)));
		assertThat(opening.size(), is(SMALLEST_CLASS));
		assertThat(NoiseNk.respond(SessionFixtures.sealingPrivate(EXIT_ID), Session.prologue(SessionFixtures.CHAIN,
			wire.clientContext.writer().writer()), Arrays.copyOf(opening.payload(), NoiseNk.MESSAGE_SIZE)).isPresent(),
			is(true));
		Expect.assertThrows(IllegalStateException.class, client::open);
		Expect.assertThrows(IllegalStateException.class, () -> client.send(new Data(new byte[] { 1 })));
	}

	/* What the chain sees of an opening tells nobody which member it is for */
	@Example
	public void openingNamesNoExit() {
		final SessionWire wire = new SessionWire(EXIT_ID);
		final ClientSession client = wire.client(ONLY_EXIT, CONNECT);

		client.open();

		final Entry opening = wire.posted.peek();
		final byte[] bytes = opening.payload();

		assertThat(contains(bytes, EXIT_ID.getBytes(StandardCharsets.UTF_8)), is(false));
		assertThat(contains(bytes, SessionFixtures.sealingPublic(EXIT_ID)), is(false));
		assertThat(contains(opening.header().tag().bytes(), EXIT_ID.getBytes(StandardCharsets.UTF_8)),
			is(false));
		assertThat(opening.size(), is(SMALLEST_CLASS));
		assertThat(NoiseNk.respond(SessionFixtures.sealingPrivate("gn-other"), Session.prologue(SessionFixtures.CHAIN,
			wire.clientContext.writer().writer()), Arrays.copyOf(bytes, NoiseNk.MESSAGE_SIZE)).isPresent(), is(false));
	}

	@Example
	public void shouldCloseWhenTheGroupOffersNoExit() {
		final SessionWire wire = new SessionWire(EXIT_ID);
		final ClientSession client = wire.client(excluded -> Optional.empty(), CONNECT);

		assertThat(client.open(), is(false));
		assertThat(client.state(), is(SessionState.CLOSED));
		assertThat(wire.posted.isEmpty(), is(true));
	}

	/* An answer under the right tag that another key made, of another handshake, completes nothing */
	@Example
	public void anAnswerFromAnotherKeyIsIgnored() throws HandshakeException {
		final SessionWire wire = new SessionWire(EXIT_ID);
		final ClientSession client = wire.client(ONLY_EXIT, CONNECT);

		client.open();

		final Entry opening = wire.posted.poll();
		final Tag answerTag = answerTagOf(responderTo(opening, EXIT_ID));
		final byte[] prologue = Session.prologue(SessionFixtures.CHAIN, wire.clientContext.writer().writer());
		final NoiseNk.Initiation elsewhere = NoiseNk.initiate(SessionFixtures.sealingPublic("gn-other"), prologue,
			new SecureRandom());
		final Responder other = NoiseNk.respond(SessionFixtures.sealingPrivate("gn-other"), prologue,
			elsewhere.message()).orElseThrow();
		final SessionContext impostor = wire.context(3);
		final byte[] otherAnswer = impostor.padded(other.answer(new SecureRandom()).message());

		assertIgnored("under the answer tag", client, impostor.post(answerTag, otherAnswer));
		assertIgnored("under its own tag", client, impostor.post(answerTagOf(other), otherAnswer));
		client.receive(answerTo(wire, opening));
		assertThat(client.state(), is(SessionState.OPEN));
	}

	@Example
	public void shouldWaitPastAnAnswerThatDoesNotCompleteTheHandshake() {
		final SessionWire wire = new SessionWire(EXIT_ID);
		final ClientSession client = wire.client(ONLY_EXIT, CONNECT);

		client.open();

		final Tag answerTag = answerTagOf(responderTo(wire.posted.peek(), EXIT_ID));
		final SessionContext impostor = wire.context(3);
		final Entry forged = impostor.post(answerTag, Padding.padded(new byte[0],
			impostor.settings().chainLimits().maxEntryBytes()));

		assertThat(client.expects(forged), is(true));
		assertThat(client.receive(forged), equalTo(Optional.empty()));
		assertThat(client.state(), is(SessionState.OPENING));
		wire.pump();
		assertThat(client.state(), is(SessionState.OPEN));
		assertThat(wire.exitSession().peer(), equalTo(wire.clientContext.writer().writer()));
		assertThat(wire.atExit, equalTo(List.of(CONNECT)));
	}

	/* The writer whose answer completed the handshake is the exit's feed: a copy of the answer under another writer
	   comes too late, and what that writer posts under the session's tags, copied or forged, ends nothing */
	@Example
	public void shouldHearOnlyTheWriterWhoseAnswerCompletedTheHandshake() {
		final SessionWire wire = new SessionWire(EXIT_ID);
		final ClientSession client = wire.client(ONLY_EXIT, CONNECT);

		client.open();

		final Session exit = Session.tryAccept(wire.exitContext, wire.posted.poll(),
			SessionFixtures.sealingPrivate(EXIT_ID)).orElseThrow();
		final Entry answer = wire.posted.pollLast();
		final SessionContext copier = wire.context(3);

		assertThat(client.receive(answer), equalTo(Optional.empty()));
		assertThat(exit.receive(wire.posted.poll()), equalTo(Optional.of(CONNECT)));
		assertThat(client.receive(copier.post(answer.header().tag(), answer.payload())), equalTo(Optional.empty()));
		wire.posted.clear();
		exit.send(new Data(new byte[] { 1 }));

		final Entry first = wire.posted.poll();

		assertThat(client.receive(first), equalTo(Optional.of(new Data(new byte[] { 1 }))));
		exit.send(new Data(new byte[] { 2 }));

		final Entry second = wire.posted.poll();
		final List<Entry> copies = List.of(copier.post(first.header().tag(), first.payload()),
			copier.post(second.header().tag(), second.payload()),
			copier.post(second.header().tag(), Padding.padded(new byte[0], copier.settings().chainLimits()
				.maxEntryBytes())));

		wire.posted.clear();

		for (Entry copy : copies) {
			assertThat(client.expects(copy), is(false));
			assertThat(client.receive(copy), equalTo(Optional.empty()));
		}

		assertThat(client.state(), is(SessionState.OPEN));
		assertThat(wire.posted.isEmpty(), is(true));
		assertThat(client.receive(second), equalTo(Optional.of(new Data(new byte[] { 2 }))));
	}

	/* The answer names the exit's writer under the handshake, so a copy of it that another writer posts, and that
	   reaches the client first, completes nothing: the client waits for the exit's own */
	@Example
	public void anAnswerCopiedOntoAnotherWriterIsIgnored() {
		final SessionWire wire = new SessionWire(EXIT_ID);
		final ClientSession client = wire.client(ONLY_EXIT, CONNECT);

		client.open();

		final Session exit = Session.tryAccept(wire.exitContext, wire.posted.poll(),
			SessionFixtures.sealingPrivate(EXIT_ID)).orElseThrow();
		final Entry answer = wire.posted.pollLast();
		final Entry copy = wire.context(3).post(answer.header().tag(), answer.payload());

		wire.posted.clear();
		assertThat(client.expects(copy), is(true));
		assertThat(client.receive(copy), equalTo(Optional.empty()));
		assertThat(client.state(), is(SessionState.OPENING));
		assertThat(wire.posted.isEmpty(), is(true));
		assertThat(client.receive(answer), equalTo(Optional.empty()));
		assertThat(client.state(), is(SessionState.OPEN));
		assertThat(exit.receive(wire.posted.poll()), equalTo(Optional.of(CONNECT)));
		exit.send(new Data(new byte[] { 1 }));
		assertThat(client.receive(wire.posted.poll()), equalTo(Optional.of(new Data(new byte[] { 1 }))));
	}

	/* An answer of the exit's key whose writer is another than the one it names completes nothing */
	@Example
	public void anAnswerNamingAnotherWriterIsIgnored() throws HandshakeException {
		final SessionWire wire = new SessionWire(EXIT_ID);
		final ClientSession client = wire.client(ONLY_EXIT, CONNECT);

		client.open();

		final Entry opening = wire.posted.poll();
		final Responder responder = responderTo(opening, EXIT_ID);
		final byte[] named = wire.context(4).writer().writer().bytes();
		final Entry misnamed = wire.exitContext.post(answerTagOf(responder), wire.exitContext.padded(
			responder.answer(new SecureRandom(), named).message()));

		assertThat(client.receive(misnamed), equalTo(Optional.empty()));
		assertThat(client.state(), is(SessionState.OPENING));
		client.receive(answerTo(wire, opening));
		assertThat(client.state(), is(SessionState.OPEN));
	}

	/* The prologue binds the writer of the opening, so its bytes under another writer open for no exit */
	@Example
	public void shouldLeaveAnOpeningAnotherWriterPostedAgainUnanswered() {
		final SessionWire wire = new SessionWire(EXIT_ID);
		final ClientSession client = wire.client(ONLY_EXIT, CONNECT);

		client.open();

		final Entry opening = wire.posted.poll();
		final Entry postedAgain = wire.context(3).post(opening.header().tag(), opening.payload());

		assertThat(Session.tryAccept(wire.exitContext, postedAgain, SessionFixtures.sealingPrivate(EXIT_ID)),
			equalTo(Optional.empty()));
		assertThat(wire.posted.size(), is(1));
		client.receive(answerTo(wire, opening));
		assertThat(client.state(), is(SessionState.OPEN));
	}

	@Example
	public void shouldIgnoreAnAnswerOnAnotherChain() {
		final SessionWire wire = new SessionWire(EXIT_ID);
		final ClientSession client = wire.client(ONLY_EXIT, CONNECT);

		client.open();

		final Entry opening = wire.posted.poll();
		final Entry answer = answerTo(wire, opening);
		final SessionContext elsewhere = new SessionContext(new FeedWriter(SessionFixtures.key(2), OTHER_CHAIN),
			wire.posted::add, wire.clock::get, new SecureRandom(), ProxySettings.DEFAULTS);

		final Entry onAnotherChain = elsewhere.post(answer.header().tag(), answer.payload());

		assertThat(client.expects(onAnotherChain), is(false));
		assertIgnored("other chain", client, onAnotherChain);
		client.receive(answer);
		assertThat(client.state(), is(SessionState.OPEN));
	}

	/* The exit's key answers an opening once per answer it makes; the first answer to arrive makes the session */
	@Example
	public void shouldStayWithTheFirstAnswerThatCompletesTheHandshake() {
		final SessionWire wire = new SessionWire(EXIT_ID);
		final ClientSession client = wire.client(ONLY_EXIT, CONNECT);

		client.open();

		final Entry opening = wire.posted.poll();
		final Session first = Session.tryAccept(wire.exitContext, opening, SessionFixtures.sealingPrivate(EXIT_ID))
			.orElseThrow();
		final Entry firstAnswer = wire.posted.pollLast();
		final Session second = Session.tryAccept(wire.context(3), opening, SessionFixtures.sealingPrivate(EXIT_ID))
			.orElseThrow();
		final Entry secondAnswer = wire.posted.pollLast();

		assertThat(client.receive(firstAnswer), equalTo(Optional.empty()));
		assertThat(wire.posted.size(), is(1));
		assertThat(first.receive(wire.posted.peek()), equalTo(Optional.of(CONNECT)));
		assertThat(client.receive(secondAnswer), equalTo(Optional.empty()));
		assertThat(wire.posted.size(), is(1));
		assertThat(client.state(), is(SessionState.OPEN));
		assertThat(client.exit(), equalTo(Optional.of(EXIT_ID)));
		second.send(new Data(new byte[] { 2 }));
		assertThat(client.receive(wire.posted.pollLast()), equalTo(Optional.empty()));
		first.send(new Data(new byte[] { 1 }));
		assertThat(client.receive(wire.posted.pollLast()), equalTo(Optional.of(new Data(new byte[] { 1 }))));
	}

	@Example
	public void shouldIgnoreAnAnswerWithAnyBitOfItsHandshakeChanged() {
		final SessionWire wire = new SessionWire(EXIT_ID);
		final ClientSession client = wire.client(ONLY_EXIT, CONNECT);

		client.open();

		final Entry answer = answerTo(wire, wire.posted.poll());
		final byte[] payload = answer.payload();

		for (int bit = 0; bit < Session.ANSWER_SIZE * Byte.SIZE; bit++) {
			final byte[] flipped = payload.clone();

			flipped[bit / Byte.SIZE] ^= 1 << (bit % Byte.SIZE);
			assertThat("bit " + bit, client.receive(wire.exitContext.post(answer.header().tag(), flipped)),
				equalTo(Optional.empty()));
			assertThat("bit " + bit, client.state(), is(SessionState.OPENING));
		}

		client.receive(answer);
		assertThat(client.state(), is(SessionState.OPEN));
	}

	@Example
	public void shouldIgnoreAnAnswerTooShortForTheHandshake() {
		final SessionWire wire = new SessionWire(EXIT_ID);
		final ClientSession client = wire.client(ONLY_EXIT, CONNECT);

		client.open();

		final Entry answer = answerTo(wire, wire.posted.poll());

		for (int size : List.of(0, 1, NoiseNk.KEY_SIZE, NoiseNk.MESSAGE_SIZE, Session.ANSWER_SIZE - 1)) {
			assertThat("size " + size, client.receive(wire.exitContext.post(answer.header().tag(),
				Arrays.copyOf(answer.payload(), size))), equalTo(Optional.empty()));
			assertThat("size " + size, client.state(), is(SessionState.OPENING));
		}

		client.receive(answer);
		assertThat(client.state(), is(SessionState.OPEN));
	}

	@Example
	public void shouldIgnoreAnAnswerWithAKeyOfLowOrder() {
		final SessionWire wire = new SessionWire(EXIT_ID);
		final ClientSession client = wire.client(ONLY_EXIT, CONNECT);

		client.open();

		final Entry answer = answerTo(wire, wire.posted.poll());

		for (String point : LOW_ORDER) {
			final byte[] payload = answer.payload();

			System.arraycopy(HexFormat.of().parseHex(point), 0, payload, 0, NoiseNk.KEY_SIZE);
			assertThat(point, client.receive(wire.exitContext.post(answer.header().tag(), payload)),
				equalTo(Optional.empty()));
			assertThat(point, client.state(), is(SessionState.OPENING));
		}
	}

	@Example
	public void shouldIgnoreTheAnswerUnderAnotherTag() {
		final SessionWire wire = new SessionWire(EXIT_ID);
		final ClientSession client = wire.client(ONLY_EXIT, CONNECT);

		client.open();

		final Entry answer = answerTo(wire, wire.posted.poll());

		final Entry underAnotherTag = wire.exitContext.post(Tag.random(new SecureRandom()), answer.payload());

		assertThat(client.expects(underAnotherTag), is(false));
		assertIgnored("random tag", client, underAnotherTag);
		client.receive(answer);
		assertThat(client.state(), is(SessionState.OPEN));
	}

	/* An answer to another client's opening, taken off the wire */
	private static Entry answerForSomeoneElse(SessionWire wire) {
		final ClientSession stranger = new ClientSession(wire.context(3), ONLY_EXIT, CONNECT);

		stranger.open();

		final Entry answer = answerTo(wire, wire.posted.poll());

		wire.posted.clear();
		return answer;
	}

	@Example
	public void shouldIgnoreAnAnswerBeforeAnyOpening() {
		final SessionWire wire = new SessionWire(EXIT_ID);
		final ClientSession client = wire.client(ONLY_EXIT, CONNECT);
		final Entry answer = answerForSomeoneElse(wire);

		assertThat(client.expects(answer), is(false));
		assertThat(client.receive(answer), equalTo(Optional.empty()));
		assertThat(client.state(), is(SessionState.NEW));
		assertThat(wire.posted.isEmpty(), is(true));
		assertThat(client.open(), is(true));
		assertIgnored("the same answer after the opening", client, answer);
	}

	@Example
	public void shouldIgnoreAnAnswerAndNotOpenAgainWhenTheGroupOfferedNoExit() {
		final SessionWire wire = new SessionWire(EXIT_ID);
		final ClientSession client = wire.client(excluded -> Optional.empty(), CONNECT);
		final Entry answer = answerForSomeoneElse(wire);

		assertThat(client.open(), is(false));
		assertThat(client.receive(answer), equalTo(Optional.empty()));
		assertThat(client.state(), is(SessionState.CLOSED));
		Expect.assertThrows(IllegalStateException.class, client::open);
		Expect.assertThrows(IllegalStateException.class, () -> client.send(new Data(new byte[] { 1 })));
		assertThat(wire.posted.isEmpty(), is(true));
	}

	/* A key of the wrong size or of low order opens no handshake, so that member is passed over */
	@Example
	public void shouldNotOpenTowardsAnExitWhoseKeyIsNoKey() {
		for (byte[] key : List.of(new byte[0], new byte[NoiseNk.KEY_SIZE - 1], new byte[NoiseNk.KEY_SIZE + 1],
			HexFormat.of().parseHex(LOW_ORDER.get(0)), HexFormat.of().parseHex(LOW_ORDER.get(2)))) {

			final SessionWire wire = new SessionWire(EXIT_ID);
			final ClientSession client = wire.client(excluded -> Optional.of(new ExitCandidate("gn-bad", key)),
				CONNECT);

			assertThat(client.open(), is(false));
			assertThat(client.state(), is(SessionState.CLOSED));
			assertThat(wire.posted.isEmpty(), is(true));
			assertThat(client.exit(), equalTo(Optional.empty()));
		}
	}

	@Example
	public void shouldAskTheSelectorWithNothingExcludedFirst() {
		final SessionWire wire = new SessionWire(EXIT_ID);
		final List<Set<String>> excluded = new ArrayList<>();
		final ClientSession client = wire.client(passedOver -> {
			excluded.add(Set.copyOf(passedOver));
			return ONLY_EXIT.exit(passedOver);
		}, CONNECT);

		client.open();
		assertThat(excluded, equalTo(List.of(Set.of())));
	}

	/* The key comes first, then the padding, then the tag */
	@Example
	public void shouldDrawTheOpeningFromTheRandomnessOfItsContext() {
		final SessionWire wire = new SessionWire(EXIT_ID);
		final ClientSession client = clientOf(wire, wire.posted::add, new KnownRandom(), ONLY_EXIT);
		final byte[] tag = new byte[Tag.SIZE];

		new KnownRandom().nextBytes(tag);
		client.open();

		final Entry opening = wire.posted.peek();

		assertThat(opening.header().tag(), equalTo(new Tag(tag)));
		assertThat(Arrays.copyOf(opening.payload(), NoiseNk.KEY_SIZE), equalTo(new X25519PrivateKeyParameters(
			knownPrivateKey()).generatePublicKey().getEncoded()));
	}

	@Example
	public void shouldKeepThePrivateKeyOnlyForTheAttempt() throws IllegalAccessException {
		final SessionWire wire = new SessionWire(EXIT_ID);
		final ClientSession client = clientOf(wire, wire.posted::add, new KnownRandom(), ONLY_EXIT);
		final byte[] privateKey = knownPrivateKey();

		client.open();
		assertThat(holds(client, privateKey), is(true));
		assertThat(client.toString(), not(containsString(HexFormat.of().formatHex(privateKey))));

		final Entry opening = wire.posted.poll();

		assertThat(contains(opening.payload(), privateKey), is(false));
		client.receive(answerTo(wire, opening));
		assertThat(client.state(), is(SessionState.OPEN));
		assertThat(holds(client, privateKey), is(false));
		wire.posted.forEach(entry -> assertThat(contains(entry.payload(), privateKey), is(false)));
	}

	@Example
	public void shouldCloseBrokenWhenTheConnectCannotBePosted() throws IllegalAccessException {
		final SessionWire wire = new SessionWire(EXIT_ID);
		final AtomicInteger posts = new AtomicInteger();
		final ClientSession client = clientOf(wire, entry -> {
			if (posts.getAndIncrement() > 0) {
				throw new IllegalStateException("The outbox refuses");
			}

			wire.posted.add(entry);
		}, new KnownRandom(), ONLY_EXIT);

		client.open();

		final Entry answer = answerTo(wire, wire.posted.poll());

		assertThat(client.receive(answer), equalTo(Optional.of(new Close(CloseReason.BROKEN))));
		assertThat(client.state(), is(SessionState.CLOSED));
		assertThat(holds(client, knownPrivateKey()), is(false));
		assertThat(client.receive(answer), equalTo(Optional.empty()));
		Expect.assertThrows(IllegalStateException.class, () -> client.send(new Data(new byte[] { 1 })));
	}

	/* The exit's writer is known once its answer completed the handshake, and not while the session opens */
	@Example
	public void shouldNameTheWriterOfItsExitOnceTheAnswerCompletedTheHandshake() {
		final SessionWire wire = new SessionWire(EXIT_ID);
		final ClientSession client = wire.client(ONLY_EXIT, CONNECT);

		assertThat(client.peer(), equalTo(Optional.empty()));
		client.open();
		Session.tryAccept(wire.exitContext, wire.posted.poll(), SessionFixtures.sealingPrivate(EXIT_ID))
			.orElseThrow();
		assertThat(client.peer(), equalTo(Optional.empty()));
		client.receive(wire.posted.pollLast());
		assertThat(client.peer(), equalTo(Optional.of(wire.exitContext.writer().writer())));
	}

	/* The answer while it opens, then the exit's next message and no other */
	@Example
	public void shouldExpectTheAnswerAndThenTheNextMessageOfTheExit() {
		final SessionWire wire = new SessionWire(EXIT_ID);
		final ClientSession client = wire.client(ONLY_EXIT, CONNECT);

		client.open();

		final Entry opening = wire.posted.poll();
		final Session exit = Session.tryAccept(wire.exitContext, opening, SessionFixtures.sealingPrivate(EXIT_ID))
			.orElseThrow();
		final Entry answer = wire.posted.pollLast();

		assertThat(client.expects(opening), is(false));
		assertThat(client.expects(answer), is(true));
		client.receive(answer);
		wire.posted.clear();
		exit.send(new Data(new byte[] { 1 }));
		exit.send(new Data(new byte[] { 2 }));

		final Entry first = wire.posted.poll();
		final Entry second = wire.posted.poll();

		assertThat(client.expects(answer), is(false));
		assertThat(client.expects(second), is(false));
		assertThat(client.expects(first), is(true));
		client.receive(first);
		assertThat(client.expects(second), is(true));
		client.close(CloseReason.DONE);
		assertThat(client.expects(second), is(false));
	}

	@Example
	public void shouldRequireItsParts() {
		assertThat(Expect.assertThrows(NullPointerException.class,
			() -> new ClientSession(null, ONLY_EXIT, CONNECT)).getMessage(), is("context"));
		assertThat(Expect.assertThrows(NullPointerException.class,
			() -> new ClientSession(new SessionWire(EXIT_ID).clientContext, null, CONNECT)).getMessage(), is("exits"));
		assertThat(Expect.assertThrows(NullPointerException.class,
			() -> new ClientSession(new SessionWire(EXIT_ID).clientContext, ONLY_EXIT, null)).getMessage(),
			is("connect"));
	}
}

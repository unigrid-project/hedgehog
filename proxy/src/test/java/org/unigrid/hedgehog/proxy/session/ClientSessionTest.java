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

package org.unigrid.hedgehog.proxy.session;

import io.netty.handler.codec.socksx.v5.Socks5AddressType;
import io.netty.handler.codec.socksx.v5.Socks5CommandStatus;
import io.netty.handler.codec.socksx.v5.Socks5CommandType;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
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
import org.unigrid.hedgehog.proxy.chain.ChainId;
import org.unigrid.hedgehog.proxy.chain.Entry;
import org.unigrid.hedgehog.proxy.chain.EntryKind;
import org.unigrid.hedgehog.proxy.chain.FeedWriter;
import org.unigrid.hedgehog.proxy.chain.Fingerprint;
import org.unigrid.hedgehog.proxy.chain.WriterId;
import org.unigrid.hedgehog.proxy.group.ExitChoice;
import org.unigrid.hedgehog.proxy.group.ProxySettings;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

public class ClientSessionTest {
	private static final String EXIT_ID = "gn-exit";
	private static final ExitSelector ONLY_EXIT = (fingerprint, excluded) -> ExitChoice.exit(fingerprint,
		List.of(EXIT_ID), excluded);
	private static final Connect CONNECT = new Connect(Socks5CommandType.CONNECT, new SocksAddress(
		Socks5AddressType.DOMAIN, "example.org", 443));
	private static final Connected CONNECTED = new Connected(Socks5CommandStatus.SUCCESS, new SocksAddress(
		Socks5AddressType.IPv4, "192.0.2.10", 40_000));
	private static final ChainId OTHER_CHAIN = new ChainId(SessionFixtures.filled(ChainId.SIZE, 8));
	private static final int LONGEST_FITTING_ID = ProxySettings.DEFAULTS.chainLimits().maxPayloadBytes()
		- EphemeralKey.SIZE;

	/* Points of low order: the secret agreed with any of them is all zeros, which a peer can confirm */
	private static final List<String> LOW_ORDER = List.of(
		"0000000000000000000000000000000000000000000000000000000000000000",
		"0100000000000000000000000000000000000000000000000000000000000000",
		"e0eb7a7c3b41b8ae1656e3faf19fc46ada098deb9c32b1fd866205165f49b800",
		"5f9c95bca3508c24b1d0b1559c83ef5b04445cc4581c8e86d8224eddd09f1157");

	/* Draws that depend only on how many bytes are asked for, so that the test can build the client's private key */
	private static final class KnownRandom extends SecureRandom {
		@Override
		public void nextBytes(byte[] bytes) {
			for (int i = 0; i < bytes.length; i++) {
				bytes[i] = (byte) (bytes.length * 3 + i);
			}
		}
	}

	/* An exit's session and the ACCEPT it posted for an OPEN */
	private record Answer(Session session, Entry accept) {
		/* Kept together so that a test can let several exits answer the same OPEN */
	}

	@Provide
	public Arbitrary<List<byte[]>> chunks() {
		return Arbitraries.bytes().array(byte[].class).ofMinSize(1).ofMaxSize(2000).list().ofMaxSize(10);
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

		final Entry open = wire.posted.peek();

		assertThat(client.state(), is(SessionState.OPENING));
		assertThat(client.exit(), equalTo(Optional.of(EXIT_ID)));
		assertThat(open.header().kind(), is(EntryKind.OPEN));
		assertThat(Optional.of(open.header().fingerprint()), equalTo(client.fingerprint()));
		assertThat(Handshake.names(open.payload(), EXIT_ID), is(true));
		Expect.assertThrows(IllegalStateException.class, client::open);
		Expect.assertThrows(IllegalStateException.class, () -> client.send(new Data(new byte[] { 1 })));
	}

	@Example
	public void shouldCloseWhenTheGroupOffersNoExit() {
		final SessionWire wire = new SessionWire(EXIT_ID);
		final ClientSession client = wire.client((fingerprint, excluded) -> Optional.empty(), CONNECT);

		assertThat(client.open(), is(false));
		assertThat(client.state(), is(SessionState.CLOSED));
		assertThat(wire.posted.isEmpty(), is(true));
	}

	@Example
	public void shouldWaitPastAnAcceptThatDoesNotCompleteTheHandshake() {
		final SessionWire wire = new SessionWire(EXIT_ID);
		final ClientSession client = wire.client(ONLY_EXIT, CONNECT);

		client.open();

		final SessionContext impostor = wire.context(3);
		final Entry forged = impostor.post(client.fingerprint().orElseThrow(), EntryKind.ACCEPT,
			new byte[Handshake.ACCEPT_SIZE]);
		final Entry otherSession = impostor.post(SessionFixtures.fingerprint(3), EntryKind.ACCEPT,
			EphemeralKey.generate(impostor.random()).publicKey());

		assertThat(client.receive(forged), equalTo(Optional.empty()));
		assertThat(client.receive(otherSession), equalTo(Optional.empty()));
		assertThat(client.state(), is(SessionState.OPENING));
		wire.pump();
		assertThat(client.state(), is(SessionState.OPEN));
		assertThat(wire.exitSession().peer(), equalTo(wire.clientContext.writer().writer()));
		assertThat(wire.atExit, equalTo(List.of(CONNECT)));
	}

	/* The exit's ACCEPT to an OPEN, taken off the wire before anyone else sees it */
	private static Entry acceptTo(SessionWire wire, Entry open) {
		Session.accept(wire.exitContext, open, EXIT_ID).orElseThrow();
		return wire.posted.pollLast();
	}

	@Example
	public void shouldIgnoreAnAcceptThatAnotherWriterPostedAgain() {
		final SessionWire wire = new SessionWire(EXIT_ID);
		final ClientSession client = wire.client(ONLY_EXIT, CONNECT);

		client.open();

		final Entry accept = acceptTo(wire, wire.posted.poll());
		final Entry postedAgain = wire.context(3).post(client.fingerprint().orElseThrow(), EntryKind.ACCEPT,
			accept.payload());

		assertThat(client.receive(postedAgain), equalTo(Optional.empty()));
		assertThat(client.state(), is(SessionState.OPENING));
		client.receive(accept);
		assertThat(client.state(), is(SessionState.OPEN));
	}

	@Example
	public void shouldIgnoreTheAcceptToAnOpenAnotherWriterPostedAgain() {
		final SessionWire wire = new SessionWire(EXIT_ID);
		final ClientSession client = wire.client(ONLY_EXIT, CONNECT);

		client.open();

		final Entry open = wire.posted.poll();
		final Entry postedAgain = wire.context(3).post(client.fingerprint().orElseThrow(), EntryKind.OPEN,
			open.payload());

		assertThat(client.receive(acceptTo(wire, postedAgain)), equalTo(Optional.empty()));
		assertThat(client.state(), is(SessionState.OPENING));
		client.receive(acceptTo(wire, open));
		assertThat(client.state(), is(SessionState.OPEN));
	}

	@Example
	public void shouldIgnoreTheAcceptToAnotherClientKeyUnderTheSameFingerprint() {
		final SessionWire wire = new SessionWire(EXIT_ID);
		final ClientSession client = wire.client(ONLY_EXIT, CONNECT);

		client.open();

		final Entry open = wire.posted.poll();
		final SessionContext other = wire.context(4);
		final Entry copied = other.post(client.fingerprint().orElseThrow(), EntryKind.OPEN,
			Handshake.open(EphemeralKey.generate(other.random()), EXIT_ID));

		assertThat(client.receive(acceptTo(wire, copied)), equalTo(Optional.empty()));
		assertThat(client.state(), is(SessionState.OPENING));
		client.receive(acceptTo(wire, open));
		assertThat(client.state(), is(SessionState.OPEN));
	}

	@Example
	public void shouldIgnoreAnAcceptOnAnotherChainThatOtherwiseConfirmsTheHandshake() throws HandshakeException {
		final SessionWire wire = new SessionWire(EXIT_ID);
		final ClientSession client = wire.client(ONLY_EXIT, CONNECT);

		client.open();

		final Fingerprint fingerprint = client.fingerprint().orElseThrow();
		final SessionContext impostor = wire.context(3);
		final SessionContext elsewhere = new SessionContext(new FeedWriter(SessionFixtures.key(3),
			new ChainId(SessionFixtures.filled(ChainId.SIZE, 8))), wire.posted::add, wire.clock::get,
			new SecureRandom(), ProxySettings.DEFAULTS);
		final EphemeralKey key = EphemeralKey.generate(impostor.random());
		final byte[] payload = Handshake.accept(key, Handshake.exitKeys(key, wire.posted.poll(),
			impostor.writer().writer()));

		assertThat(client.receive(elsewhere.post(fingerprint, EntryKind.ACCEPT, payload)), equalTo(Optional.empty()));
		assertThat(client.state(), is(SessionState.OPENING));
		client.receive(impostor.post(fingerprint, EntryKind.ACCEPT, payload));
		assertThat(client.state(), is(SessionState.OPEN));
	}

	private static Answer answer(SessionWire wire, SessionContext exit, Entry open) {
		final Session session = Session.accept(exit, open, EXIT_ID).orElseThrow();

		return new Answer(session, wire.posted.pollLast());
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

	/* An ACCEPT that confirms a handshake of someone else, taken off the wire */
	private static Entry acceptForSomeoneElse(SessionWire wire) {
		final SessionContext other = wire.context(3);
		final Entry open = other.post(SessionFixtures.fingerprint(3), EntryKind.OPEN,
			Handshake.open(EphemeralKey.generate(other.random()), EXIT_ID));
		final Entry accept = acceptTo(wire, open);

		wire.posted.clear();
		return accept;
	}

	private static byte[] acceptPayload(EphemeralKey own, byte[] clientKey, Transcript transcript)
		throws HandshakeException {

		return Handshake.accept(own, SessionKeys.derive(own.agree(clientKey), transcript));
	}

	private static void assertIgnored(String why, ClientSession client, Entry entry) {
		assertThat(why, client.receive(entry), equalTo(Optional.empty()));
		assertThat(why, client.state(), is(SessionState.OPENING));
	}

	@Example
	public void shouldStayWithTheExitWhoseAcceptCompletedTheHandshake() {
		final SessionWire wire = new SessionWire(EXIT_ID);
		final ClientSession client = wire.client(ONLY_EXIT, CONNECT);

		client.open();

		final Fingerprint fingerprint = client.fingerprint().orElseThrow();
		final Entry open = wire.posted.poll();
		final Answer first = answer(wire, wire.exitContext, open);
		final Answer second = answer(wire, wire.context(3), open);

		assertThat(client.receive(first.accept()), equalTo(Optional.empty()));
		assertThat(wire.posted.size(), is(1));
		assertThat(first.session().receive(wire.posted.peek()), equalTo(Optional.of(CONNECT)));
		assertThat(client.receive(second.accept()), equalTo(Optional.empty()));
		assertThat(wire.posted.size(), is(1));
		assertThat(client.state(), is(SessionState.OPEN));
		assertThat(client.fingerprint(), equalTo(Optional.of(fingerprint)));
		assertThat(client.exit(), equalTo(Optional.of(EXIT_ID)));
		second.session().send(new Data(new byte[] { 2 }));
		assertThat(client.receive(wire.posted.pollLast()), equalTo(Optional.empty()));
		first.session().send(new Data(new byte[] { 1 }));
		assertThat(client.receive(wire.posted.pollLast()), equalTo(Optional.of(new Data(new byte[] { 1 }))));
	}

	/* A gridnode id is not bound to a writer key yet, so the writer of the ACCEPT is not compared with anything
	   the client knows of the exit: it is the first writer whose ACCEPT confirms the handshake. Signed gridnode
	   announcements close this. */
	@Example
	public void shouldTakeTheFirstAcceptThatConfirmsTheHandshakeWhoeverPostedIt() {
		final SessionWire wire = new SessionWire(EXIT_ID);
		final ClientSession client = wire.client(ONLY_EXIT, CONNECT);

		client.open();

		final Entry open = wire.posted.poll();
		final Answer impostor = answer(wire, wire.context(3), open);
		final Answer exit = answer(wire, wire.exitContext, open);

		client.receive(impostor.accept());
		client.receive(exit.accept());
		assertThat(client.state(), is(SessionState.OPEN));
		assertThat(wire.posted.size(), is(1));
		assertThat(impostor.session().receive(wire.posted.peek()), equalTo(Optional.of(CONNECT)));
		exit.session().send(new Data(new byte[] { 1 }));
		assertThat(client.receive(wire.posted.pollLast()), equalTo(Optional.empty()));
	}

	/* What the chain gives up on in the feed of the exit is what the session lost, so the client side needs to know
	   whose feed that is, and only the ACCEPT that completed the handshake tells it */
	@Example
	public void shouldKnowTheFeedOfItsExitOnceTheAcceptCompletedTheHandshake() {
		final SessionWire wire = new SessionWire(EXIT_ID);
		final ClientSession client = wire.client(ONLY_EXIT, CONNECT);

		client.open();
		assertThat(client.exitWriter(), equalTo(Optional.empty()));

		final Entry open = wire.posted.poll();
		final Answer impostor = answer(wire, wire.context(3), open);
		final Answer exit = answer(wire, wire.exitContext, open);

		client.receive(exit.accept());
		client.receive(impostor.accept());
		assertThat(client.exitWriter(), equalTo(Optional.of(wire.exitContext.writer().writer())));
	}

	@Example
	public void shouldIgnoreAnAcceptWhoseTranscriptDiffersInOneField() throws HandshakeException {
		final SessionWire wire = new SessionWire(EXIT_ID);
		final ClientSession client = wire.client(ONLY_EXIT, CONNECT);

		client.open();

		final Entry open = wire.posted.poll();
		final Fingerprint fingerprint = open.header().fingerprint();
		final byte[] clientKey = Handshake.clientKey(open.payload());
		final SessionContext exit = wire.context(3);
		final EphemeralKey own = EphemeralKey.generate(exit.random());
		final byte[] exitKey = own.publicKey();
		final byte[] otherKey = EphemeralKey.generate(exit.random()).publicKey();
		final WriterId clientWriter = wire.clientContext.writer().writer();
		final WriterId exitWriter = exit.writer().writer();
		final WriterId otherWriter = wire.context(5).writer().writer();
		final Transcript right = new Transcript(SessionFixtures.CHAIN, fingerprint, clientKey, exitKey,
			clientWriter, exitWriter);
		final Map<String, Transcript> wrong = Map.of(
			"chain", new Transcript(OTHER_CHAIN, fingerprint, clientKey, exitKey, clientWriter, exitWriter),
			"fingerprint", new Transcript(SessionFixtures.CHAIN, SessionFixtures.fingerprint(9), clientKey, exitKey,
				clientWriter, exitWriter),
			"client key", new Transcript(SessionFixtures.CHAIN, fingerprint, otherKey, exitKey, clientWriter,
				exitWriter),
			"exit key", new Transcript(SessionFixtures.CHAIN, fingerprint, clientKey, otherKey, clientWriter,
				exitWriter),
			"client writer", new Transcript(SessionFixtures.CHAIN, fingerprint, clientKey, exitKey, otherWriter,
				exitWriter),
			"exit writer", new Transcript(SessionFixtures.CHAIN, fingerprint, clientKey, exitKey, clientWriter,
				otherWriter));

		for (Map.Entry<String, Transcript> field : wrong.entrySet()) {
			assertIgnored(field.getKey(), client, exit.post(fingerprint, EntryKind.ACCEPT,
				acceptPayload(own, clientKey, field.getValue())));
		}

		client.receive(exit.post(fingerprint, EntryKind.ACCEPT, acceptPayload(own, clientKey, right)));
		assertThat(client.state(), is(SessionState.OPEN));
	}

	@Example
	public void shouldIgnoreAnAcceptWithAnyBitOfItsKeyOrConfirmationChanged() {
		final SessionWire wire = new SessionWire(EXIT_ID);
		final ClientSession client = wire.client(ONLY_EXIT, CONNECT);

		client.open();

		final Fingerprint fingerprint = client.fingerprint().orElseThrow();
		final Entry accept = acceptTo(wire, wire.posted.poll());
		final byte[] payload = accept.payload();

		for (int bit = 0; bit < payload.length * Byte.SIZE; bit++) {
			final byte[] flipped = payload.clone();

			flipped[bit / Byte.SIZE] ^= 1 << (bit % Byte.SIZE);
			assertIgnored("bit " + bit, client, wire.exitContext.post(fingerprint, EntryKind.ACCEPT, flipped));
		}

		client.receive(accept);
		assertThat(client.state(), is(SessionState.OPEN));
	}

	@Example
	public void shouldIgnoreAnAcceptOfAnotherSize() {
		final SessionWire wire = new SessionWire(EXIT_ID);
		final ClientSession client = wire.client(ONLY_EXIT, CONNECT);

		client.open();

		final Fingerprint fingerprint = client.fingerprint().orElseThrow();
		final Entry accept = acceptTo(wire, wire.posted.poll());

		for (int size : List.of(0, 1, EphemeralKey.SIZE, Handshake.ACCEPT_SIZE - 1, Handshake.ACCEPT_SIZE + 1,
			2 * Handshake.ACCEPT_SIZE)) {

			assertIgnored("size " + size, client, wire.exitContext.post(fingerprint, EntryKind.ACCEPT,
				Arrays.copyOf(accept.payload(), size)));
		}

		client.receive(accept);
		assertThat(client.state(), is(SessionState.OPEN));
	}

	@Example
	public void shouldIgnoreAnAcceptWithAKeyOfLowOrderThatConfirmsItsOwnSecret() throws HandshakeException {
		final SessionWire wire = new SessionWire(EXIT_ID);
		final ClientSession client = wire.client(ONLY_EXIT, CONNECT);

		client.open();

		final Entry open = wire.posted.poll();
		final Fingerprint fingerprint = open.header().fingerprint();
		final SessionContext exit = wire.context(3);

		for (String point : LOW_ORDER) {
			final byte[] lowOrder = HexFormat.of().parseHex(point);
			final Transcript transcript = new Transcript(SessionFixtures.CHAIN, fingerprint,
				Handshake.clientKey(open.payload()), lowOrder, wire.clientContext.writer().writer(),
				exit.writer().writer());
			final byte[] confirmation = SessionKeys.derive(new byte[EphemeralKey.SIZE], transcript).confirmation();

			assertIgnored(point, client, exit.post(fingerprint, EntryKind.ACCEPT,
				ByteBuffer.allocate(Handshake.ACCEPT_SIZE).put(lowOrder).put(confirmation).array()));
		}
	}

	@Example
	public void shouldIgnoreAnEntryOfAnotherKindThatCarriesTheAcceptsPayload() {
		final SessionWire wire = new SessionWire(EXIT_ID);
		final ClientSession client = wire.client(ONLY_EXIT, CONNECT);

		client.open();

		final Fingerprint fingerprint = client.fingerprint().orElseThrow();
		final Entry accept = acceptTo(wire, wire.posted.poll());

		for (EntryKind kind : EntryKind.values()) {
			if (kind != EntryKind.ACCEPT) {
				assertIgnored(kind.name(), client, wire.exitContext.post(fingerprint, kind, accept.payload()));
			}
		}

		client.receive(accept);
		assertThat(client.state(), is(SessionState.OPEN));
	}

	@Example
	public void shouldIgnoreTheAcceptsPayloadUnderAnotherFingerprint() {
		final SessionWire wire = new SessionWire(EXIT_ID);
		final ClientSession client = wire.client(ONLY_EXIT, CONNECT);

		client.open();

		final Entry accept = acceptTo(wire, wire.posted.poll());

		assertIgnored("fingerprint", client, wire.exitContext.post(SessionFixtures.fingerprint(3), EntryKind.ACCEPT,
			accept.payload()));
		client.receive(accept);
		assertThat(client.state(), is(SessionState.OPEN));
	}

	@Example
	public void shouldIgnoreAnAcceptBeforeAnyOpen() {
		final SessionWire wire = new SessionWire(EXIT_ID);
		final ClientSession client = wire.client(ONLY_EXIT, CONNECT);
		final Entry accept = acceptForSomeoneElse(wire);

		assertThat(client.receive(accept), equalTo(Optional.empty()));
		assertThat(client.state(), is(SessionState.NEW));
		assertThat(client.fingerprint(), equalTo(Optional.empty()));
		assertThat(wire.posted.isEmpty(), is(true));
		assertThat(client.open(), is(true));
		assertIgnored("the same ACCEPT after the OPEN", client, accept);
	}

	@Example
	public void shouldIgnoreAnAcceptAndNotOpenAgainWhenTheGroupOfferedNoExit() {
		final SessionWire wire = new SessionWire(EXIT_ID);
		final ClientSession client = wire.client((fingerprint, excluded) -> Optional.empty(), CONNECT);
		final Entry accept = acceptForSomeoneElse(wire);

		assertThat(client.open(), is(false));
		assertThat(client.receive(accept), equalTo(Optional.empty()));
		assertThat(client.state(), is(SessionState.CLOSED));
		Expect.assertThrows(IllegalStateException.class, client::open);
		Expect.assertThrows(IllegalStateException.class, () -> client.send(new Data(new byte[] { 1 })));
		assertThat(wire.posted.isEmpty(), is(true));
	}

	@Example
	public void shouldNotBuildAnOpenThatDoesNotFitOneEntry() {
		for (String id : List.of("g".repeat(LONGEST_FITTING_ID + 1), "é".repeat(LONGEST_FITTING_ID / 2 + 1))) {
			final SessionWire wire = new SessionWire(EXIT_ID);
			final ClientSession client = wire.client((fingerprint, excluded) -> Optional.of(id), CONNECT);

			assertThat(client.open(), is(false));
			assertThat(client.state(), is(SessionState.CLOSED));
			assertThat(wire.posted.isEmpty(), is(true));
			assertThat(client.fingerprint(), equalTo(Optional.empty()));
			assertThat(client.exit(), equalTo(Optional.empty()));
		}
	}

	@Example
	public void shouldOpenTowardsAnExitWhoseIdFillsTheOpenExactly() {
		for (String id : List.of("g".repeat(LONGEST_FITTING_ID),
			"é".repeat(LONGEST_FITTING_ID / 2) + "g".repeat(LONGEST_FITTING_ID % 2))) {

			final SessionWire wire = new SessionWire(id);
			final ClientSession client = wire.client((fingerprint, excluded) -> Optional.of(id), CONNECT);

			assertThat(client.open(), is(true));
			assertThat(wire.posted.peek().size(), is((int) ProxySettings.DEFAULTS.maxEntryBytes()));
			wire.pump();
			assertThat(client.state(), is(SessionState.OPEN));
			assertThat(wire.atExit, equalTo(List.of(CONNECT)));
		}
	}

	@Example
	public void shouldChooseTheExitForTheFingerprintOfTheAttempt() {
		final SessionWire wire = new SessionWire(EXIT_ID);
		final List<Fingerprint> asked = new ArrayList<>();
		final List<Set<String>> excluded = new ArrayList<>();
		final ClientSession client = wire.client((fingerprint, passedOver) -> {
			asked.add(fingerprint);
			excluded.add(passedOver);
			return Optional.of(EXIT_ID);
		}, CONNECT);

		client.open();
		assertThat(asked, equalTo(List.of(client.fingerprint().orElseThrow())));
		assertThat(excluded, equalTo(List.of(Set.of())));
	}

	@Example
	public void shouldDrawTheFingerprintFromTheRandomnessOfItsContext() {
		final SessionWire wire = new SessionWire(EXIT_ID);
		final ClientSession client = clientOf(wire, wire.posted::add, new KnownRandom(), ONLY_EXIT);
		final byte[] drawn = new byte[Fingerprint.SIZE];

		new KnownRandom().nextBytes(drawn);
		client.open();
		assertThat(client.fingerprint(), equalTo(Optional.of(new Fingerprint(drawn))));
	}

	@Example
	public void shouldKeepThePrivateKeyOnlyForTheAttempt() throws IllegalAccessException {
		final SessionWire wire = new SessionWire(EXIT_ID);
		final ClientSession client = clientOf(wire, wire.posted::add, new KnownRandom(), ONLY_EXIT);
		final byte[] privateKey = knownPrivateKey();

		client.open();
		assertThat(holds(client, privateKey), is(true));
		assertThat(client.toString(), not(containsString(HexFormat.of().formatHex(privateKey))));

		final Entry open = wire.posted.poll();

		assertThat(contains(open.payload(), privateKey), is(false));
		client.receive(acceptTo(wire, open));
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

		final Entry accept = acceptTo(wire, wire.posted.poll());

		assertThat(client.receive(accept), equalTo(Optional.of(new Close(CloseReason.BROKEN))));
		assertThat(client.state(), is(SessionState.CLOSED));
		assertThat(holds(client, knownPrivateKey()), is(false));
		assertThat(client.receive(accept), equalTo(Optional.empty()));
		Expect.assertThrows(IllegalStateException.class, () -> client.send(new Data(new byte[] { 1 })));
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

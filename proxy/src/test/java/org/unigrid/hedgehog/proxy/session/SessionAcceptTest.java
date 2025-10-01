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

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.StringLength;
import org.unigrid.hedgehog.proxy.Expect;
import org.unigrid.hedgehog.proxy.chain.ChainId;
import org.unigrid.hedgehog.proxy.chain.Entry;
import org.unigrid.hedgehog.proxy.chain.EntryKind;
import org.unigrid.hedgehog.proxy.chain.FeedWriter;
import org.unigrid.hedgehog.proxy.chain.Fingerprint;
import org.unigrid.hedgehog.proxy.chain.WriterId;
import org.unigrid.hedgehog.proxy.group.ProxySettings;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

public class SessionAcceptTest {
	private static final Fingerprint FINGERPRINT = SessionFixtures.fingerprint(5);
	private static final String EXIT_ID = "gridnode-exit";

	/* The smallest entry the spork allows, so that the longest exit id that fits is a few hundred bytes */
	private static final ProxySettings SMALLEST_ENTRIES = new ProxySettings(8, 3600, 4, 16, 32L * 1024 * 1024, 30,
		1024, 300, ProxySettings.CONNECT | ProxySettings.UDP);

	/* The points of low order, then p - 1, p and p + 1, and all of them again with the top bit set, which
	   X25519 ignores */
	private static final List<String> LOW_ORDER = List.of(
		"0000000000000000000000000000000000000000000000000000000000000000",
		"0100000000000000000000000000000000000000000000000000000000000000",
		"e0eb7a7c3b41b8ae1656e3faf19fc46ada098deb9c32b1fd866205165f49b800",
		"5f9c95bca3508c24b1d0b1559c83ef5b04445cc4581c8e86d8224eddd09f1157",
		"ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
		"edffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
		"eeffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
		"0000000000000000000000000000000000000000000000000000000000000080",
		"0100000000000000000000000000000000000000000000000000000000000080",
		"e0eb7a7c3b41b8ae1656e3faf19fc46ada098deb9c32b1fd866205165f49b880",
		"5f9c95bca3508c24b1d0b1559c83ef5b04445cc4581c8e86d8224eddd09f11d7",
		"ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
		"edffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
		"eeffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff");

	private final List<Entry> fromClient = new ArrayList<>();
	private final List<Entry> fromExit = new ArrayList<>();
	private final SessionContext client = SessionFixtures.context(1, fromClient, () -> SessionFixtures.NOW);
	private final SessionContext exit = SessionFixtures.context(2, fromExit, () -> SessionFixtures.NOW);
	private final EphemeralKey clientKey = EphemeralKey.generate(new SecureRandom());

	private static final class CountingRandom extends SecureRandom {
		private int draws;

		@Override
		public synchronized void nextBytes(byte[] bytes) {
			draws++;
			super.nextBytes(bytes);
		}
	}

	private Entry open(EntryKind kind, byte[] payload) {
		return client.post(FINGERPRINT, kind, payload);
	}

	private SessionKeys clientKeys(Entry accept) throws HandshakeException {
		return Handshake.clientKeys(clientKey, SessionFixtures.CHAIN, FINGERPRINT, client.writer().writer(), accept);
	}

	private void assertRefused(EphemeralKey key, ChainId chainId, Fingerprint fingerprint, WriterId writer,
		Entry accept) {

		Expect.assertThrows(HandshakeException.class,
			() -> Handshake.clientKeys(key, chainId, fingerprint, writer, accept));
	}

	/* The ACCEPT the exit posts for the client's OPEN */
	private Entry accepted() {
		Session.accept(exit, open(EntryKind.OPEN, Handshake.open(clientKey, EXIT_ID)), EXIT_ID).orElseThrow();
		return fromExit.remove(0);
	}

	private SessionContext exitContext(SecureRandom random, ProxySettings settings) {
		return new SessionContext(new FeedWriter(SessionFixtures.key(2), SessionFixtures.CHAIN), fromExit::add,
			() -> SessionFixtures.NOW, random, settings);
	}

	/* The client's own feed on another chain, which the exit does not serve */
	private static SessionContext clientOn(ChainId chainId) {
		return new SessionContext(new FeedWriter(SessionFixtures.key(1), chainId), new ArrayList<Entry>()::add,
			() -> SessionFixtures.NOW, new SecureRandom(), ProxySettings.DEFAULTS);
	}

	private static byte[] withKey(byte[] payload, byte[] key) {
		final byte[] replaced = payload.clone();

		System.arraycopy(key, 0, replaced, 0, EphemeralKey.SIZE);
		return replaced;
	}

	@Example
	public void shouldAnswerAnOpenNamingItWithAnAccept() throws HandshakeException {
		final Entry open = open(EntryKind.OPEN, Handshake.open(clientKey, EXIT_ID));
		final Session session = Session.accept(exit, open, EXIT_ID).orElseThrow();

		assertThat(fromExit, hasSize(1));
		assertThat(fromExit.get(0).header().kind(), is(EntryKind.ACCEPT));
		assertThat(fromExit.get(0).header().fingerprint(), equalTo(FINGERPRINT));
		assertThat(fromExit.get(0).payload().length, is(Handshake.ACCEPT_SIZE));
		assertThat(session.fingerprint(), equalTo(FINGERPRINT));
		assertThat(session.peer(), equalTo(client.writer().writer()));
		assertThat(session.isOpen(), is(true));
	}

	@Example
	public void shouldPostTheAcceptOnceOnTheChainOfTheOpenAsItsOwnWriter() {
		Session.accept(exit, open(EntryKind.OPEN, Handshake.open(clientKey, EXIT_ID)), EXIT_ID).orElseThrow();

		assertThat(fromExit, hasSize(1));
		assertThat(fromExit.get(0).header().chainId(), equalTo(SessionFixtures.CHAIN));
		assertThat(fromExit.get(0).header().writer(), equalTo(exit.writer().writer()));
		assertThat(fromExit.get(0).isAuthentic(), is(true));
		assertThat(fromClient, hasSize(1));
	}

	@Example
	public void shouldAgreeOnTheKeysWithTheClient() throws HandshakeException {
		final Entry open = open(EntryKind.OPEN, Handshake.open(clientKey, EXIT_ID));
		final Session exitSession = Session.accept(exit, open, EXIT_ID).orElseThrow();
		final Entry accept = fromExit.remove(0);
		final Session clientSession = new Session(client, FINGERPRINT, accept.header().writer(),
			new SessionCipher(clientKeys(accept), Direction.CLIENT_TO_EXIT, SessionFixtures.CHAIN, FINGERPRINT));

		exitSession.send(new Data(new byte[] { 4, 2 }));
		clientSession.send(new Data(new byte[] { 2, 4 }));
		assertThat(clientSession.receive(fromExit.get(0)), equalTo(Optional.of(new Data(new byte[] { 4, 2 }))));
		assertThat(exitSession.receive(fromClient.get(1)), equalTo(Optional.of(new Data(new byte[] { 2, 4 }))));
	}

	@Example
	public void shouldLeaveAnOpenForAnotherExitAlone() {
		final Entry open = open(EntryKind.OPEN, Handshake.open(clientKey, "gridnode-other"));

		assertThat(Session.accept(exit, open, EXIT_ID), equalTo(Optional.empty()));
		assertThat(fromExit, is(empty()));
	}

	@Example
	public void shouldIgnoreWhatIsNotAnOpenOfItsChain() {
		final SessionContext elsewhere = new SessionContext(new FeedWriter(SessionFixtures.key(1),
			new ChainId(SessionFixtures.filled(ChainId.SIZE, 8))), fromClient::add, () -> SessionFixtures.NOW,
			new SecureRandom(), ProxySettings.DEFAULTS);
		final byte[] payload = Handshake.open(clientKey, EXIT_ID);

		assertThat(Session.accept(exit, open(EntryKind.JOIN, payload), EXIT_ID), equalTo(Optional.empty()));
		assertThat(Session.accept(exit, elsewhere.post(FINGERPRINT, EntryKind.OPEN, payload), EXIT_ID),
			equalTo(Optional.empty()));
		assertThat(fromExit, is(empty()));
	}

	@Example
	public void shouldRefuseAClientKeyOfLowOrder() {
		final byte[] payload = Handshake.open(clientKey, EXIT_ID);

		Arrays.fill(payload, 0, EphemeralKey.SIZE, (byte) 0);
		assertThat(Session.accept(exit, open(EntryKind.OPEN, payload), EXIT_ID), equalTo(Optional.empty()));
		assertThat(Session.accept(exit, open(EntryKind.OPEN, EXIT_ID.getBytes(StandardCharsets.UTF_8)), EXIT_ID),
			equalTo(Optional.empty()));
		assertThat(fromExit, is(empty()));
	}

	@Property(tries = 50)
	public void shouldNameExactlyTheChosenExit(@ForAll @AlphaChars @StringLength(min = 1, max = 64) String exitId,
		@ForAll @AlphaChars @StringLength(min = 1, max = 64) String other) {
		final byte[] payload = Handshake.open(clientKey, exitId);

		assertThat(Arrays.copyOf(payload, EphemeralKey.SIZE), equalTo(clientKey.publicKey()));
		assertThat(Handshake.names(payload, exitId), is(true));
		assertThat(Handshake.names(payload, other), is(other.equals(exitId)));
	}

	@Example
	public void shouldUseAFreshKeyForEveryAccept() {
		final Entry open = open(EntryKind.OPEN, Handshake.open(clientKey, EXIT_ID));

		Session.accept(exit, open, EXIT_ID).orElseThrow();
		Session.accept(exit, open, EXIT_ID).orElseThrow();
		assertThat(Arrays.copyOf(fromExit.get(0).payload(), EphemeralKey.SIZE),
			not(equalTo(Arrays.copyOf(fromExit.get(1).payload(), EphemeralKey.SIZE))));
	}

	@Example
	public void shouldRefuseAnAcceptThatDoesNotConfirmThisHandshake() throws HandshakeException {
		final Entry accept = accepted();
		final WriterId writer = client.writer().writer();
		final Entry postedAgain = SessionFixtures.context(3, new ArrayList<>(), () -> SessionFixtures.NOW)
			.post(FINGERPRINT, EntryKind.ACCEPT, accept.payload());

		assertThat(clientKeys(accept).confirmation(), equalTo(Arrays.copyOfRange(accept.payload(),
			EphemeralKey.SIZE, Handshake.ACCEPT_SIZE)));
		assertRefused(EphemeralKey.generate(new SecureRandom()), SessionFixtures.CHAIN, FINGERPRINT, writer, accept);
		assertRefused(clientKey, SessionFixtures.CHAIN, SessionFixtures.fingerprint(6), writer, accept);
		assertRefused(clientKey, new ChainId(SessionFixtures.filled(ChainId.SIZE, 8)), FINGERPRINT, writer, accept);
		assertRefused(clientKey, SessionFixtures.CHAIN, FINGERPRINT, exit.writer().writer(), accept);
		assertRefused(clientKey, SessionFixtures.CHAIN, FINGERPRINT, writer, postedAgain);
	}

	@Example
	public void shouldRefuseAnAcceptWithAnyBitFlipped() {
		final byte[] payload = accepted().payload();

		for (int bit = 0; bit < payload.length * Byte.SIZE; bit++) {
			final byte[] flipped = payload.clone();

			flipped[bit / Byte.SIZE] ^= 1 << bit % Byte.SIZE;
			assertRefused(clientKey, SessionFixtures.CHAIN, FINGERPRINT, client.writer().writer(),
				exit.post(FINGERPRINT, EntryKind.ACCEPT, flipped));
		}
	}

	@Example
	public void shouldRefuseAnAcceptOfTheWrongSizeOrWithAKeyOfLowOrder() {
		final byte[] payload = accepted().payload();

		for (byte[] refused : List.of(Arrays.copyOf(payload, Handshake.ACCEPT_SIZE - 1),
			Arrays.copyOf(payload, Handshake.ACCEPT_SIZE + 1), new byte[Handshake.ACCEPT_SIZE])) {

			assertRefused(clientKey, SessionFixtures.CHAIN, FINGERPRINT, client.writer().writer(),
				exit.post(FINGERPRINT, EntryKind.ACCEPT, refused));
		}
	}

	@Example
	public void shouldTakeAnAcceptOfExactlyTheRightSizeAndNoOther() throws HandshakeException {
		final byte[] payload = accepted().payload();

		for (int size = 0; size <= Handshake.ACCEPT_SIZE + 8; size++) {
			final Entry accept = exit.post(FINGERPRINT, EntryKind.ACCEPT, Arrays.copyOf(payload, size));

			if (size == Handshake.ACCEPT_SIZE) {
				assertThat(clientKeys(accept).confirmation(), equalTo(Arrays.copyOfRange(payload, EphemeralKey.SIZE,
					Handshake.ACCEPT_SIZE)));
			} else {
				assertRefused(clientKey, SessionFixtures.CHAIN, FINGERPRINT, client.writer().writer(), accept);
			}
		}
	}

	@Example
	public void shouldRefuseTheUnconfirmedAcceptByItsConfirmation() {
		final byte[] payload = accepted().payload();

		for (int bit = EphemeralKey.SIZE * Byte.SIZE; bit < payload.length * Byte.SIZE; bit++) {
			final byte[] flipped = payload.clone();

			flipped[bit / Byte.SIZE] ^= 1 << bit % Byte.SIZE;

			final HandshakeException refused = Expect.assertThrows(HandshakeException.class, () ->
				clientKeys(exit.post(FINGERPRINT, EntryKind.ACCEPT, flipped)));

			assertThat(refused.getMessage(), containsString("does not confirm"));
		}
	}

	@Example
	public void shouldRefuseAnAcceptKeyOfLowOrderOfEveryKind() {
		final byte[] payload = accepted().payload();

		for (String point : LOW_ORDER) {
			assertRefused(clientKey, SessionFixtures.CHAIN, FINGERPRINT, client.writer().writer(),
				exit.post(FINGERPRINT, EntryKind.ACCEPT, withKey(payload, HexFormat.of().parseHex(point))));
		}
	}

	/* Whoever picks a key of low order knows the secret, so the confirmation of the all-zero secret over the public
	   transcript is one a forger can make */
	@Example
	public void shouldRefuseAForgedAcceptThatConfirmsTheSecretOfAKeyOfLowOrder() {
		final SessionContext forger = SessionFixtures.context(3, new ArrayList<>(), () -> SessionFixtures.NOW);
		final WriterId clientWriter = client.writer().writer();

		for (String point : LOW_ORDER) {
			final byte[] lowOrder = HexFormat.of().parseHex(point);
			final Transcript transcript = new Transcript(SessionFixtures.CHAIN, FINGERPRINT, clientKey.publicKey(),
				lowOrder, clientWriter, forger.writer().writer());
			final SessionKeys forged = SessionKeys.derive(new byte[EphemeralKey.SIZE], transcript);
			final byte[] payload = ByteBuffer.allocate(Handshake.ACCEPT_SIZE).put(lowOrder)
				.put(forged.confirmation()).array();

			assertRefused(clientKey, SessionFixtures.CHAIN, FINGERPRINT, clientWriter,
				forger.post(FINGERPRINT, EntryKind.ACCEPT, payload));
		}
	}

	@Example
	public void shouldAnswerOnlyAnOpenOfExactlyTheSizeThatNamesIt() {
		final byte[] valid = Handshake.open(clientKey, EXIT_ID);

		for (int size = 0; size <= valid.length + 8; size++) {
			final byte[] payload = Arrays.copyOf(valid, size);
			final boolean exact = size == valid.length;

			assertThat(Handshake.names(payload, EXIT_ID), is(exact));
			assertThat(Session.accept(exit, open(EntryKind.OPEN, payload), EXIT_ID).isPresent(), is(exact));
		}

		assertThat(fromExit, hasSize(1));
	}

	@Example
	public void shouldRefuseAnOpenWhoseExitIdDiffersInAnyBit() {
		final byte[] valid = Handshake.open(clientKey, EXIT_ID);

		for (int bit = EphemeralKey.SIZE * Byte.SIZE; bit < valid.length * Byte.SIZE; bit++) {
			final byte[] flipped = valid.clone();

			flipped[bit / Byte.SIZE] ^= 1 << bit % Byte.SIZE;
			assertThat(Handshake.names(flipped, EXIT_ID), is(false));
			assertThat(Session.accept(exit, open(EntryKind.OPEN, flipped), EXIT_ID), equalTo(Optional.empty()));
		}

		assertThat(fromExit, is(empty()));
	}

	@Example
	public void shouldRefuseAnOpenThatNamesAPrefixOrAnExtensionOfItsId() {
		for (String named : List.of("", "g", "gridnode-exi", "ridnode-exit", "gridnode-exits", "gridnode-exit ",
			"Gridnode-exit", "gridnode-exit\0")) {

			assertThat(Session.accept(exit, open(EntryKind.OPEN, Handshake.open(clientKey, named)), EXIT_ID),
				equalTo(Optional.empty()));
		}

		assertThat(fromExit, is(empty()));
	}

	@Example
	public void shouldNameAnIdByItsUtf8BytesAndNotItsCharacters() {
		final String exitId = "gridnöde-exit";
		final byte[] payload = Handshake.open(clientKey, exitId);

		assertThat(payload.length, is(EphemeralKey.SIZE + exitId.getBytes(StandardCharsets.UTF_8).length));
		assertThat(payload.length, is(EphemeralKey.SIZE + exitId.length() + 1));
		assertThat(Handshake.names(payload, exitId), is(true));
		assertThat(Session.accept(exit, open(EntryKind.OPEN, payload), exitId).isPresent(), is(true));
		assertThat(Handshake.names(payload, "gridnode-exit"), is(false));
	}

	@Example
	public void shouldTakeAnExitIdUpToWhatAnEntryCarriesAndNoLonger() {
		final SessionContext smallest = exitContext(new SecureRandom(), SMALLEST_ENTRIES);
		final String longest = "x".repeat(smallest.maxPayloadBytes() - EphemeralKey.SIZE);
		final String tooLong = longest + "x";
		final String muchTooLong = "x".repeat(10 * smallest.maxPayloadBytes());

		assertThat(Session.accept(smallest, open(EntryKind.OPEN, Handshake.open(clientKey, tooLong)), tooLong),
			equalTo(Optional.empty()));
		assertThat(Session.accept(smallest, open(EntryKind.OPEN, Handshake.open(clientKey, muchTooLong)),
			muchTooLong), equalTo(Optional.empty()));
		assertThat(fromExit, is(empty()));
		assertThat(Session.accept(smallest, open(EntryKind.OPEN, Handshake.open(clientKey, longest)), longest)
			.isPresent(), is(true));
		assertThat(fromExit, hasSize(1));
	}

	@Example
	public void shouldIgnoreEveryKindOfEntryButAnOpen() {
		final byte[] payload = Handshake.open(clientKey, EXIT_ID);

		for (EntryKind kind : EntryKind.values()) {
			assertThat(Session.accept(exit, open(kind, payload), EXIT_ID).isPresent(), is(kind == EntryKind.OPEN));
		}

		assertThat(fromExit, hasSize(1));
	}

	@Example
	public void shouldSpendNoRandomnessOnWhatItRefusesBeforeTheAgreement() {
		final CountingRandom random = new CountingRandom();
		final SessionContext counting = exitContext(random, SMALLEST_ENTRIES);
		final byte[] valid = Handshake.open(clientKey, EXIT_ID);
		final String tooLong = "x".repeat(counting.maxPayloadBytes());

		Session.accept(counting, open(EntryKind.JOIN, valid), EXIT_ID);
		Session.accept(counting, open(EntryKind.OPEN, Handshake.open(clientKey, "gridnode-other")), EXIT_ID);
		Session.accept(counting, open(EntryKind.OPEN, Arrays.copyOf(valid, valid.length - 1)), EXIT_ID);
		Session.accept(counting, open(EntryKind.OPEN, Arrays.copyOf(valid, valid.length + 1)), EXIT_ID);
		Session.accept(counting, open(EntryKind.OPEN, Handshake.open(clientKey, tooLong)), tooLong);
		Session.accept(counting, clientOn(new ChainId(SessionFixtures.filled(ChainId.SIZE, 8)))
			.post(FINGERPRINT, EntryKind.OPEN, valid), EXIT_ID);
		assertThat(random.draws, is(0));
		Session.accept(counting, open(EntryKind.OPEN, valid), EXIT_ID).orElseThrow();
		assertThat(random.draws, greaterThan(0));
	}

	@Example
	public void shouldRefuseAClientKeyOfLowOrderOfEveryKind() {
		final byte[] valid = Handshake.open(clientKey, EXIT_ID);

		for (String point : LOW_ORDER) {
			final Entry open = open(EntryKind.OPEN, withKey(valid, HexFormat.of().parseHex(point)));

			assertThat(Session.accept(exit, open, EXIT_ID), equalTo(Optional.empty()));
			Expect.assertThrows(HandshakeException.class, () -> Handshake.exitKeys(
				EphemeralKey.generate(new SecureRandom()), open, exit.writer().writer()));
		}

		assertThat(fromExit, is(empty()));
	}

	@Example
	public void shouldRefuseAClientKeyOfTheWrongSizeInsteadOfPaddingIt() {
		final byte[] valid = Handshake.open(clientKey, EXIT_ID);

		for (int size = 0; size < EphemeralKey.SIZE; size++) {
			final Entry open = open(EntryKind.OPEN, Arrays.copyOf(valid, size));

			Expect.assertThrows(HandshakeException.class, () -> Handshake.exitKeys(
				EphemeralKey.generate(new SecureRandom()), open, exit.writer().writer()));
		}
	}

	@Example
	public void shouldBindTheWriterOfTheOpenIntoTheKeys() throws HandshakeException {
		final SessionContext other = SessionFixtures.context(3, new ArrayList<>(), () -> SessionFixtures.NOW);
		final Entry replayed = other.post(FINGERPRINT, EntryKind.OPEN, Handshake.open(clientKey, EXIT_ID));
		final Session session = Session.accept(exit, replayed, EXIT_ID).orElseThrow();
		final Entry accept = fromExit.get(0);

		assertThat(session.peer(), equalTo(other.writer().writer()));
		assertRefused(clientKey, SessionFixtures.CHAIN, FINGERPRINT, client.writer().writer(), accept);
		assertThat(Handshake.clientKeys(clientKey, SessionFixtures.CHAIN, FINGERPRINT, other.writer().writer(),
			accept).confirms(Arrays.copyOfRange(accept.payload(), EphemeralKey.SIZE, Handshake.ACCEPT_SIZE)),
			is(true));
	}

	@Example
	public void shouldBindTheChainAndFingerprintOfTheOpenIntoTheKeys() throws HandshakeException {
		final ChainId otherChain = new ChainId(SessionFixtures.filled(ChainId.SIZE, 8));
		final Fingerprint otherFingerprint = SessionFixtures.fingerprint(6);
		final WriterId writer = client.writer().writer();
		final Entry open = clientOn(otherChain).post(otherFingerprint, EntryKind.OPEN,
			Handshake.open(clientKey, EXIT_ID));
		final EphemeralKey own = EphemeralKey.generate(new SecureRandom());
		final SessionKeys keys = Handshake.exitKeys(own, open, exit.writer().writer());
		final Entry accept = exit.post(otherFingerprint, EntryKind.ACCEPT, Handshake.accept(own, keys));

		assertThat(Handshake.clientKeys(clientKey, otherChain, otherFingerprint, writer, accept).confirmation(),
			equalTo(keys.confirmation()));
		assertRefused(clientKey, SessionFixtures.CHAIN, otherFingerprint, writer, accept);
		assertRefused(clientKey, otherChain, FINGERPRINT, writer, accept);
	}

	@Example
	public void shouldNeverQuoteASecretWhenItRefuses() throws HandshakeException {
		final EphemeralKey clientEnd = new EphemeralKey(SessionFixtures.filled(EphemeralKey.SIZE, 9));
		final EphemeralKey exitEnd = new EphemeralKey(SessionFixtures.filled(EphemeralKey.SIZE, 10));
		final byte[] openPayload = Handshake.open(clientEnd, EXIT_ID);
		final Entry open = open(EntryKind.OPEN, openPayload);
		final SessionKeys keys = Handshake.exitKeys(exitEnd, open, exit.writer().writer());
		final byte[] payload = Handshake.accept(exitEnd, keys);
		final List<byte[]> secrets = List.of(SessionFixtures.filled(EphemeralKey.SIZE, 9),
			SessionFixtures.filled(EphemeralKey.SIZE, 10), clientEnd.agree(exitEnd.publicKey()),
			keys.key(Direction.CLIENT_TO_EXIT), keys.key(Direction.EXIT_TO_CLIENT), keys.confirmation());
		final List<String> messages = new ArrayList<>();
		final byte[] tampered = payload.clone();

		tampered[Handshake.ACCEPT_SIZE - 1] ^= 1;

		for (byte[] refused : List.of(tampered, Arrays.copyOf(payload, Handshake.ACCEPT_SIZE - 1),
			withKey(payload, new byte[EphemeralKey.SIZE]))) {

			messages.add(Expect.assertThrows(HandshakeException.class, () -> Handshake.clientKeys(clientEnd,
				SessionFixtures.CHAIN, FINGERPRINT, client.writer().writer(),
				exit.post(FINGERPRINT, EntryKind.ACCEPT, refused))).getMessage());
		}

		messages.add(Expect.assertThrows(HandshakeException.class, () -> Handshake.exitKeys(exitEnd,
			open(EntryKind.OPEN, withKey(openPayload, new byte[EphemeralKey.SIZE])), exit.writer().writer()))
			.getMessage());
		messages.add(Expect.assertThrows(HandshakeException.class, () -> Handshake.exitKeys(exitEnd,
			open(EntryKind.OPEN, Arrays.copyOf(openPayload, EphemeralKey.SIZE - 1)), exit.writer().writer()))
			.getMessage());

		for (String message : messages) {
			for (byte[] secret : secrets) {
				assertThat(message.toLowerCase(), not(containsString(HexFormat.of().formatHex(secret))));
				assertThat(message, not(containsString(Base64.getEncoder().encodeToString(secret))));
				assertThat(message, not(containsString(Arrays.toString(secret))));
			}
		}
	}
}

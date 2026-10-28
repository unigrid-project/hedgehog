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

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters;
import org.unigrid.hedgehog.proxy.Expect;
import org.unigrid.hedgehog.proxy.session.NoiseNk.Answered;
import org.unigrid.hedgehog.proxy.session.NoiseNk.Initiation;
import org.unigrid.hedgehog.proxy.session.NoiseNk.Responder;
import org.unigrid.hedgehog.proxy.session.NoiseNk.SessionSecrets;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

public class NoiseNkTest {
	private static final HexFormat HEX = HexFormat.of();

	/* Noise_NK_25519_AESGCM_SHA256 of vectors/cacophony.txt in github.com/haskell-cryptography/cacophony */
	private static final String PROLOGUE = "4a6f686e2047616c74";
	private static final String INITIATOR_EPHEMERAL = "893e28b9dc6ca8d611ab664754b8ceb7bac5117349a4439a6b0569da977c464a";
	private static final String RESPONDER_STATIC = "4a3acbfdb163dec651dfa3194dece676d437029c62a408b4c5ea9114246e4893";
	private static final String RESPONDER_PUBLIC = "31e0303fd6418d2f8c0e78b91f22e8caed0fbe48656dcf4767e4834f701b8f62";
	private static final String RESPONDER_EPHEMERAL = "bbdb4cdbd309f1a1f2e1456967fe288cadd6f712d65dc7b7793d5e63da6b375b";
	private static final String HANDSHAKE_HASH = "f8a87aa8add4fea6e33365b89637486c2f6564546ce29d1df9ce9abf78c507d7";
	private static final String OPENING_PAYLOAD = "4c756477696720766f6e204d69736573";
	private static final String OPENING = "ca35def5ae56cec33dc2036731ab14896bc4c75dbb07a61f879f8e3afa4c7944"
		+ "75ab4d66d222457dd414bc5f296bc7b4078cc7d72af5192628b68bca7d28844b";
	private static final String ANSWER_PAYLOAD = "4d757272617920526f746862617264";
	private static final String ANSWER = "95ebc60d2b1fa672c1f46a8aa265ef51bfe38e7ccb39ec5be34069f144808843"
		+ "03c7d89310502baa8299520ba451624c3c0492e2698f8d457c32400b91fd8a";
	private static final String TO_EXIT_PAYLOAD = "462e20412e20486179656b";
	private static final String TO_EXIT = "304f70c37c93573099228016d54cb15213af94eb598d1b17df1153";
	private static final String TO_CLIENT_PAYLOAD = "4361726c204d656e676572";
	private static final String TO_CLIENT = "a1bf6c954529f29b31d8ae9f67d2c18dbd332aa1a0918690c6d80b";

	/* Hedgehog's own derivations, computed from the handshake of the vector above; the vector itself does not
	   publish them */
	private static final String ANSWER_TAG_KEY = "e302d16bbf8e90f8646879e4fd1fd0a0cf15d30ed6e48f30c1ce9cd0f34383b3";
	private static final String CLIENT_TAGS = "6ac85208f74b46f44686902fd7016428bc520f046c88c43b16da59b988411fa5";
	private static final String EXIT_TAGS = "a89b3a78a881785a12f38156e7aab7d22ac72f3bd0d90b1631fc21f2fa1d1404";

	/* u = 0, u = 1 and a point of order eight, whose shared secret is zero for every key */
	private static final List<String> LOW_ORDER_POINTS = List.of(
		"0000000000000000000000000000000000000000000000000000000000000000",
		"0100000000000000000000000000000000000000000000000000000000000000",
		"e0eb7a7c3b41b8ae1656e3faf19fc46ada098deb9c32b1fd866205165f49b800");

	/* Hands out one fixed private key, so that a handshake takes the ephemeral key of the vector */
	private static final class FixedRandom extends SecureRandom {
		private final byte[] bytes;
		private boolean drawn;

		FixedRandom(String hex) {
			bytes = HEX.parseHex(hex);
		}

		@Override
		public void nextBytes(byte[] out) {
			if (drawn || out.length != bytes.length) {
				throw new IllegalStateException("Only one draw of " + bytes.length + " bytes");
			}

			System.arraycopy(bytes, 0, out, 0, bytes.length);
			drawn = true;
		}
	}

	private record KeyPair(byte[] privateKey, byte[] publicKey) {
		static KeyPair generate(SecureRandom random) {
			final X25519PrivateKeyParameters key = new X25519PrivateKeyParameters(random);

			return new KeyPair(key.getEncoded(), key.generatePublicKey().getEncoded());
		}
	}

	/* A transport message of the Noise specification: the first nonce, 4 zero bytes and a u64 counter of 0, and
	   no associated data */
	private static byte[] transport(byte[] key, String payload) throws GeneralSecurityException {
		final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");

		cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, new byte[12]));
		return cipher.doFinal(HEX.parseHex(payload));
	}

	private static byte[] flipped(byte[] bytes, int index) {
		final byte[] copy = bytes.clone();

		copy[index] ^= 1;
		return copy;
	}

	private static byte[] withKey(byte[] message, String point) {
		final byte[] copy = message.clone();

		System.arraycopy(HEX.parseHex(point), 0, copy, 0, NoiseNk.KEY_SIZE);
		return copy;
	}

	@Example
	public void matchesTheCacophonyVector() throws Exception {
		final Initiation initiation = NoiseNk.initiate(HEX.parseHex(RESPONDER_PUBLIC), HEX.parseHex(PROLOGUE),
			new FixedRandom(INITIATOR_EPHEMERAL), HEX.parseHex(OPENING_PAYLOAD));

		assertThat(HEX.formatHex(initiation.message()), equalTo(OPENING));

		final Responder responder = NoiseNk.respond(HEX.parseHex(RESPONDER_STATIC), HEX.parseHex(PROLOGUE),
			HEX.parseHex(OPENING)).orElseThrow();
		final Answered answered = responder.answer(new FixedRandom(RESPONDER_EPHEMERAL),
			HEX.parseHex(ANSWER_PAYLOAD));

		assertThat(HEX.formatHex(answered.message()), equalTo(ANSWER));

		final SessionSecrets secrets = initiation.state().finish(HEX.parseHex(ANSWER));

		assertThat(HEX.formatHex(secrets.binding()), equalTo(HANDSHAKE_HASH));
		assertThat(HEX.formatHex(transport(secrets.clientToExit(), TO_EXIT_PAYLOAD)), equalTo(TO_EXIT));
		assertThat(HEX.formatHex(transport(secrets.exitToClient(), TO_CLIENT_PAYLOAD)), equalTo(TO_CLIENT));
		assertThat(answered.secrets(), equalTo(secrets));
		assertThat(HEX.formatHex(initiation.state().answerTagKey()), equalTo(ANSWER_TAG_KEY));
		assertThat(HEX.formatHex(responder.answerTagKey()), equalTo(ANSWER_TAG_KEY));
		assertThat(HEX.formatHex(secrets.clientTags()), equalTo(CLIENT_TAGS));
		assertThat(HEX.formatHex(secrets.exitTags()), equalTo(EXIT_TAGS));
	}

	@Example
	public void sendsTheEphemeralKeyAndATagForAnEmptyPayload() throws HandshakeException {
		final KeyPair exit = KeyPair.generate(new SecureRandom());
		final Initiation initiation = NoiseNk.initiate(exit.publicKey(), new byte[0], new SecureRandom());
		final Responder responder = NoiseNk.respond(exit.privateKey(), new byte[0], initiation.message())
			.orElseThrow();

		assertThat(initiation.message().length, is(NoiseNk.MESSAGE_SIZE));
		assertThat(responder.answer(new SecureRandom()).message().length, is(NoiseNk.MESSAGE_SIZE));
		assertThat(NoiseNk.MESSAGE_SIZE, is(48));
	}

	@Property(tries = 20)
	public void anotherKeyCannotOpenTheOpening(@ForAll @Size(max = 64) byte[] prologue) throws HandshakeException {
		final SecureRandom random = new SecureRandom();
		final KeyPair exit = KeyPair.generate(random);
		final KeyPair other = KeyPair.generate(random);
		final byte[] opening = NoiseNk.initiate(exit.publicKey(), prologue, random).message();

		assertThat(NoiseNk.respond(other.privateKey(), prologue, opening).isEmpty(), is(true));
		assertThat(NoiseNk.respond(exit.privateKey(), prologue, opening).isPresent(), is(true));
	}

	@Example
	public void anotherPrologueCannotOpenTheOpening() throws HandshakeException {
		final KeyPair exit = KeyPair.generate(new SecureRandom());
		final byte[] opening = NoiseNk.initiate(exit.publicKey(), new byte[] { 1 }, new SecureRandom()).message();

		assertThat(NoiseNk.respond(exit.privateKey(), new byte[] { 2 }, opening).isEmpty(), is(true));
		assertThat(NoiseNk.respond(exit.privateKey(), new byte[0], opening).isEmpty(), is(true));
	}

	@Property(tries = 50)
	public void aFlippedBitOfTheOpeningIsNotOpened(@ForAll @IntRange(min = 0, max = NoiseNk.MESSAGE_SIZE - 1)
		int index) throws HandshakeException {
		final KeyPair exit = KeyPair.generate(new SecureRandom());
		final byte[] opening = NoiseNk.initiate(exit.publicKey(), new byte[0], new SecureRandom()).message();

		assertThat(NoiseNk.respond(exit.privateKey(), new byte[0], flipped(opening, index)).isEmpty(), is(true));
	}

	/* Every entry of a chain may be tried as an opening, so nothing it holds makes the exit throw */
	@Property(tries = 200)
	public void anythingThatIsNotAnOpeningIsRefusedQuietly(@ForAll @Size(max = 100) byte[] message) {
		final KeyPair exit = KeyPair.generate(new SecureRandom());

		assertThat(NoiseNk.respond(exit.privateKey(), new byte[0], message).isEmpty(), is(true));
	}

	@Example
	public void anOpeningFromAPointOfLowOrderIsRefused() throws HandshakeException {
		final KeyPair exit = KeyPair.generate(new SecureRandom());
		final byte[] opening = NoiseNk.initiate(exit.publicKey(), new byte[0], new SecureRandom()).message();

		for (String point : LOW_ORDER_POINTS) {
			final byte[] forged = withKey(opening, point);

			assertThat(NoiseNk.respond(exit.privateKey(), new byte[0], forged).isEmpty(), is(true));
		}
	}

	@Example
	public void anExitKeyOfLowOrderOrAnotherSizeIsRefused() {
		for (String point : LOW_ORDER_POINTS) {
			Expect.assertThrows(HandshakeException.class,
				() -> NoiseNk.initiate(HEX.parseHex(point), new byte[0], new SecureRandom()));
		}

		Expect.assertThrows(HandshakeException.class,
			() -> NoiseNk.initiate(new byte[NoiseNk.KEY_SIZE - 1], new byte[0], new SecureRandom()));
	}

	@Property(tries = 20)
	public void bothEndsDeriveTheSameSecretsAndAnswerTag(@ForAll @Size(max = 64) byte[] prologue)
		throws HandshakeException {
		final SecureRandom random = new SecureRandom();
		final KeyPair exit = KeyPair.generate(random);
		final Initiation initiation = NoiseNk.initiate(exit.publicKey(), prologue, random);
		final Responder responder = NoiseNk.respond(exit.privateKey(), prologue, initiation.message()).orElseThrow();

		assertThat(initiation.state().answerTagKey(), equalTo(responder.answerTagKey()));
		assertThat(responder.answerTagKey().length, is(32));

		final Answered answered = responder.answer(random);
		final SessionSecrets secrets = initiation.state().finish(answered.message());

		assertThat(secrets, equalTo(answered.secrets()));

		final List<byte[]> values = List.of(secrets.clientToExit(), secrets.exitToClient(), secrets.clientTags(),
			secrets.exitTags(), secrets.binding(), responder.answerTagKey());
		final Set<String> distinct = Set.copyOf(values.stream().map(HEX::formatHex).toList());

		assertThat(distinct.size(), is(values.size()));
		values.forEach(value -> assertThat(value.length, is(32)));
	}

	@Example
	public void twoOpeningsToOneExitShareNoSecret() throws HandshakeException {
		final SecureRandom random = new SecureRandom();
		final KeyPair exit = KeyPair.generate(random);
		final Initiation first = NoiseNk.initiate(exit.publicKey(), new byte[0], random);
		final Initiation second = NoiseNk.initiate(exit.publicKey(), new byte[0], random);
		final SessionSecrets firstSecrets = first.state().finish(NoiseNk.respond(exit.privateKey(), new byte[0],
			first.message()).orElseThrow().answer(random).message());
		final SessionSecrets secondSecrets = second.state().finish(NoiseNk.respond(exit.privateKey(), new byte[0],
			second.message()).orElseThrow().answer(random).message());

		assertThat(first.state().answerTagKey(), not(equalTo(second.state().answerTagKey())));
		assertThat(firstSecrets.clientTags(), not(equalTo(secondSecrets.clientTags())));
		assertThat(firstSecrets.binding(), not(equalTo(secondSecrets.binding())));
	}

	@Example
	public void anAnswerToAnotherOpeningIsRefused() throws HandshakeException {
		final SecureRandom random = new SecureRandom();
		final KeyPair exit = KeyPair.generate(random);
		final Initiation first = NoiseNk.initiate(exit.publicKey(), new byte[0], random);
		final Initiation second = NoiseNk.initiate(exit.publicKey(), new byte[0], random);
		final Answered answered = NoiseNk.respond(exit.privateKey(), new byte[0], second.message()).orElseThrow()
			.answer(random);

		Expect.assertThrows(HandshakeException.class, () -> first.state().finish(answered.message()));
		assertThat(second.state().finish(answered.message()), equalTo(answered.secrets()));
	}

	@Property(tries = 50)
	public void aFlippedBitOfTheAnswerIsRefused(@ForAll @IntRange(min = 0, max = NoiseNk.MESSAGE_SIZE - 1)
		int index) throws HandshakeException {
		final SecureRandom random = new SecureRandom();
		final KeyPair exit = KeyPair.generate(random);
		final Initiation initiation = NoiseNk.initiate(exit.publicKey(), new byte[0], random);
		final Answered answered = NoiseNk.respond(exit.privateKey(), new byte[0], initiation.message())
			.orElseThrow().answer(random);

		/* A refused answer leaves the handshake as it was, so the real answer still finishes it */
		Expect.assertThrows(HandshakeException.class,
			() -> initiation.state().finish(flipped(answered.message(), index)));
		assertThat(initiation.state().finish(answered.message()), equalTo(answered.secrets()));
	}

	@Example
	public void anAnswerTooShortOrOfLowOrderIsRefused() throws HandshakeException {
		final SecureRandom random = new SecureRandom();
		final KeyPair exit = KeyPair.generate(random);
		final Initiation initiation = NoiseNk.initiate(exit.publicKey(), new byte[0], random);
		final byte[] answer = NoiseNk.respond(exit.privateKey(), new byte[0], initiation.message()).orElseThrow()
			.answer(random).message();

		Expect.assertThrows(HandshakeException.class,
			() -> initiation.state().finish(new byte[NoiseNk.MESSAGE_SIZE - 1]));

		for (String point : LOW_ORDER_POINTS) {
			final byte[] forged = withKey(answer, point);

			Expect.assertThrows(HandshakeException.class, () -> initiation.state().finish(forged));
		}
	}
}

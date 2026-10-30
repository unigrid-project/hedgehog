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

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Optional;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.bouncycastle.crypto.agreement.X25519Agreement;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.params.HKDFParameters;
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.X25519PublicKeyParameters;

/* The Noise handshake Noise_NK_25519_AESGCM_SHA256, as revision 34 of the Noise Protocol Framework defines it.
   The client knows the exit's static sealing key: "-> e, es" is the opening and "<- e, ee" the answer. The opening
   carries an empty payload, so it is an X25519 key followed by a 16-byte tag, MESSAGE_SIZE bytes in all; the answer
   may carry a payload between the key and the tag. respond() and finish() read the whole array as the message, so a
   caller that padded one cuts it back first.
   Two derivations are hedgehog's own: the answer tag key, from the chaining key after es, which both ends hold
   before the answer exists; and the tag keys of the session, from HKDF with the final chaining key as salt and
   the handshake hash as input. */
public final class NoiseNk {
	public static final int KEY_SIZE = X25519PublicKeyParameters.KEY_SIZE;
	public static final int TAG_SIZE = 16;
	public static final int MESSAGE_SIZE = KEY_SIZE + TAG_SIZE;

	private static final byte[] PROTOCOL_NAME = "Noise_NK_25519_AESGCM_SHA256".getBytes(StandardCharsets.US_ASCII);
	private static final byte[] ANSWER_LABEL = "hedgehog-proxy/answer".getBytes(StandardCharsets.US_ASCII);
	private static final byte[] TAGS_LABEL = "hedgehog-proxy/tags".getBytes(StandardCharsets.US_ASCII);
	private static final byte[] EMPTY = new byte[0];
	private static final int HASH_SIZE = 32;
	private static final int NONCE_SIZE = 12;

	private NoiseNk() {
		/* Static helpers only */
	}

	/* The opening to post and the state that finishes the handshake once the answer arrives */
	public record Initiation(byte[] message, Initiator state) {
		public Initiation {
			message = message.clone();
		}

		@Override
		public byte[] message() {
			return message.clone();
		}
	}

	/* What the client reads from an answer: the payload the exit put in it and the secrets of the session */
	public record Completed(byte[] payload, SessionSecrets secrets) {
		public Completed {
			payload = payload.clone();
		}

		@Override
		public byte[] payload() {
			return payload.clone();
		}

		@Override
		public boolean equals(Object other) {
			return other instanceof Completed completed && Arrays.equals(payload, completed.payload)
				&& secrets.equals(completed.secrets);
		}

		@Override
		public int hashCode() {
			return 31 * Arrays.hashCode(payload) + secrets.hashCode();
		}

		@Override
		public String toString() {
			return "Completed[" + secrets + "]";
		}
	}

	/* The answer to post and the secrets of the session it completes */
	public record Answered(byte[] message, SessionSecrets secrets) {
		public Answered {
			message = message.clone();
		}

		@Override
		public byte[] message() {
			return message.clone();
		}
	}

	/* The two keys of Split, client to exit first; the tag keys of the client and the exit, the first and second
	   half of HKDF(final chaining key, handshake hash, "hedgehog-proxy/tags"); and the handshake hash itself,
	   which binds whatever is sealed with these keys to this handshake */
	public record SessionSecrets(byte[] clientToExit, byte[] exitToClient, byte[] clientTags, byte[] exitTags,
		byte[] binding) {
		public SessionSecrets {
			clientToExit = clientToExit.clone();
			exitToClient = exitToClient.clone();
			clientTags = clientTags.clone();
			exitTags = exitTags.clone();
			binding = binding.clone();
		}

		@Override
		public byte[] clientToExit() {
			return clientToExit.clone();
		}

		@Override
		public byte[] exitToClient() {
			return exitToClient.clone();
		}

		@Override
		public byte[] clientTags() {
			return clientTags.clone();
		}

		@Override
		public byte[] exitTags() {
			return exitTags.clone();
		}

		@Override
		public byte[] binding() {
			return binding.clone();
		}

		@Override
		public boolean equals(Object other) {
			return other instanceof SessionSecrets secrets && Arrays.deepEquals(values(), secrets.values());
		}

		@Override
		public int hashCode() {
			return Arrays.deepHashCode(values());
		}

		@Override
		public String toString() {
			return "SessionSecrets[binding=" + HexFormat.of().formatHex(binding) + "]";
		}

		private byte[][] values() {
			return new byte[][] { clientToExit, exitToClient, clientTags, exitTags, binding };
		}
	}

	/* The static key of an exit made ready once for the openings it tries: the private key in the form the key
	   agreement takes, and its public half, which the handshake hash mixes in and which would otherwise cost one
	   more scalar multiplication for every entry tried. Neither half is ever handed out. */
	public static final class StaticKey {
		private final X25519PrivateKeyParameters privateKey;
		private final byte[] publicKey;

		private StaticKey(X25519PrivateKeyParameters privateKey) {
			this.privateKey = privateKey;
			publicKey = privateKey.generatePublicKey().getEncoded();
		}

		public static StaticKey of(byte[] privateKey) {
			return new StaticKey(NoiseNk.privateKey(privateKey));
		}

		@Override
		public String toString() {
			return "StaticKey[held]";
		}
	}

	/* The client's side between its opening and the answer */
	public static final class Initiator {
		private final SymmetricState state;
		private final X25519PrivateKeyParameters ephemeral;
		private final byte[] answerTagKey;

		private Initiator(SymmetricState state, X25519PrivateKeyParameters ephemeral, byte[] answerTagKey) {
			this.state = state;
			this.ephemeral = ephemeral;
			this.answerTagKey = answerTagKey;
		}

		public byte[] answerTagKey() {
			return answerTagKey.clone();
		}

		/* "<- e, ee" read from an answer whose payload is not needed */
		public SessionSecrets finish(byte[] answer) throws HandshakeException {
			return complete(answer).secrets();
		}

		/* "<- e, ee" read from the answer with its payload; a refused answer leaves the handshake as it was */
		public Completed complete(byte[] answer) throws HandshakeException {
			if (answer.length < MESSAGE_SIZE) {
				throw new HandshakeException("An answer has at least " + MESSAGE_SIZE + " bytes, found "
					+ answer.length);
			}

			final SymmetricState finishing = state.copy();
			final byte[] responderEphemeral = Arrays.copyOf(answer, KEY_SIZE);

			finishing.mixHash(responderEphemeral);
			finishing.mixKey(agree(ephemeral, responderEphemeral)
				.orElseThrow(() -> new HandshakeException("The key of the answer is a point of low order")));

			final Optional<byte[]> payload = finishing.decryptAndHash(Arrays.copyOfRange(answer, KEY_SIZE,
				answer.length));

			if (payload.isEmpty()) {
				throw new HandshakeException("The answer fails authentication");
			}

			return new Completed(payload.get(), finishing.secrets());
		}
	}

	/* The exit's side of an opening it could open */
	public static final class Responder {
		private final SymmetricState state;
		private final byte[] initiatorEphemeral;
		private final byte[] answerTagKey;

		private Responder(SymmetricState state, byte[] initiatorEphemeral, byte[] answerTagKey) {
			this.state = state;
			this.initiatorEphemeral = initiatorEphemeral;
			this.answerTagKey = answerTagKey;
		}

		public byte[] answerTagKey() {
			return answerTagKey.clone();
		}

		public Answered answer(SecureRandom random) {
			return answer(random, EMPTY);
		}

		/* "<- e, ee" written with a payload, which the handshake hides and authenticates */
		public Answered answer(SecureRandom random, byte[] payload) {
			final SymmetricState answering = state.copy();
			final X25519PrivateKeyParameters ephemeral = new X25519PrivateKeyParameters(random);
			final byte[] ephemeralPublic = ephemeral.generatePublicKey().getEncoded();

			answering.mixHash(ephemeralPublic);

			/* respond() refused a client key of low order, and only such a key gives the all-zero secret */
			answering.mixKey(agree(ephemeral, initiatorEphemeral)
				.orElseThrow(() -> new IllegalStateException("The client key is a point of low order")));

			final byte[] message = concat(ephemeralPublic, answering.encryptAndHash(payload));

			return new Answered(message, answering.secrets());
		}
	}

	public static Initiation initiate(byte[] responderStatic, byte[] prologue, SecureRandom random)
		throws HandshakeException {
		return initiate(responderStatic, prologue, random, EMPTY);
	}

	/* "-> e, es" written with a payload, after the pre-message "<- s" */
	static Initiation initiate(byte[] responderStatic, byte[] prologue, SecureRandom random, byte[] payload)
		throws HandshakeException {
		if (responderStatic.length != KEY_SIZE) {
			throw new HandshakeException("An X25519 public key is exactly " + KEY_SIZE + " bytes, found "
				+ responderStatic.length);
		}

		final SymmetricState state = SymmetricState.initialize(prologue);
		final X25519PrivateKeyParameters ephemeral = new X25519PrivateKeyParameters(random);
		final byte[] ephemeralPublic = ephemeral.generatePublicKey().getEncoded();

		state.mixHash(responderStatic);
		state.mixHash(ephemeralPublic);
		state.mixKey(agree(ephemeral, responderStatic)
			.orElseThrow(() -> new HandshakeException("The exit key is a point of low order")));

		final byte[] answerTagKey = state.answerTagKey();
		final byte[] message = concat(ephemeralPublic, state.encryptAndHash(payload));

		return new Initiation(message, new Initiator(state, ephemeral, answerTagKey));
	}

	/* "-> e, es" read from a message. Every entry of a chain may be one, so whatever does not open under this key,
	   short, of low order or failing its tag, is simply not an opening for this exit. */
	public static Optional<Responder> respond(byte[] staticPrivate, byte[] prologue, byte[] message) {
		if (message.length < MESSAGE_SIZE) {
			return Optional.empty();
		}

		return respond(StaticKey.of(staticPrivate), prologue, message);
	}

	/* The same under a key made ready once, as an exit tries every entry it cannot place */
	public static Optional<Responder> respond(StaticKey key, byte[] prologue, byte[] message) {
		if (message.length < MESSAGE_SIZE) {
			return Optional.empty();
		}

		final byte[] initiatorEphemeral = Arrays.copyOf(message, KEY_SIZE);
		final Optional<byte[]> secret = agree(key.privateKey, initiatorEphemeral);

		if (secret.isEmpty()) {
			return Optional.empty();
		}

		final SymmetricState state = SymmetricState.initialize(prologue);

		state.mixHash(key.publicKey);
		state.mixHash(initiatorEphemeral);
		state.mixKey(secret.get());

		final byte[] answerTagKey = state.answerTagKey();

		if (state.decryptAndHash(Arrays.copyOfRange(message, KEY_SIZE, message.length)).isEmpty()) {
			return Optional.empty();
		}

		return Optional.of(new Responder(state, initiatorEphemeral, answerTagKey));
	}

	/* Empty for a peer key of low order, which gives the all-zero secret whatever the own key; BouncyCastle
	   refuses to compute that secret */
	private static Optional<byte[]> agree(X25519PrivateKeyParameters own, byte[] peer) {
		final X25519Agreement agreement = new X25519Agreement();
		final byte[] secret = new byte[agreement.getAgreementSize()];

		agreement.init(own);

		try {
			agreement.calculateAgreement(new X25519PublicKeyParameters(peer, 0), secret, 0);
			return Optional.of(secret);
		} catch (IllegalStateException ex) {
			return Optional.empty();
		}
	}

	private static X25519PrivateKeyParameters privateKey(byte[] privateKey) {
		if (privateKey.length != KEY_SIZE) {
			throw new IllegalArgumentException("An X25519 private key is exactly " + KEY_SIZE + " bytes, found "
				+ privateKey.length);
		}

		return new X25519PrivateKeyParameters(privateKey, 0);
	}

	private static byte[] concat(byte[] first, byte[] second) {
		return ByteBuffer.allocate(first.length + second.length).put(first).put(second).array();
	}

	/* HKDF-SHA256 of RFC 5869, of which the HKDF of the Noise specification is the case of empty info */
	private static byte[] hkdf(byte[] salt, byte[] inputKeyMaterial, byte[] info, int size) {
		final HKDFBytesGenerator generator = new HKDFBytesGenerator(new SHA256Digest());
		final byte[] output = new byte[size];

		generator.init(new HKDFParameters(inputKeyMaterial, salt, info));
		generator.generateBytes(output, 0, size);
		return output;
	}

	/* CipherState and SymmetricState of the specification in one. Every step replaces the arrays it changes
	   rather than writing into them, so a copy may share them. */
	private static final class SymmetricState {
		private byte[] chainingKey;
		private byte[] hash;
		private byte[] key;
		private long nonce;

		private SymmetricState(byte[] chainingKey, byte[] hash, byte[] key, long nonce) {
			this.chainingKey = chainingKey;
			this.hash = hash;
			this.key = key;
			this.nonce = nonce;
		}

		/* The protocol name fits in HASHLEN bytes, so it is padded with zeros to make h, and ck starts as h */
		static SymmetricState initialize(byte[] prologue) {
			final byte[] name = Arrays.copyOf(PROTOCOL_NAME, HASH_SIZE);
			final SymmetricState state = new SymmetricState(name, name, null, 0);

			state.mixHash(prologue);
			return state;
		}

		SymmetricState copy() {
			return new SymmetricState(chainingKey, hash, key, nonce);
		}

		void mixHash(byte[] data) {
			final SHA256Digest digest = new SHA256Digest();
			final byte[] mixed = new byte[HASH_SIZE];

			digest.update(hash, 0, hash.length);
			digest.update(data, 0, data.length);
			digest.doFinal(mixed, 0);
			hash = mixed;
		}

		void mixKey(byte[] inputKeyMaterial) {
			final byte[] output = hkdf(chainingKey, inputKeyMaterial, EMPTY, 2 * HASH_SIZE);

			chainingKey = Arrays.copyOf(output, HASH_SIZE);
			key = Arrays.copyOfRange(output, HASH_SIZE, 2 * HASH_SIZE);
			nonce = 0;
		}

		byte[] encryptAndHash(byte[] plaintext) {
			try {
				final byte[] ciphertext = cipher(Cipher.ENCRYPT_MODE).doFinal(plaintext);

				nonce++;
				mixHash(ciphertext);
				return ciphertext;
			} catch (GeneralSecurityException ex) {
				throw new IllegalStateException("AES-GCM is mandatory on every Java platform", ex);
			}
		}

		/* The plaintext, or empty when the tag fails, and then nothing changes */
		Optional<byte[]> decryptAndHash(byte[] ciphertext) {
			final byte[] plaintext;

			try {
				plaintext = cipher(Cipher.DECRYPT_MODE).doFinal(ciphertext);
			} catch (AEADBadTagException ex) {
				return Optional.empty();
			} catch (GeneralSecurityException ex) {
				throw new IllegalStateException("AES-GCM is mandatory on every Java platform", ex);
			}

			nonce++;
			mixHash(ciphertext);
			return Optional.of(plaintext);
		}

		byte[] answerTagKey() {
			return hkdf(chainingKey, EMPTY, ANSWER_LABEL, HASH_SIZE);
		}

		/* Split, then the tag keys and the binding, all from the state after the last message */
		SessionSecrets secrets() {
			final byte[] keys = hkdf(chainingKey, EMPTY, EMPTY, 2 * HASH_SIZE);
			final byte[] tags = hkdf(chainingKey, hash, TAGS_LABEL, 2 * HASH_SIZE);

			return new SessionSecrets(Arrays.copyOf(keys, HASH_SIZE),
				Arrays.copyOfRange(keys, HASH_SIZE, keys.length), Arrays.copyOf(tags, HASH_SIZE),
				Arrays.copyOfRange(tags, HASH_SIZE, tags.length), hash);
		}

		/* ENCRYPT and DECRYPT of AESGCM: the nonce is 4 zero bytes and the u64 counter, the associated data h */
		private Cipher cipher(int mode) throws GeneralSecurityException {
			final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			final byte[] iv = ByteBuffer.allocate(NONCE_SIZE).putLong(NONCE_SIZE - Long.BYTES, nonce).array();

			cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_SIZE * Byte.SIZE, iv));
			cipher.updateAAD(hash);
			return cipher;
		}
	}
}

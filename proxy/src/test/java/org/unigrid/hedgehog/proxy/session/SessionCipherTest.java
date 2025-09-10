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
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import org.unigrid.hedgehog.proxy.Expect;
import org.unigrid.hedgehog.proxy.chain.ChainId;
import org.unigrid.hedgehog.proxy.chain.Fingerprint;
import org.unigrid.hedgehog.proxy.chain.WriterId;
import org.unigrid.hedgehog.proxy.session.SealException.Reason;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

public class SessionCipherTest {
	private static final ChainId CHAIN = new ChainId(filled(ChainId.SIZE, 7));
	private static final Fingerprint FINGERPRINT = new Fingerprint(filled(Fingerprint.SIZE, 9));
	private static final SessionKeys KEYS = SessionKeys.derive(filled(32, 1), new Transcript(CHAIN, FINGERPRINT,
		filled(EphemeralKey.SIZE, 2), filled(EphemeralKey.SIZE, 3), new WriterId(filled(WriterId.SIZE, 4)),
		new WriterId(filled(WriterId.SIZE, 5))));

	private static final HexFormat HEX = HexFormat.of();
	private static final byte[] MESSAGE = "hedgehog".getBytes(StandardCharsets.US_ASCII);
	/* ProxySettings caps an entry of a chain at one mebibyte, which no payload can exceed */
	private static final int MAX_ENTRY_BYTES = 1024 * 1024;
	private static final int THREADS = 8;

	/* The counters a sender can reach, then the ones past Long.MAX_VALUE - 1 that only a receiver is handed */
	private static final long[] SEALABLE_COUNTERS = { 0, 1, 0xFFFF_FFFFL, 1L << 32, 0x0102030405060708L,
		Long.MAX_VALUE - 1 };
	private static final long[] COUNTERS_BEYOND_SEALING = { Long.MAX_VALUE, Long.MIN_VALUE, -1 };

	private record Vector(Direction direction, long counter, String plaintext, String sealed) {
		byte[] message() {
			return plaintext.getBytes(StandardCharsets.US_ASCII);
		}
	}

	/* Sealed by Python's cryptography package, which is AES-GCM and HKDF-SHA256 of OpenSSL, from the keys it derived
	   itself from the transcript of KEYS. The same script reproduces the keys of SessionKeysTest and NIST GCM test
	   case 16, which shows the oracle itself is right. */
	private static final List<Vector> SEALABLE_VECTORS = List.of(
		new Vector(Direction.CLIENT_TO_EXIT, 0, "", "0000000000000000a323d09430c7647ae208854903801123"),
		new Vector(Direction.CLIENT_TO_EXIT, 0, "hedgehog",
			"000000000000000055fecdaf9677859b9f9c4ede083e6f4a2515a783cf0d915c"),
		new Vector(Direction.CLIENT_TO_EXIT, 1, "hedgehog",
			"00000000000000011705833bee28294cb58db5c760e23d77121825212e575053"),
		new Vector(Direction.CLIENT_TO_EXIT, 1L << 32, "hedgehog",
			"00000001000000008d91208f35f4845c17b7d48867fb7183bf572081f613bfd0"),
		new Vector(Direction.CLIENT_TO_EXIT, Long.MAX_VALUE - 1, "hedgehog",
			"7ffffffffffffffe51557e3f10451e38a1ecb236d1d39f2b3a31baaaa5b3d539"),
		new Vector(Direction.EXIT_TO_CLIENT, 0, "", "0000000000000000a6d7843ebdb641adfcd96b44ad50797e"),
		new Vector(Direction.EXIT_TO_CLIENT, 0, "hedgehog",
			"0000000000000000e8ef293c34937abbf1fcbefe7bc97f30b6775531301b27b3"),
		new Vector(Direction.EXIT_TO_CLIENT, 1, "hedgehog",
			"00000000000000019861260cd3a1de14733057e1991765f13cac247cf505338b"),
		new Vector(Direction.EXIT_TO_CLIENT, 1L << 32, "hedgehog",
			"000000010000000088ba416a7e5436da75d54ad90a166766b300fd7f81f4ed2e"),
		new Vector(Direction.EXIT_TO_CLIENT, Long.MAX_VALUE - 1, "hedgehog",
			"7ffffffffffffffec9a08c4f7f920739dc0bf84bd332b663fcbc5feee978bc51"));

	private static final List<Vector> VECTORS_BEYOND_SEALING = List.of(
		new Vector(Direction.CLIENT_TO_EXIT, Long.MIN_VALUE, "hedgehog",
			"80000000000000003dd7dafd3a08bb02dffb58780e77eb838ac663fabe83064c"),
		new Vector(Direction.CLIENT_TO_EXIT, -1, "hedgehog",
			"ffffffffffffffffdbf7d7546cc3a98e097d71fd83b6cf899a7412b883efd8f5"),
		new Vector(Direction.EXIT_TO_CLIENT, Long.MIN_VALUE, "hedgehog",
			"800000000000000061282715d25f37b233e48da62a3c375eee167a40d42913aa"),
		new Vector(Direction.EXIT_TO_CLIENT, -1, "hedgehog",
			"ffffffffffffffff3ea45cfa4a02349687fe74cf0a6f6c8769fc2a88b30083d1"));

	private static byte[] filled(int size, int value) {
		final byte[] bytes = new byte[size];

		Arrays.fill(bytes, (byte) value);
		return bytes;
	}

	private static SessionCipher client() {
		return new SessionCipher(KEYS, Direction.CLIENT_TO_EXIT, CHAIN, FINGERPRINT);
	}

	private static SessionCipher exit() {
		return new SessionCipher(KEYS, Direction.EXIT_TO_CLIENT, CHAIN, FINGERPRINT);
	}

	private static Reason refusal(SessionCipher cipher, byte[] sealed) {
		return Expect.assertThrows(SealException.class, () -> cipher.open(sealed)).reason();
	}

	private static SessionCipher sending(Direction direction, long sendCounter) {
		return new SessionCipher(KEYS, direction, CHAIN, FINGERPRINT, sendCounter, 0);
	}

	private static SessionCipher receiving(Direction direction, long receiveCounter) {
		return new SessionCipher(KEYS, direction.reverse(), CHAIN, FINGERPRINT, 0, receiveCounter);
	}

	private static byte[] flipped(byte[] bytes, int index) {
		final byte[] copy = bytes.clone();

		copy[index] ^= 1;
		return copy;
	}

	/* The documented nonce, laid out byte by byte apart from the code under test */
	private static byte[] documentedNonce(int directionCode, long counter) {
		final byte[] nonce = new byte[SessionCipher.NONCE_SIZE];

		nonce[0] = (byte) directionCode;

		for (int i = 0; i < Long.BYTES; i++) {
			nonce[SessionCipher.NONCE_SIZE - 1 - i] = (byte) (counter >>> (Byte.SIZE * i));
		}

		return nonce;
	}

	private static byte[] documentedAssociatedData(int directionCode) {
		return ByteBuffer.allocate(ChainId.SIZE + Fingerprint.SIZE + 1).put(CHAIN.bytes()).put(FINGERPRINT.bytes())
			.put((byte) directionCode).array();
	}

	/* Key, nonce and associated data of a sealed payload, sealed with the platform cipher alone */
	private record Parts(byte[] key, byte[] nonce, byte[] aad) {
		static Parts of(Direction direction, long counter) {
			return new Parts(KEYS.key(direction), documentedNonce(direction.code(), counter),
				documentedAssociatedData(direction.code()));
		}

		Parts withKey(byte[] other) {
			return new Parts(other, nonce, aad);
		}

		Parts withNonce(byte[] other) {
			return new Parts(key, other, aad);
		}

		Parts withAad(byte[] other) {
			return new Parts(key, nonce, other);
		}

		byte[] seal(long counter, byte[] plaintext) throws GeneralSecurityException {
			final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");

			cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
			cipher.updateAAD(aad);
			return ByteBuffer.allocate(SessionCipher.OVERHEAD + plaintext.length).putLong(counter)
				.put(cipher.doFinal(plaintext)).array();
		}
	}

	/* Sealed with the key and associated data of the direction, a counter and a tag that all agree */
	private static byte[] sealedAt(Direction direction, long counter, byte[] plaintext)
		throws GeneralSecurityException {
		return Parts.of(direction, counter).seal(counter, plaintext);
	}

	/* Every way of sealing that differs from the documented layout in one component only */
	private static List<Parts> deviations(Direction direction, long counter) {
		final Parts good = Parts.of(direction, counter);
		final int otherCode = direction.reverse().code();
		final List<Parts> deviations = new ArrayList<>(List.of(good.withKey(KEYS.key(direction.reverse())),
			good.withNonce(documentedNonce(otherCode, counter)),
			good.withAad(documentedAssociatedData(otherCode)),
			good.withAad(Arrays.copyOf(good.aad(), good.aad().length - 1)), good.withAad(new byte[0])));

		for (int i = 0; i < good.nonce().length; i++) {
			deviations.add(good.withNonce(flipped(good.nonce(), i)));
		}

		for (int i = 0; i < good.aad().length; i++) {
			deviations.add(good.withAad(flipped(good.aad(), i)));
		}

		return deviations;
	}

	/* A flip in the counter prefix is a counter of its own, a flip in ciphertext or tag fails the tag */
	private static Reason refusalOfAFlip(byte[] flipped, int index, long expectedCounter) {
		if (index >= Long.BYTES) {
			return Reason.TAMPERED;
		}

		return Long.compareUnsigned(ByteBuffer.wrap(flipped).getLong(), expectedCounter) < 0 ? Reason.REPLAYED
			: Reason.SKIPPED;
	}

	private static List<Long> counters(SessionCipher client, int seals) {
		final List<Long> counters = new ArrayList<>();

		for (int i = 0; i < seals; i++) {
			counters.add(ByteBuffer.wrap(client.seal(MESSAGE)).getLong());
		}

		return counters;
	}

	private static boolean opens(SessionCipher cipher, byte[] sealed) {
		try {
			cipher.open(sealed);
			return true;
		} catch (SealException ex) {
			return false;
		}
	}

	/* Every thread runs the task at once, released together, so that unsynchronised counters would collide */
	private static <T> List<T> inParallel(Callable<T> task) throws Exception {
		final ExecutorService pool = Executors.newFixedThreadPool(THREADS);
		final CyclicBarrier start = new CyclicBarrier(THREADS);
		final List<Future<T>> futures = new ArrayList<>();
		final List<T> results = new ArrayList<>();

		try {
			for (int i = 0; i < THREADS; i++) {
				futures.add(pool.submit(() -> {
					start.await();
					return task.call();
				}));
			}

			for (Future<T> future : futures) {
				results.add(future.get());
			}
		} finally {
			pool.shutdownNow();
		}

		return results;
	}

	@Provide
	public Arbitrary<List<byte[]>> plaintexts() {
		return Arbitraries.bytes().array(byte[].class).ofMaxSize(200).list().ofMaxSize(20);
	}

	@Property(tries = 50)
	public void shouldOpenWhatTheOtherSideSealedInOrder(@ForAll("plaintexts") List<byte[]> toExit,
		@ForAll("plaintexts") List<byte[]> toClient) throws SealException {
		final SessionCipher client = client();
		final SessionCipher exit = exit();

		for (byte[] plaintext : toExit) {
			final byte[] sealed = client.seal(plaintext);

			assertThat(sealed.length, is(plaintext.length + SessionCipher.OVERHEAD));
			assertThat(exit.open(sealed), equalTo(plaintext));
		}

		for (byte[] plaintext : toClient) {
			assertThat(client.open(exit.seal(plaintext)), equalTo(plaintext));
		}
	}

	/* Rebuilds the layout independently, in both directions: counter prefix, nonce and associated data */
	@Example
	public void shouldSealWithTheDocumentedNonceAndAssociatedData() throws Exception {
		for (Direction direction : Direction.values()) {
			final SessionCipher sender = new SessionCipher(KEYS, direction, CHAIN, FINGERPRINT);
			final Cipher reference = Cipher.getInstance("AES/GCM/NoPadding");
			final byte[] nonce = { direction.code(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1 };

			sender.seal(new byte[] { 1 });
			reference.init(Cipher.DECRYPT_MODE, new SecretKeySpec(KEYS.key(direction), "AES"),
				new GCMParameterSpec(128, nonce));
			reference.updateAAD(ByteBuffer.allocate(49).put(CHAIN.bytes()).put(FINGERPRINT.bytes())
				.put(direction.code()).array());

			final byte[] sealed = sender.seal(new byte[] { 4, 2 });

			assertThat(Arrays.copyOf(sealed, 8), equalTo(new byte[] { 0, 0, 0, 0, 0, 0, 0, 1 }));
			assertThat(reference.doFinal(sealed, 8, sealed.length - 8), equalTo(new byte[] { 4, 2 }));
		}
	}

	@Example
	public void shouldRefuseAReplay() throws SealException {
		final SessionCipher client = client();
		final SessionCipher exit = exit();
		final byte[] first = client.seal(new byte[] { 1 });

		exit.open(first);
		assertThat(refusal(exit, first), is(Reason.REPLAYED));
	}

	@Example
	public void shouldRefuseASkippedCounter() throws SealException {
		final SessionCipher client = client();
		final SessionCipher exit = exit();

		exit.open(client.seal(new byte[] { 1 }));
		client.seal(new byte[] { 2 });
		assertThat(refusal(exit, client.seal(new byte[] { 3 })), is(Reason.SKIPPED));
	}

	@Property(tries = 100)
	public void shouldRefuseEveryFlippedBit(@ForAll @Size(min = 1, max = 64) byte[] plaintext,
		@ForAll @IntRange(min = 0, max = 1000) int position, @ForAll @IntRange(min = 0, max = 7) int bit) {
		final byte[] sealed = client().seal(plaintext);
		final int index = position % sealed.length;

		sealed[index] ^= (byte) (1 << bit);
		assertThat(refusal(exit(), sealed), is(index < Long.BYTES ? Reason.SKIPPED : Reason.TAMPERED));
	}

	@Example
	public void shouldRefuseItsOwnDirectionAnotherSessionAndAnotherChain() {
		final byte[] sealed = client().seal(new byte[] { 1, 2, 3 });
		final ChainId otherChain = new ChainId(filled(ChainId.SIZE, 8));
		final Fingerprint otherFingerprint = new Fingerprint(filled(Fingerprint.SIZE, 10));

		assertThat(refusal(client(), sealed), is(Reason.TAMPERED));
		assertThat(refusal(new SessionCipher(KEYS, Direction.EXIT_TO_CLIENT, otherChain, FINGERPRINT), sealed),
			is(Reason.TAMPERED));
		assertThat(refusal(new SessionCipher(KEYS, Direction.EXIT_TO_CLIENT, CHAIN, otherFingerprint), sealed),
			is(Reason.TAMPERED));
	}

	@Example
	public void shouldRefuseAPayloadTooShortForACounterAndTag() {
		assertThat(refusal(exit(), new byte[SessionCipher.OVERHEAD - 1]), is(Reason.MALFORMED));
	}

	@Example
	public void shouldStopBeforeTheCounterWrapsAround() throws SealException {
		final SessionCipher client = new SessionCipher(KEYS, Direction.CLIENT_TO_EXIT, CHAIN, FINGERPRINT,
			Long.MAX_VALUE - 1, 0);
		final SessionCipher exit = new SessionCipher(KEYS, Direction.EXIT_TO_CLIENT, CHAIN, FINGERPRINT, 0,
			Long.MAX_VALUE - 1);

		assertThat(exit.open(client.seal(new byte[] { 1 })), equalTo(new byte[] { 1 }));
		Expect.assertThrows(IllegalStateException.class, () -> client.seal(new byte[] { 2 }));
	}

	@Example
	public void shouldKeepRefusingToSealOnceTheCounterIsUsedUp() {
		final SessionCipher client = sending(Direction.CLIENT_TO_EXIT, Long.MAX_VALUE);

		for (int i = 0; i < 3; i++) {
			Expect.assertThrows(IllegalStateException.class, () -> client.seal(MESSAGE));
		}
	}

	@Example
	public void shouldSealTheKnownAnswers() {
		for (Vector vector : SEALABLE_VECTORS) {
			final byte[] sealed = sending(vector.direction(), vector.counter()).seal(vector.message());

			assertThat(HEX.formatHex(sealed), equalTo(vector.sealed()));
		}
	}

	@Example
	public void shouldOpenTheKnownAnswers() throws SealException {
		for (Vector vector : Stream.concat(SEALABLE_VECTORS.stream(), VECTORS_BEYOND_SEALING.stream()).toList()) {
			final SessionCipher receiver = receiving(vector.direction(), vector.counter());

			assertThat(receiver.open(HEX.parseHex(vector.sealed())), equalTo(vector.message()));
		}
	}

	@Example
	public void shouldSealAtEveryEdgeOfTheCounterAsTheLayoutDocuments() throws Exception {
		for (Direction direction : Direction.values()) {
			for (long counter : SEALABLE_COUNTERS) {
				assertThat(sending(direction, counter).seal(MESSAGE),
					equalTo(sealedAt(direction, counter, MESSAGE)));
			}
		}
	}

	@Example
	public void shouldOpenAtEveryEdgeOfTheCounterAsTheLayoutDocuments() throws Exception {
		final long[] counters = LongStream.concat(Arrays.stream(SEALABLE_COUNTERS),
			Arrays.stream(COUNTERS_BEYOND_SEALING)).toArray();

		for (Direction direction : Direction.values()) {
			for (long counter : counters) {
				assertThat(receiving(direction, counter).open(sealedAt(direction, counter, MESSAGE)),
					equalTo(MESSAGE));
			}
		}
	}

	@Example
	public void shouldBindTheKeyTheNonceAndTheAssociatedDataByteByByte() throws Exception {
		for (Direction direction : Direction.values()) {
			for (long counter : new long[] { 0, 1L << 32 }) {
				for (Parts deviation : deviations(direction, counter)) {
					assertThat(refusal(receiving(direction, counter), deviation.seal(counter, MESSAGE)),
						is(Reason.TAMPERED));
				}
			}
		}
	}

	@Example
	public void shouldRefuseThePayloadsOfItsOwnDirection() {
		for (Direction direction : Direction.values()) {
			assertThat(refusal(sending(direction, 0), sending(direction, 0).seal(MESSAGE)), is(Reason.TAMPERED));
		}
	}

	@Example
	public void shouldRefuseAPayloadWhoseCounterWasRewrittenToTheExpectedOne() throws SealException {
		final SessionCipher client = client();
		final SessionCipher exit = exit();
		final byte[] first = client.seal(new byte[] { 1 });
		final byte[] second = client.seal(new byte[] { 2 });

		System.arraycopy(first, 0, second, 0, Long.BYTES);
		assertThat(refusal(exit, second), is(Reason.TAMPERED));
		assertThat(exit.open(first), equalTo(new byte[] { 1 }));
	}

	@Example
	public void shouldRefuseEveryOlderCounter() throws SealException {
		final SessionCipher client = client();
		final SessionCipher exit = exit();
		final List<byte[]> opened = new ArrayList<>();

		for (int i = 0; i < 4; i++) {
			opened.add(client.seal(new byte[] { (byte) i }));
			exit.open(opened.get(i));
		}

		for (byte[] old : opened) {
			assertThat(refusal(exit, old), is(Reason.REPLAYED));
		}

		assertThat(exit.open(client.seal(new byte[] { 9 })), equalTo(new byte[] { 9 }));
	}

	@Example
	public void shouldRefuseAnotherPayloadSealedUnderACounterAlreadyOpened() throws SealException {
		final SessionCipher exit = exit();

		exit.open(client().seal(new byte[] { 1 }));
		assertThat(refusal(exit, client().seal(new byte[] { 2 })), is(Reason.REPLAYED));
	}

	@Example
	public void shouldRefuseEveryCounterPastTheNextOneAndStillWaitForIt() throws Exception {
		for (long counter : new long[] { 1, 2, 1L << 32, Long.MAX_VALUE, Long.MIN_VALUE, -1 }) {
			final SessionCipher exit = exit();

			assertThat(refusal(exit, sealedAt(Direction.CLIENT_TO_EXIT, counter, MESSAGE)), is(Reason.SKIPPED));
			assertThat(exit.open(client().seal(MESSAGE)), equalTo(MESSAGE));
		}
	}

	@Example
	public void shouldCompareCountersAsUnsigned() throws Exception {
		final byte[] atTwoToThe63 = sealedAt(Direction.CLIENT_TO_EXIT, Long.MIN_VALUE, MESSAGE);
		final byte[] justBelow = sealedAt(Direction.CLIENT_TO_EXIT, Long.MAX_VALUE, MESSAGE);

		assertThat(refusal(receiving(Direction.CLIENT_TO_EXIT, Long.MAX_VALUE), atTwoToThe63), is(Reason.SKIPPED));
		assertThat(refusal(receiving(Direction.CLIENT_TO_EXIT, Long.MIN_VALUE), justBelow), is(Reason.REPLAYED));
	}

	@Example
	public void shouldNotConsumeACounterWhenAnOpenIsRefused() throws SealException {
		final SessionCipher client = client();
		final SessionCipher exit = exit();
		final byte[] first = client.seal(new byte[] { 1 });
		final byte[] second = client.seal(new byte[] { 2 });

		assertThat(refusal(exit, flipped(first, Long.BYTES)), is(Reason.TAMPERED));
		assertThat(refusal(exit, flipped(first, first.length - 1)), is(Reason.TAMPERED));
		assertThat(refusal(exit, second), is(Reason.SKIPPED));
		assertThat(refusal(exit, new byte[SessionCipher.OVERHEAD - 1]), is(Reason.MALFORMED));
		assertThat(exit.open(first), equalTo(new byte[] { 1 }));
		assertThat(refusal(exit, first), is(Reason.REPLAYED));
		assertThat(refusal(exit, flipped(second, Long.BYTES)), is(Reason.TAMPERED));
		assertThat(exit.open(second), equalTo(new byte[] { 2 }));
	}

	@Example
	public void shouldRefuseEveryFlippedBitOfASealedPayloadAndStayAtTheSameCounter() throws SealException {
		final long counter = 5;
		final SessionCipher exit = receiving(Direction.CLIENT_TO_EXIT, counter);
		final byte[] sealed = sending(Direction.CLIENT_TO_EXIT, counter).seal(new byte[] { 1, 2, 3 });

		for (int bit = 0; bit < sealed.length * Byte.SIZE; bit++) {
			final byte[] flipped = sealed.clone();

			flipped[bit / Byte.SIZE] ^= 1 << (bit % Byte.SIZE);
			assertThat(refusal(exit, flipped), is(refusalOfAFlip(flipped, bit / Byte.SIZE, counter)));
		}

		assertThat(exit.open(sealed), equalTo(new byte[] { 1, 2, 3 }));
	}

	@Example
	public void shouldRefuseAPayloadOfTheWrongLength() {
		final byte[] sealed = client().seal(new byte[] { 1, 2, 3 });

		for (int size : new int[] { 0, 1, Long.BYTES, SessionCipher.OVERHEAD - 1 }) {
			assertThat(refusal(exit(), new byte[size]), is(Reason.MALFORMED));
		}

		assertThat(refusal(exit(), new byte[SessionCipher.OVERHEAD]), is(Reason.TAMPERED));
		assertThat(refusal(exit(), Arrays.copyOf(sealed, SessionCipher.OVERHEAD)), is(Reason.TAMPERED));
		assertThat(refusal(exit(), Arrays.copyOf(sealed, sealed.length - 1)), is(Reason.TAMPERED));
		assertThat(refusal(exit(), Arrays.copyOf(sealed, sealed.length + 1)), is(Reason.TAMPERED));
	}

	@Example
	public void shouldSealAnEmptyPayload() throws SealException {
		final byte[] sealed = client().seal(new byte[0]);

		assertThat(sealed.length, is(SessionCipher.OVERHEAD));
		assertThat(exit().open(sealed), equalTo(new byte[0]));
	}

	@Example
	public void shouldSealAPayloadOfTheLargestEntry() throws Exception {
		final byte[] plaintext = new byte[MAX_ENTRY_BYTES];

		new Random(7).nextBytes(plaintext);

		final byte[] sealed = client().seal(plaintext);

		assertThat(Arrays.mismatch(sealed, sealedAt(Direction.CLIENT_TO_EXIT, 0, plaintext)), is(-1));
		assertThat(Arrays.mismatch(exit().open(sealed), plaintext), is(-1));
	}

	@Example
	public void shouldNeverSealTwoPayloadsUnderOneCounterFromManyThreads() throws Exception {
		final SessionCipher client = client();
		final int sealsPerThread = 500;
		final List<Long> counters = new ArrayList<>();

		for (List<Long> sealed : inParallel(() -> counters(client, sealsPerThread))) {
			counters.addAll(sealed);
		}

		Collections.sort(counters);
		assertThat(counters, equalTo(LongStream.range(0, (long) THREADS * sealsPerThread).boxed().toList()));
	}

	@Example
	public void shouldOpenAPayloadOnceWhenManyThreadsOfferIt() throws Exception {
		for (int round = 0; round < 50; round++) {
			final SessionCipher exit = exit();
			final byte[] sealed = client().seal(MESSAGE);

			assertThat(inParallel(() -> opens(exit, sealed)).stream().filter(opened -> opened).count(), is(1L));
		}
	}

}

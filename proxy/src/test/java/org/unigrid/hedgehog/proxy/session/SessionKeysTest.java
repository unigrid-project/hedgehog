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
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.Size;
import org.unigrid.hedgehog.proxy.Expect;
import org.unigrid.hedgehog.proxy.chain.ChainId;
import org.unigrid.hedgehog.proxy.chain.Fingerprint;
import org.unigrid.hedgehog.proxy.chain.WriterId;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

public class SessionKeysTest {
	private static final int CHAIN = 0;
	private static final int FINGERPRINT = 1;
	private static final int CLIENT_KEY = 2;
	private static final int EXIT_KEY = 3;
	private static final int CLIENT_WRITER = 4;
	private static final int EXIT_WRITER = 5;

	private static final byte[] SECRET = HexFormat.of()
		.parseHex("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742");

	/* RFC 7748, section 6.1, whose shared secret is SECRET */
	private static final byte[] ALICE_PRIVATE = HexFormat.of()
		.parseHex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a");
	private static final byte[] BOB_PRIVATE = HexFormat.of()
		.parseHex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb");

	/* Points of low order: u = 0, u = 1, one of order eight and u = 0 with the top bit set, which X25519 ignores */
	private static final List<String> LOW_ORDER_POINTS = List.of(
		"0000000000000000000000000000000000000000000000000000000000000000",
		"0100000000000000000000000000000000000000000000000000000000000000",
		"e0eb7a7c3b41b8ae1656e3faf19fc46ada098deb9c32b1fd866205165f49b800",
		"0000000000000000000000000000000000000000000000000000000000000080");

	private static byte[] counting(int from, int size) {
		final byte[] bytes = new byte[size];

		for (int i = 0; i < size; i++) {
			bytes[i] = (byte) (from + i);
		}

		return bytes;
	}

	/* The fields of a transcript in the order it encodes them: chain id, fingerprint, client key, exit key,
	   client writer, exit writer, each a run of its own byte values */
	private static byte[][] fields() {
		return new byte[][] { counting(0x20, ChainId.SIZE), counting(0, Fingerprint.SIZE),
			counting(0x40, EphemeralKey.SIZE), counting(0x60, EphemeralKey.SIZE), counting(0x80, WriterId.SIZE),
			counting(0xa0, WriterId.SIZE) };
	}

	private static Transcript transcript(byte[][] fields) {
		return new Transcript(new ChainId(fields[0]), new Fingerprint(fields[1]), fields[2], fields[3],
			new WriterId(fields[4]), new WriterId(fields[5]));
	}

	private static SessionKeys derive(byte[] secret, byte[][] fields) {
		return SessionKeys.derive(secret, transcript(fields));
	}

	private static List<byte[]> everyValue(SessionKeys keys) {
		return List.of(keys.key(Direction.CLIENT_TO_EXIT), keys.key(Direction.EXIT_TO_CLIENT), keys.confirmation());
	}

	private static void assertUnrelated(SessionKeys keys, SessionKeys other) {
		for (byte[] value : everyValue(other)) {
			assertThat(everyValue(keys), not(hasItem(value)));
		}

		assertThat(keys.confirms(other.confirmation()), is(false));
		assertThat(other.confirms(keys.confirmation()), is(false));
	}

	private static void assertEveryBitOfTheFieldChangesTheKeys(int field) {
		final SessionKeys keys = derive(SECRET, fields());

		for (int bit = 0; bit < fields()[field].length * Byte.SIZE; bit++) {
			final byte[][] other = fields();

			other[field][bit / Byte.SIZE] ^= 1 << (bit % Byte.SIZE);
			assertUnrelated(keys, derive(SECRET, other));
		}
	}

	private static byte[][] swapped(byte[][] fields, int first, int second) {
		final byte[] kept = fields[first];

		fields[first] = fields[second];
		fields[second] = kept;
		return fields;
	}

	/* Expected values from "openssl kdf -keylen 96 -kdfopt digest:SHA256 -kdfopt hexkey:<secret>
	   -kdfopt hexsalt:<fingerprint> -kdfopt hexinfo:<label and transcript> HKDF", matched by an HMAC
	   implementation of RFC 5869 in Python */
	@Example
	public void shouldMatchTheKnownKeys() {
		final SessionKeys keys = derive(SECRET, fields());

		assertThat(HexFormat.of().formatHex(keys.key(Direction.CLIENT_TO_EXIT)),
			equalTo("a44e861ef5092ff152a0c3af7533eccf5381fe80970a1a70f28aaa490deb2d63"));
		assertThat(HexFormat.of().formatHex(keys.key(Direction.EXIT_TO_CLIENT)),
			equalTo("af2be399634f3346f271ccd654c0cee24b594fa6c3f14cc6b0a7b121acb8c6f4"));
		assertThat(HexFormat.of().formatHex(keys.confirmation()),
			equalTo("21420e8e94a934007f875f7612d8bd26700aeec284b343669e6b0538d1f3ab11"));
	}

	@Example
	public void shouldEncodeTheTranscriptAsItsFieldsInOrder() {
		final ByteBuffer expected = ByteBuffer.allocate(176);

		for (byte[] field : fields()) {
			expected.put(field);
		}

		assertThat(transcript(fields()).bytes(), equalTo(expected.array()));
	}

	@Example
	public void shouldDeriveOtherKeysWhenAnyFieldOfTheTranscriptDiffers() {
		final SessionKeys keys = derive(SECRET, fields());

		for (int field = 0; field < fields().length; field++) {
			for (int position : new int[] { 0, fields()[field].length - 1 }) {
				final byte[][] other = fields();

				other[field][position] ^= 1;

				final SessionKeys changed = derive(SECRET, other);

				assertThat(changed.key(Direction.CLIENT_TO_EXIT),
					not(equalTo(keys.key(Direction.CLIENT_TO_EXIT))));
				assertThat(changed.key(Direction.EXIT_TO_CLIENT),
					not(equalTo(keys.key(Direction.EXIT_TO_CLIENT))));
				assertThat(changed.confirmation(), not(equalTo(keys.confirmation())));
			}
		}
	}

	@Example
	public void shouldDeriveOtherKeysWhenTheChainIdDiffers() {
		assertEveryBitOfTheFieldChangesTheKeys(CHAIN);
	}

	@Example
	public void shouldDeriveOtherKeysWhenTheFingerprintDiffers() {
		assertEveryBitOfTheFieldChangesTheKeys(FINGERPRINT);
	}

	@Example
	public void shouldDeriveOtherKeysWhenTheClientKeyDiffers() {
		assertEveryBitOfTheFieldChangesTheKeys(CLIENT_KEY);
	}

	@Example
	public void shouldDeriveOtherKeysWhenTheExitKeyDiffers() {
		assertEveryBitOfTheFieldChangesTheKeys(EXIT_KEY);
	}

	@Example
	public void shouldDeriveOtherKeysWhenTheClientWriterDiffers() {
		assertEveryBitOfTheFieldChangesTheKeys(CLIENT_WRITER);
	}

	@Example
	public void shouldDeriveOtherKeysWhenTheExitWriterDiffers() {
		assertEveryBitOfTheFieldChangesTheKeys(EXIT_WRITER);
	}

	@Example
	public void shouldDeriveOtherKeysWhenTheTwoEndsChangePlaces() {
		final SessionKeys keys = derive(SECRET, fields());
		final byte[][] writersSwapped = swapped(fields(), CLIENT_WRITER, EXIT_WRITER);
		final byte[][] keysSwapped = swapped(fields(), CLIENT_KEY, EXIT_KEY);
		final byte[][] bothSwapped = swapped(swapped(fields(), CLIENT_KEY, EXIT_KEY), CLIENT_WRITER, EXIT_WRITER);

		assertUnrelated(keys, derive(SECRET, writersSwapped));
		assertUnrelated(keys, derive(SECRET, keysSwapped));
		assertUnrelated(keys, derive(SECRET, bothSwapped));
	}

	@Property(tries = 50)
	public void shouldKeepTheDirectionsAndTheConfirmationApart(@ForAll @Size(32) byte[] secret) {
		final SessionKeys keys = derive(secret, fields());
		final byte[] otherSecret = secret.clone();

		otherSecret[0] ^= 1;
		assertThat(keys.key(Direction.CLIENT_TO_EXIT), not(equalTo(keys.key(Direction.EXIT_TO_CLIENT))));
		assertThat(keys.confirmation(), not(equalTo(keys.key(Direction.CLIENT_TO_EXIT))));
		assertThat(keys.confirmation(), not(equalTo(keys.key(Direction.EXIT_TO_CLIENT))));
		assertThat(derive(otherSecret, fields()).key(Direction.CLIENT_TO_EXIT),
			not(equalTo(keys.key(Direction.CLIENT_TO_EXIT))));
	}

	@Example
	public void shouldDeriveOtherKeysWhenOnlyTheSecretDiffers() {
		final byte[] otherSecret = SECRET.clone();

		for (int bit = 0; bit < SECRET.length * Byte.SIZE; bit++) {
			otherSecret[bit / Byte.SIZE] ^= 1 << (bit % Byte.SIZE);
			assertUnrelated(derive(SECRET, fields()), derive(otherSecret, fields()));
			otherSecret[bit / Byte.SIZE] ^= 1 << (bit % Byte.SIZE);
		}
	}

	@Example
	public void shouldHandOutCopies() {
		final SessionKeys keys = derive(SECRET, fields());
		final byte[] key = keys.key(Direction.CLIENT_TO_EXIT);
		final byte[] confirmation = keys.confirmation();

		key[0]++;
		confirmation[0]++;
		assertThat(keys.key(Direction.CLIENT_TO_EXIT), not(equalTo(key)));
		assertThat(keys.confirmation(), not(equalTo(confirmation)));
		assertThat(key.length, is(SessionKeys.KEY_SIZE));
	}

	@Example
	public void shouldHandOutCopiesOfBothKeys() {
		final SessionKeys keys = derive(SECRET, fields());

		for (Direction direction : Direction.values()) {
			final byte[] key = keys.key(direction);
			final byte[] original = key.clone();

			Arrays.fill(key, (byte) 0);
			assertThat(keys.key(direction), equalTo(original));
			assertThat(original.length, is(SessionKeys.KEY_SIZE));
		}
	}

	@Example
	public void shouldNotKeepTheArraysItWasGiven() {
		final byte[][] fields = fields();
		final Transcript transcript = transcript(fields);
		final byte[] encoded = transcript.bytes();

		Arrays.fill(fields[CLIENT_KEY], (byte) 0);
		Arrays.fill(fields[EXIT_KEY], (byte) 0);
		Arrays.fill(transcript.bytes(), (byte) 0);
		assertThat(transcript.bytes(), equalTo(encoded));
		assertThat(SessionKeys.derive(SECRET, transcript).confirmation(),
			equalTo(derive(SECRET, fields()).confirmation()));
	}

	@Example
	public void shouldKeepTheFingerprintOfTheTranscript() {
		assertThat(transcript(fields()).fingerprint(), equalTo(new Fingerprint(fields()[FINGERPRINT])));
	}

	@Example
	public void shouldRefuseEphemeralKeysOfTheWrongSize() {
		final byte[][] fields = fields();
		final ChainId chain = new ChainId(fields[0]);
		final Fingerprint fingerprint = new Fingerprint(fields[1]);
		final WriterId client = new WriterId(fields[4]);
		final WriterId exit = new WriterId(fields[5]);

		Expect.assertThrows(IllegalArgumentException.class,
			() -> new Transcript(chain, fingerprint, new byte[31], fields[3], client, exit));
		Expect.assertThrows(IllegalArgumentException.class,
			() -> new Transcript(chain, fingerprint, fields[2], new byte[33], client, exit));
	}

	@Example
	public void shouldRefuseEphemeralKeysOneByteOffTheSizeOnEitherSide() {
		final byte[][] fields = fields();
		final ChainId chain = new ChainId(fields[0]);
		final Fingerprint fingerprint = new Fingerprint(fields[1]);
		final WriterId client = new WriterId(fields[4]);
		final WriterId exit = new WriterId(fields[5]);

		for (int size : new int[] { 0, 31, 33 }) {
			Expect.assertThrows(IllegalArgumentException.class,
				() -> new Transcript(chain, fingerprint, new byte[size], fields[3], client, exit));
			Expect.assertThrows(IllegalArgumentException.class,
				() -> new Transcript(chain, fingerprint, fields[2], new byte[size], client, exit));
		}
	}

	@Example
	public void shouldReverseEachDirection() {
		assertThat(Direction.CLIENT_TO_EXIT.reverse(), is(Direction.EXIT_TO_CLIENT));
		assertThat(Direction.EXIT_TO_CLIENT.reverse(), is(Direction.CLIENT_TO_EXIT));
		assertThat(Direction.CLIENT_TO_EXIT.code(), is((byte) 1));
		assertThat(Direction.EXIT_TO_CLIENT.code(), is((byte) 2));
	}

	@Example
	public void shouldConfirmItsOwnConfirmation() {
		final SessionKeys keys = derive(SECRET, fields());

		assertThat(keys.confirms(keys.confirmation()), is(true));
		assertThat(derive(SECRET, fields()).confirms(keys.confirmation()), is(true));
	}

	@Example
	public void shouldRefuseEveryConfirmationWithAFlippedBit() {
		final SessionKeys keys = derive(SECRET, fields());

		for (int bit = 0; bit < SessionKeys.KEY_SIZE * Byte.SIZE; bit++) {
			final byte[] flipped = keys.confirmation();

			flipped[bit / Byte.SIZE] ^= 1 << (bit % Byte.SIZE);
			assertThat(keys.confirms(flipped), is(false));
		}
	}

	@Example
	public void shouldRefuseAConfirmationOfTheWrongSize() {
		final SessionKeys keys = derive(SECRET, fields());
		final byte[] confirmation = keys.confirmation();

		assertThat(keys.confirms(new byte[0]), is(false));
		assertThat(keys.confirms(Arrays.copyOf(confirmation, 1)), is(false));
		assertThat(keys.confirms(Arrays.copyOf(confirmation, SessionKeys.KEY_SIZE - 1)), is(false));
		assertThat(keys.confirms(Arrays.copyOfRange(confirmation, 1, SessionKeys.KEY_SIZE)), is(false));
		assertThat(keys.confirms(Arrays.copyOf(confirmation, SessionKeys.KEY_SIZE + 1)), is(false));
		assertThat(keys.confirms(ByteBuffer.allocate(2 * SessionKeys.KEY_SIZE).put(confirmation).put(confirmation)
			.array()), is(false));
	}

	@Example
	public void shouldRefuseAKeyOrZeroesInPlaceOfTheConfirmation() {
		final SessionKeys keys = derive(SECRET, fields());

		assertThat(keys.confirms(keys.key(Direction.CLIENT_TO_EXIT)), is(false));
		assertThat(keys.confirms(keys.key(Direction.EXIT_TO_CLIENT)), is(false));
		assertThat(keys.confirms(new byte[SessionKeys.KEY_SIZE]), is(false));
	}

	@Example
	public void shouldLeaveTheReceivedConfirmationAlone() {
		final SessionKeys keys = derive(SECRET, fields());
		final byte[] received = keys.confirmation();

		keys.confirms(received);
		assertThat(received, equalTo(keys.confirmation()));
		assertThat(keys.confirms(received), is(true));
	}

	@Example
	public void shouldGiveBothEndsOfAHandshakeTheSameKeys() throws HandshakeException {
		final EphemeralKey client = new EphemeralKey(ALICE_PRIVATE);
		final EphemeralKey exit = new EphemeralKey(BOB_PRIVATE);
		final byte[][] fields = fields();

		fields[CLIENT_KEY] = client.publicKey();
		fields[EXIT_KEY] = exit.publicKey();

		final byte[] secret = client.agree(exit.publicKey());
		final SessionKeys clientSide = SessionKeys.derive(secret, transcript(fields));
		final SessionKeys exitSide = SessionKeys.derive(exit.agree(client.publicKey()), transcript(fields));

		assertThat(secret, equalTo(SECRET));
		assertThat(clientSide.key(Direction.CLIENT_TO_EXIT), equalTo(exitSide.key(Direction.CLIENT_TO_EXIT)));
		assertThat(clientSide.key(Direction.EXIT_TO_CLIENT), equalTo(exitSide.key(Direction.EXIT_TO_CLIENT)));
		assertThat(clientSide.confirms(exitSide.confirmation()), is(true));
	}

	@Example
	public void shouldNotConfirmAnExitThatAnsweredAnotherOpen() throws HandshakeException {
		final EphemeralKey client = new EphemeralKey(ALICE_PRIVATE);
		final EphemeralKey exit = new EphemeralKey(BOB_PRIVATE);
		final byte[][] ours = fields();
		final byte[][] theirs = fields();

		ours[CLIENT_KEY] = theirs[CLIENT_KEY] = client.publicKey();
		ours[EXIT_KEY] = theirs[EXIT_KEY] = exit.publicKey();
		theirs[CHAIN][0] ^= 1;

		final SessionKeys clientSide = SessionKeys.derive(client.agree(exit.publicKey()), transcript(ours));
		final SessionKeys exitSide = SessionKeys.derive(exit.agree(client.publicKey()), transcript(theirs));

		assertThat(clientSide.confirms(exitSide.confirmation()), is(false));
	}

	@Example
	public void shouldNeverReachTheKeysWithAPointOfLowOrder() {
		final EphemeralKey client = new EphemeralKey(ALICE_PRIVATE);

		for (String point : LOW_ORDER_POINTS) {
			Expect.assertThrows(HandshakeException.class, () -> SessionKeys
				.derive(client.agree(HexFormat.of().parseHex(point)), transcript(fields())));
		}
	}
}

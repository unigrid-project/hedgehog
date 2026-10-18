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

package org.unigrid.hedgehog.s3.index;

import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.unigrid.hedgehog.s3.jqwik.Expect;
import org.unigrid.hedgehog.s3.spi.BlobId;
import static org.unigrid.hedgehog.s3.jqwik.Expect.assertThrows;

/* Blobs come back from the network and a mounted root may name any of them, so damaged bytes must fail as corrupt
   input. Sealed blocks are damaged inside their seal, which is the hard case of a forged blob with a valid
   checksum. A count that claims more elements than the input could hold has to fail before anything is sized by
   it: had the decoder allocated first, the counts used here would exceed what the JVM can allocate at all. */
public class CorruptionTest {
	private static final byte[] HUGE = {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x07};

	/* Overwrites a byte, cuts the input short, or splices in a count near the largest int */
	private record Damage(int kind, int position, byte value) {
		byte[] apply(final byte[] bytes) {
			final int at = Math.floorMod(position, bytes.length + 1);

			return switch (kind) {
				case 0 -> Arrays.copyOf(bytes, at);
				case 1 -> overwrite(bytes, at);
				default -> splice(bytes, at);
			};
		}

		private byte[] overwrite(final byte[] bytes, final int at) {
			final byte[] overwritten = bytes.clone();

			if (at < bytes.length) {
				overwritten[at] = value;
			}

			return overwritten;
		}

		private static byte[] splice(final byte[] bytes, final int at) {
			final byte[] spliced = Arrays.copyOf(bytes, bytes.length + HUGE.length);

			System.arraycopy(HUGE, 0, spliced, at, HUGE.length);
			System.arraycopy(bytes, at, spliced, at + HUGE.length, bytes.length - at);
			return spliced;
		}
	}

	@Provide
	Arbitrary<Damage> damage() {
		return Combinators.combine(Arbitraries.integers().between(0, 2), Arbitraries.integers(), Arbitraries.bytes())
			.as(Damage::new);
	}

	@Provide
	Arbitrary<List<Slot>> pages() {
		return IndexArbitraries.batches().list().ofMinSize(1).ofMaxSize(6).map(batches -> {
			final NavigableMap<byte[], Slot> slots = new TreeMap<>(Arrays::compareUnsigned);

			batches.forEach(batch -> batch.slots().forEach(slot -> slots.put(slot.key(), slot)));
			return List.copyOf(slots.values());
		});
	}

	@Provide
	Arbitrary<byte[]> records() {
		final String bucket = "bucket";
		final String key = "key";

		return Arbitraries.oneOf(IndexArbitraries.versions(bucket, key, ObjectVersion.NULL_VERSION).map(Records::version),
			IndexArbitraries.buckets(bucket).map(Records::bucket),
			IndexArbitraries.uploads(bucket, key, Keys.id(1)).map(Records::upload),
			IndexArbitraries.parts().map(Records::part));
	}

	private static byte[] resealed(final byte[] sealed, final Damage damage) throws IOException {
		return Codec.seal(damage.apply(Codec.unseal(sealed)));
	}

	private static void assertFailsCleanly(final Expect.Action decode) {
		try {
			decode.run();
		} catch (IOException expected) {
			/* Damage the decoder noticed */
		} catch (Throwable other) {
			throw new AssertionError("Damaged input escaped as " + other, other);
		}
	}

	@Property
	public void damagedPagesFailCleanly(@ForAll("pages") final List<Slot> slots, @ForAll("damage") final Damage damage)
		throws IOException {

		final byte[] sealed = resealed(Page.encode(slots), damage);
		assertFailsCleanly(() -> Page.decode(sealed));
	}

	@Property
	public void damagedRunIndicesFailCleanly(@ForAll("pages") final List<Slot> slots,
		@ForAll("damage") final Damage damage) throws IOException {

		final Bloom bloom = Bloom.forKeys(slots.size(), 10);

		slots.forEach(slot -> bloom.add(slot.key()));

		final RunIndex index = new RunIndex(slots.stream().map(Slot::key).toArray(byte[][]::new),
			new long[slots.size()], new int[slots.size()], bloom);
		final byte[] sealed = resealed(Codec.seal(Codec.encode(index::write)), damage);

		assertFailsCleanly(() -> RunIndex.decode(sealed));
	}

	@Property
	public void damagedManifestsFailCleanly(@ForAll("pages") final List<Slot> slots,
		@ForAll("damage") final Damage damage) throws IOException {

		final List<RunInfo> runs = slots.stream().map(slot -> new RunInfo(new BlobId("run"), 1, 0, 1, slot.key(),
			slot.key())).toList();
		final byte[] sealed = resealed(new Manifest(1, runs, List.of(new Manifest.Pending(new BlobId("old"),
			Instant.EPOCH))).encode(), damage);

		assertFailsCleanly(() -> Manifest.decode(sealed));
	}

	@Property
	public void damagedRecordsFailCleanly(@ForAll("records") final byte[] record, @ForAll("damage") final Damage damage) {
		final byte[] damaged = damage.apply(record);

		assertFailsCleanly(() -> Records.version("bucket", "key", 1, damaged));
		assertFailsCleanly(() -> Records.bucket("bucket", damaged));
		assertFailsCleanly(() -> Records.upload("bucket", "key", 1, damaged));
		assertFailsCleanly(() -> Records.part(damaged));
	}

	@Property
	public void damagedKeysFailCleanly(@ForAll("damage") final Damage damage) {
		final byte[] damaged = damage.apply(Keys.version("bucket", "a/b", 1));
		assertFailsCleanly(() -> Keys.parse(damaged));
	}

	@Example
	public void refusesHugeCountsBeforeAllocatingForThem() {
		final byte[] count = Codec.encode(output -> Codec.writeNumber(output, Integer.MAX_VALUE));
		final byte[] bloom = Codec.encode(output -> {
			output.writeByte(7);
			Codec.writeNumber(output, Integer.MAX_VALUE);
		});
		final byte[] index = Codec.encode(output -> {
			output.writeByte(1);
			Codec.writeNumber(output, Integer.MAX_VALUE);
		});

		assertThrows(CorruptIndexException.class, () -> Page.decode(Codec.seal(count)));
		assertThrows(CorruptIndexException.class, () -> Codec.decode(bloom, Bloom::read));
		assertThrows(CorruptIndexException.class, () -> RunIndex.decode(Codec.seal(index)));
		assertThrows(CorruptIndexException.class, () -> Codec.decode(count, Codec::readBytes));
		assertThrows(CorruptIndexException.class, () -> Codec.decode(count, Codec::readMap));
		assertThrows(CorruptIndexException.class, () -> Codec.decode(count, input -> Codec.readList(input,
			Codec::readString)));
	}

	@Example
	public void refusesMalformedBloomFilters() {
		for (int probes : new int[] {0, 31, 255}) {
			assertThrows(CorruptIndexException.class, () -> Codec.decode(Codec.encode(output -> {
				output.writeByte(probes);
				Codec.writeNumber(output, 1);
				output.writeLong(0);
			}), Bloom::read));
		}

		assertThrows(CorruptIndexException.class, () -> Codec.decode(Codec.encode(output -> {
			output.writeByte(7);
			Codec.writeNumber(output, 0);
		}), Bloom::read));
	}

	@Example
	public void refusesAKeySharingMoreThanTheKeyBeforeIt() {
		final byte[] page = Codec.encode(output -> {
			Codec.writeNumber(output, 2);
			Codec.writeNumber(output, 0);
			Codec.writeBytes(output, new byte[] {1});
			output.writeBoolean(false);
			Codec.writeNumber(output, 2);
			Codec.writeBytes(output, new byte[] {2});
			output.writeBoolean(false);
		});

		assertThrows(CorruptIndexException.class, () -> Page.decode(Codec.seal(page)));
	}
}

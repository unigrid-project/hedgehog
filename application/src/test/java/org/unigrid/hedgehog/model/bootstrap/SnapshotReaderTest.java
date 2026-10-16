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

package org.unigrid.hedgehog.model.bootstrap;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import lombok.SneakyThrows;
import mockit.Mock;
import mockit.MockUp;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.crypto.Signature;

public class SnapshotReaderTest {
	@Example
	@SneakyThrows
	public void shouldRejectACorruptedMagic() {
		final Path path = validSnapshot();
		final byte[] bytes = Files.readAllBytes(path);

		bytes[0] ^= 0x01;
		Files.write(path, bytes);

		try {
			SnapshotReader.open(path);
			throw new AssertionError("A snapshot with a corrupted magic was accepted");

		} catch (IOException expected) {
			assertThat(expected.getMessage(), equalTo("Not a legacy chain snapshot"));
		}
	}

	@Example
	@SneakyThrows
	public void shouldRejectAWrongVersion() {
		final Path path = validSnapshot();
		final byte[] bytes = Files.readAllBytes(path);

		ByteBuffer.wrap(bytes).putInt(SnapshotFormat.VERSION_OFFSET, 1);
		Files.write(path, bytes);

		try {
			SnapshotReader.open(path);
			throw new AssertionError("A snapshot with version 1 was accepted");

		} catch (IOException expected) {
			assertThat(expected.getMessage(), equalTo("Snapshot is format version 1, this build reads "
				+ SnapshotFormat.VERSION + "; re-import the bootstrap"));
		}
	}

	@Example
	@SneakyThrows
	public void shouldOpenASnapshotSignedByATrustedKey() {
		final Path path = validSnapshot();

		SnapshotSignature.signAndAppend(path, trustedKey().getPrivateKey());
		assertThat(SnapshotReader.open(path).getInfo().getSignature(), equalTo(SignatureStatus.SIGNED));
	}

	@Example
	@SneakyThrows
	public void shouldRefuseASnapshotChangedAfterSigning() {
		final Path path = validSnapshot();

		SnapshotSignature.signAndAppend(path, trustedKey().getPrivateKey());

		final byte[] bytes = Files.readAllBytes(path);

		bytes[(int) SnapshotDigest.contentLengthOf(path) - 1] ^= 0x01;
		Files.write(path, bytes);

		SyntheticSnapshots.assertRefused(() -> SnapshotReader.open(path), "does not verify against any trusted key");
	}

	@Example
	@SneakyThrows
	public void shouldRefuseASnapshotSignedByAnUntrustedKey() {
		final Path path = validSnapshot();

		SnapshotSignature.signAndAppend(path, new Signature().getPrivateKey());
		SyntheticSnapshots.assertRefused(() -> SnapshotReader.open(path), "does not verify against any trusted key");
	}

	@Provide
	public Arbitrary<Map<String, Long>> provideHoldings() {
		return AddressSnapshots.holdings(Long.MAX_VALUE);
	}

	@Provide
	public Arbitrary<String> provideHash() {
		return AddressSnapshots.hashes();
	}

	@Property(tries = 50)
	@SneakyThrows
	public void shouldAnswerTheBalanceOfAHashLikeTheDecimalPath(@ForAll("provideHoldings") Map<String, Long> holdings,
		@ForAll("provideHash") String probe) {

		final SnapshotReader reader = SnapshotReader.open(AddressSnapshots.write(holdings));

		holdings.forEach((hash, units) -> {
			assertThat(unitsOf(reader, hash), equalTo(units));
			assertThat(decimalUnitsOf(reader, hash), equalTo(units));
		});
		assertThat(unitsOf(reader, probe), equalTo(holdings.getOrDefault(probe, 0L)));
		assertThat(decimalUnitsOf(reader, probe), equalTo(holdings.getOrDefault(probe, 0L)));
	}

	@Example
	@SneakyThrows
	public void shouldFindTheFirstAndTheLastHashAndMissThoseBetween() {
		final SnapshotReader reader = SnapshotReader.open(AddressSnapshots.write(
			Map.of(hash("", "00", ""), 1L, hash("80", "00", ""), 2L, hash("", "ff", ""), 3L)));

		assertThat(unitsOf(reader, hash("", "00", "")), equalTo(1L));
		assertThat(unitsOf(reader, hash("80", "00", "")), equalTo(2L));
		assertThat(unitsOf(reader, hash("", "ff", "")), equalTo(3L));
		assertThat(unitsOf(reader, hash("", "00", "01")), equalTo(0L));
		assertThat(unitsOf(reader, hash("7f", "ff", "")), equalTo(0L));
		assertThat(unitsOf(reader, hash("80", "00", "01")), equalTo(0L));
		assertThat(unitsOf(reader, hash("", "ff", "fe")), equalTo(0L));
	}

	@Example
	@SneakyThrows
	public void shouldAnswerZeroWhenTheSnapshotHoldsNoAddress() {
		final SnapshotReader reader = SnapshotReader.open(AddressSnapshots.write(Map.of()));

		assertThat(reader.balanceUnits(new byte[Hashing.ADDRESS_HASH_SIZE]), equalTo(0L));
		assertThat(reader.totalBalance(), equalTo(0L));
	}

	@Property(tries = 50)
	@SneakyThrows
	public void shouldReadTheTipFromTheBlockTimeTable(@ForAll @IntRange(min = 0, max = 60) int height,
		@ForAll @LongRange(min = 0, max = 0xFFFFFFFFL) long seconds) {

		final SnapshotReader reader = SnapshotReader.open(AddressSnapshots.write(Map.of(), height, (int) seconds));

		assertThat(reader.tipHeight(), equalTo(height));
		assertThat(reader.tipTimeMillis(), equalTo(seconds * 1000));
	}

	@Example
	@SneakyThrows
	public void shouldAgreeWithTheInfoOnTheTipOfABuiltSnapshot() {
		final SnapshotReader reader = SnapshotReader.open(validSnapshot());
		final SnapshotInfo info = reader.getInfo();

		assertThat(reader.tipHeight(), equalTo(info.getTipHeight()));
		assertThat(reader.tipTimeMillis(), equalTo(info.getBuilt().toEpochMilli()));
	}

	/* A 20 byte hash in hex: the head, then the fill byte repeated up to the tail */
	private static String hash(String head, String fill, String tail) {
		return head + fill.repeat(Hashing.ADDRESS_HASH_SIZE - (head.length() + tail.length()) / 2) + tail;
	}

	private static long unitsOf(SnapshotReader reader, String hash) {
		return reader.balanceUnits(AddressSnapshots.bytesOf(hash));
	}

	private static long decimalUnitsOf(SnapshotReader reader, String hash) {
		return reader.balanceOf(AddressSnapshots.addressOf(hash)).map(AddressBalance::getBalance)
			.map(balance -> balance.movePointRight(Coin.DECIMALS).longValueExact()).orElse(0L);
	}

	@SneakyThrows
	private static Signature trustedKey() {
		final Signature signature = new Signature();

		new MockUp<NetworkKey>() {
			@Mock public /* static */ String[] getPublicKeys() {
				return new String[] { signature.getPublicKey() };
			}
		};

		return signature;
	}

	@SneakyThrows
	private static Path validSnapshot() {
		final Path path = Files.createTempFile("hhg-snapshot-", ".dat");

		path.toFile().deleteOnExit();
		SnapshotBuilder.build(BlockFixture.directory(), path);
		return path;
	}
}

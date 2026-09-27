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

package org.unigrid.hedgehog.model.network.codec;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import io.netty.channel.ChannelHandlerContext;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.stream.IntStream;
import lombok.SneakyThrows;
import mockit.Mock;
import mockit.MockUp;
import mockit.Mocked;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.ShortRange;
import net.jqwik.api.lifecycle.BeforeProperty;
import net.jqwik.api.lifecycle.BeforeTry;
import org.apache.commons.lang3.SerializationUtils;
import org.unigrid.hedgehog.model.Address;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.crypto.Signature;
import org.unigrid.hedgehog.model.network.packet.PublishSpork;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.MintStorage;
import org.unigrid.hedgehog.model.spork.MintStorage.SporkData.Location;
import org.unigrid.hedgehog.model.spork.MintSupply;
import org.unigrid.hedgehog.model.spork.StatisticsPubKey;
import org.unigrid.hedgehog.model.spork.VestingStorage;
import org.unigrid.hedgehog.model.spork.VestingStorage.SporkData.Vesting;

/* Equal content must sign equal bytes however its objects were built, and keep verifying wherever it travels */
public class SporkSignableTest extends BaseCodecTest<PublishSpork> {
	private static final Instant TIME = Instant.parse("2026-01-01T00:00:00.123456789Z");
	private static final int MAX_ENTRIES = 30;
	private static final int SHRUNK_EXTRA = 100;
	private static final int MAX_ADDRESSES = 4;

	private static Signature proposer;
	private static Signature cosigner;

	@SneakyThrows
	@BeforeProperty
	public void createKeys() {
		proposer = new Signature();
		cosigner = new Signature();
	}

	@BeforeTry
	public void mockNetworkKeys() {
		new MockUp<NetworkKey>() {
			@Mock public /* static */ String[] getPublicKeys() {
				return new String[] { proposer.getPublicKey(), cosigner.getPublicKey() };
			}

			@Mock public /* static */ String[] getRetiredPublicKeys() {
				return new String[0];
			}
		};
	}

	/* The same entries in maps with different capacities, insertion orders, histories and object sharing */
	private static <K, V> List<Map<K, V>> variants(Map<K, V> entries, UnaryOperator<K> unshareKey,
		UnaryOperator<V> unshareValue, Function<Integer, K> filler) {

		final List<Map.Entry<K, V>> reversed = new ArrayList<>(entries.entrySet());
		final Map<K, V> presized = new HashMap<>(MAX_ENTRIES * 4);
		final Map<K, V> shrunk = new HashMap<>();
		final Map<K, V> linked = new LinkedHashMap<>();
		final Map<K, V> unshared = new HashMap<>();

		Collections.reverse(reversed);
		presized.putAll(entries);
		IntStream.range(0, SHRUNK_EXTRA).forEach(i -> shrunk.put(filler.apply(i), entries.values().iterator().next()));
		shrunk.putAll(entries);
		IntStream.range(0, SHRUNK_EXTRA).forEach(i -> shrunk.remove(filler.apply(i)));
		reversed.forEach(entry -> linked.put(entry.getKey(), entry.getValue()));
		entries.forEach((key, value) -> unshared.put(unshareKey.apply(key), unshareValue.apply(value)));

		return List.of(new HashMap<>(entries), presized, shrunk, linked, new TreeMap<>(entries), unshared);
	}

	@SneakyThrows
	private static <T extends GridSpork> T signed(T spork) {
		spork.archive();
		spork.setTimeStamp(TIME);
		spork.sign(proposer.getPrivateKey());
		spork.cosign(cosigner.getPrivateKey());
		return spork;
	}

	@SneakyThrows
	private GridSpork overTheWire(GridSpork spork, ChannelHandlerContext context) {
		return encodeDecode(PublishSpork.builder().gridSpork(spork).build(), new PublishSporkEncoder(),
			new PublishSporkDecoder(), context
		).getGridSpork();
	}

	/* Equal bytes verify alike, so only the first spork's signatures are checked, and only after the wire */
	private void assertSignedAlike(List<? extends GridSpork> sporks, ChannelHandlerContext context) {
		final byte[] signable = sporks.getFirst().getSignable();

		for (GridSpork spork : sporks) {
			assertThat(spork.getSignable(), equalTo(signable));
			assertThat(SerializationUtils.clone(spork).getSignable(), equalTo(signable));
			assertThat(overTheWire(spork, context).getSignable(), equalTo(signable));
		}

		assertThat(overTheWire(sporks.getFirst(), context).isDoublySigned(), is(true));
	}

	private static Address unshared(Address address) {
		return new Address(new String(address.getWif()));
	}

	private static BigDecimal unshared(BigDecimal amount) {
		return new BigDecimal(amount.unscaledValue(), amount.scale());
	}

	/* Few addresses, so several entries share one Address and its String */
	private static Arbitrary<Address> addresses() {
		return Arbitraries.strings().alpha().numeric().ofLength(34).list().ofMinSize(1).ofMaxSize(MAX_ADDRESSES)
			.flatMap(wifs -> Arbitraries.of(wifs.stream().map(Address::new).toList()));
	}

	private static Arbitrary<BigDecimal> amounts() {
		return Arbitraries.of(BigDecimal.ONE, BigDecimal.TEN, new BigDecimal("0.00000001"));
	}

	@Provide
	public Arbitrary<Map<Location, BigDecimal>> provideMints() {
		final Arbitrary<Location> locations = addresses().flatMap(address -> Arbitraries.integers()
			.between(0, 5_000_000).map(height -> new Location(address, height))
		);

		return Arbitraries.maps(locations, amounts()).ofMinSize(1).ofMaxSize(MAX_ENTRIES);
	}

	@Provide
	public Arbitrary<Map<Address, Vesting>> provideVestings() {
		final Arbitrary<Vesting> vestings = amounts().flatMap(amount -> Arbitraries.integers().between(1, 100)
			.map(parts -> new Vesting(amount, TIME, Duration.ofDays(parts).plusNanos(parts), parts))
		);

		return Arbitraries.maps(Arbitraries.strings().alpha().numeric().ofLength(34).map(Address::new), vestings)
			.ofMinSize(1).ofMaxSize(MAX_ENTRIES);
	}

	@Property(tries = 10)
	public void shouldSignEqualMintsAlike(@ForAll("provideMints") Map<Location, BigDecimal> mints,
		@Mocked ChannelHandlerContext context) {

		assertSignedAlike(variants(mints, location -> new Location(unshared(location.getAddress()),
			location.getHeight()), SporkSignableTest::unshared, i -> new Location(new Address("filler" + i), i))
			.stream().map(map -> {
				final MintStorage spork = new MintStorage();

				spork.<MintStorage.SporkData>getData().setMints(map);
				return signed(spork);
			}).toList(), context
		);
	}

	@Property(tries = 10)
	public void shouldSignEqualVestingsAlike(@ForAll("provideVestings") Map<Address, Vesting> vestings,
		@Mocked ChannelHandlerContext context) {

		assertSignedAlike(variants(vestings, SporkSignableTest::unshared, vesting -> new Vesting(
			unshared(vesting.getAmount()), Instant.ofEpochSecond(TIME.getEpochSecond(), TIME.getNano()),
			Duration.ofSeconds(vesting.getDuration().getSeconds(), vesting.getDuration().getNano()),
			vesting.getParts()), i -> new Address("filler" + i)).stream().map(map -> {

				final VestingStorage spork = new VestingStorage();

				spork.<VestingStorage.SporkData>getData().setVestingAddresses(map);
				return signed(spork);
			}).toList(), context
		);
	}

	@Property(tries = 20)
	public void shouldSignTheSupplyAndStatisticsKeyAlike(@ForAll("provideMints") Map<Location, BigDecimal> any,
		@Mocked ChannelHandlerContext context) {

		final BigDecimal supply = any.values().iterator().next();
		final String key = any.keySet().iterator().next().getAddress().getWif();

		assertSignedAlike(List.of(supply, unshared(supply)).stream().map(maxSupply -> {
			final MintSupply spork = new MintSupply();

			spork.<MintSupply.SporkData>getData().setMaxSupply(maxSupply);
			return signed(spork);
		}).toList(), context);

		assertSignedAlike(List.of(key, new String(key)).stream().map(publicKey -> {
			final StatisticsPubKey spork = new StatisticsPubKey();

			spork.<StatisticsPubKey.SporkData>getData().setPublicKey(publicKey);
			return signed(spork);
		}).toList(), context);
	}

	@Property(tries = 20)
	public void shouldNotVerifyWithChangedFlags(@ForAll("provideMints") Map<Location, BigDecimal> mints,
		@ForAll @ShortRange(min = Short.MIN_VALUE) short change) {

		Assume.that(change != 0);

		final MintStorage spork = new MintStorage();

		spork.<MintStorage.SporkData>getData().setMints(mints);
		signed(spork);
		spork.setFlags((short) (spork.getFlags() ^ change));

		assertThat(spork.isValidSignature(), is(false));
		assertThat(spork.isDoublySigned(), is(false));
	}
}

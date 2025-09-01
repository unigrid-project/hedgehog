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

package org.unigrid.hedgehog.proxy.group;

import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import org.unigrid.hedgehog.proxy.Expect;
import org.unigrid.hedgehog.proxy.chain.ChainId;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

public class ChainIdsTest {
	private static final int LARGEST_CHAINS_PER_GROUP = 65_535;

	private static ChainId hex(String hex) {
		return new ChainId(HexFormat.of().parseHex(hex));
	}

	/* Expected values from sha256sum and openssl dgst -sha256 over the same bytes written with printf */
	@Example
	public void shouldMatchTheKnownChainIds() {
		assertThat(ChainIds.of(new Epoch(0), 0, 0),
			equalTo(hex("0a2d6b8f89b140c39e4b0d5dbcb4b7430ff827927e125ac2576f1916dc4d8523")));
		assertThat(ChainIds.of(new Epoch(486_111), 1, 3),
			equalTo(hex("2166c5ae00909190715d4e9103662d2be52923f68ae07fa12f9d94f149b215e6")));
		assertThat(ChainIds.of(new Epoch(1), 2, 0),
			equalTo(hex("52a4cdf3080792c666b03bb7418c4753b335a5cd77536cb4834e33c35cf28c4d")));
	}

	/* The 21 bytes "chain", the epoch as u64, the group as u32 and the index as u32, all big-endian, hashed by
	   Python's hashlib (struct.pack(">QII")) and cross-checked with sha256sum and openssl for several of them */
	@Example
	public void shouldMatchTheKnownChainIdsOfEachFieldAtItsBoundaries() {
		assertThat(ChainIds.of(new Epoch(0), 0, 1),
			equalTo(hex("e26900a5bac52bdfeee60455f0f1e428005af99fa90dbea8b0fb6aa4861a396f")));
		assertThat(ChainIds.of(new Epoch(0), 1, 0),
			equalTo(hex("e897b257e6d0efcb326b05719eeda6eca02e779e9c8624dd02494cd49215c30c")));
		assertThat(ChainIds.of(new Epoch(0), 65_535, 0),
			equalTo(hex("3f56256863299484c17af1e22663ebbf7e2bd07e53b02cff6c28ea83d5c6f871")));
		assertThat(ChainIds.of(new Epoch(0), 65_536, 0),
			equalTo(hex("f83d9d8bc53c61787813677c0a4c5f02b7d4e106542980dd962059d217d5bf93")));
		assertThat(ChainIds.of(new Epoch(0), 0, 65_535),
			equalTo(hex("b8003d705714b75fbd9d5cffa902dd3655694561771e2588016353cbbc39ffa8")));
		assertThat(ChainIds.of(new Epoch(0), 0, 65_536),
			equalTo(hex("48b616e20db2b5b1937c3498dcab6e73c9bf90af6f1468f4947f8c6f80dbe67d")));
		assertThat(ChainIds.of(new Epoch(0), Integer.MAX_VALUE, 0),
			equalTo(hex("e0d9e4cae4dcc8f7cdadad749f89a872fc9e00462fbdaa10340187f4d5e0e8a7")));
		assertThat(ChainIds.of(new Epoch(0), 0, Integer.MAX_VALUE),
			equalTo(hex("1e9ad808158ce109bb4088eed912d0ce3bef076b3bf678e013bcbc973bce5f92")));
		assertThat(ChainIds.of(new Epoch(0), Integer.MAX_VALUE, Integer.MAX_VALUE),
			equalTo(hex("3168cfbe0b80f703124aa2eea23d29f1bb50480748d006943cab186fc50b1160")));
		assertThat(ChainIds.of(new Epoch(Long.MAX_VALUE), 0, 0),
			equalTo(hex("f1ac27209eb98dde036107d9fc8fbaba5cc0f5b5c0cae1e6f6232535946900a6")));
		assertThat(ChainIds.of(new Epoch(Long.MAX_VALUE), Integer.MAX_VALUE, Integer.MAX_VALUE),
			equalTo(hex("89c8da2280f4024d9fdb3e2d8804cce59e229f7a93d0f7da488a7bbf18a30132")));
	}

	@Property(tries = 50)
	public void shouldListDistinctChainsOfAGroupInIndexOrder(@ForAll @LongRange(min = 0, max = 1L << 40) long epoch,
		@ForAll @IntRange(min = 0, max = 100) int group, @ForAll @IntRange(min = 0, max = 32) int chainsPerGroup) {
		final List<ChainId> chains = ChainIds.ofGroup(new Epoch(epoch), group, chainsPerGroup);

		assertThat(chains.size(), is(chainsPerGroup));
		assertThat(new HashSet<>(chains).size(), is(chainsPerGroup));

		for (int index = 0; index < chainsPerGroup; index++) {
			assertThat(chains.get(index), equalTo(ChainIds.of(new Epoch(epoch), group, index)));
		}
	}

	@Example
	public void shouldListTheKnownChainsOfAGroupInIndexOrder() {
		assertThat(ChainIds.ofGroup(new Epoch(486_111), 1, 4), equalTo(List.of(
			hex("ad123a904fc0d67a030675a6485ad7040c9dd280f7aca680a53d9657e43ca3e2"),
			hex("4a9024b13b8fa34201fa194c802bdc16e0b40e7d37fe7c0bd5f5cd5ab33385a3"),
			hex("d5fb16f7d1bd2065d9284f63f5383a4342ca81484fb38484e075545be4bf8ba1"),
			hex("2166c5ae00909190715d4e9103662d2be52923f68ae07fa12f9d94f149b215e6"))));
	}

	@Example
	public void shouldListNoChainsForAnEmptyGroup() {
		assertThat(ChainIds.ofGroup(new Epoch(486_111), 1, 0), equalTo(List.of()));
	}

	@Example
	public void shouldListEveryChainOfTheLargestGroupOnce() {
		final List<ChainId> chains = ChainIds.ofGroup(new Epoch(7), 3, LARGEST_CHAINS_PER_GROUP);

		assertThat(chains.size(), is(LARGEST_CHAINS_PER_GROUP));
		assertThat(new HashSet<>(chains).size(), is(LARGEST_CHAINS_PER_GROUP));
		assertThat(chains.get(0), equalTo(ChainIds.of(new Epoch(7), 3, 0)));
		assertThat(chains.get(LARGEST_CHAINS_PER_GROUP - 1),
			equalTo(ChainIds.of(new Epoch(7), 3, LARGEST_CHAINS_PER_GROUP - 1)));
	}

	@Example
	public void shouldTellEpochsGroupsAndIndexesApart() {
		final ChainId chain = ChainIds.of(new Epoch(5), 1, 2);

		assertThat(new HashSet<>(List.of(chain, ChainIds.of(new Epoch(6), 1, 2), ChainIds.of(new Epoch(5), 2, 2),
			ChainIds.of(new Epoch(5), 1, 3), ChainIds.of(new Epoch(5), 2, 1))).size(), is(5));
	}

	@Example
	public void shouldTellASwappedGroupAndIndexApartAtTheLargestValues() {
		assertThat(ChainIds.of(new Epoch(0), Integer.MAX_VALUE, 0), not(equalTo(ChainIds.of(new Epoch(0), 0,
			Integer.MAX_VALUE))));
		assertThat(ChainIds.of(new Epoch(0), 65_536, 0), not(equalTo(ChainIds.of(new Epoch(0), 0, 65_536))));
	}

	@Property(tries = 100)
	public void shouldChangeTheIdWhenAnyOneFieldChanges(
		@ForAll @LongRange(min = 0, max = Long.MAX_VALUE - 1) long epoch,
		@ForAll @IntRange(min = 0, max = Integer.MAX_VALUE - 1) int group,
		@ForAll @IntRange(min = 0, max = Integer.MAX_VALUE - 1) int index) {
		final ChainId chain = ChainIds.of(new Epoch(epoch), group, index);

		assertThat(ChainIds.of(new Epoch(epoch + 1), group, index), not(equalTo(chain)));
		assertThat(ChainIds.of(new Epoch(epoch), group + 1, index), not(equalTo(chain)));
		assertThat(ChainIds.of(new Epoch(epoch), group, index + 1), not(equalTo(chain)));
	}

	@Example
	public void shouldRefuseANegativeGroupOrIndex() {
		Expect.assertThrows(IllegalArgumentException.class, () -> ChainIds.of(new Epoch(1), -1, 0));
		Expect.assertThrows(IllegalArgumentException.class, () -> ChainIds.of(new Epoch(1), 0, -1));
	}

	@Example
	public void shouldRefuseTheMostNegativeGroupAndIndexAndBothTogether() {
		Expect.assertThrows(IllegalArgumentException.class,
			() -> ChainIds.of(new Epoch(1), Integer.MIN_VALUE, 0));
		Expect.assertThrows(IllegalArgumentException.class,
			() -> ChainIds.of(new Epoch(1), 0, Integer.MIN_VALUE));
		Expect.assertThrows(IllegalArgumentException.class, () -> ChainIds.of(new Epoch(1), -1, -1));
	}

	@Example
	public void shouldRefuseANegativeGroupWhenListingAGroup() {
		Expect.assertThrows(IllegalArgumentException.class, () -> ChainIds.ofGroup(new Epoch(1), -1, 1));
		Expect.assertThrows(IllegalArgumentException.class, () -> ChainIds.ofGroup(new Epoch(1), Integer.MIN_VALUE, 4));
	}
}

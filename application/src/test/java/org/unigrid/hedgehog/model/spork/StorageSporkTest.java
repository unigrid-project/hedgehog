/*
    Unigrid Hedgehog
    Copyright © 2021-2026 Stiftelsen The Unigrid Foundation, UGD Software AB

    Stiftelsen The Unigrid Foundation (org. nr: 802482-2408)
    UGD Software AB (org. nr: 559339-5824)

    This program is free software: you can redistribute it and/or modify it under the terms of the
    addended GNU Affero General Public License as published by the The Unigrid Foundation and
    the Free Software Foundation, version 3 of the License (see COPYING and COPYING.addendum).

    This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
    even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU Affero General Public License and the addendum for more details.

    You should have received an addended copy of the GNU Affero General Public License with this program.
    If not, see <http://www.gnu.org/licenses/> and <https://github.com/unigrid-project/hedgehog>.
 */

package org.unigrid.hedgehog.model.spork;

import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Builders;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import org.unigrid.hedgehog.model.spork.StorageSpork.SporkData;
import org.unigrid.hedgehog.model.storage.LayoutParameters;
import org.unigrid.hedgehog.model.storage.LayoutParametersTest;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

public class StorageSporkTest {
	public static <T> BiFunction<SporkData, T, SporkData> with(BiConsumer<SporkData, T> setter) {
		return (data, value) -> {
			setter.accept(data, value);
			return data;
		};
	}

	private static Arbitrary<Integer> justBeyond(int minimum, int maximum) {
		return Arbitraries.integers().between(minimum - 1, maximum + 1);
	}

	public static Arbitrary<LayoutParameters> anyLayouts() {
		return Arbitraries.oneOf(LayoutParametersTest.validLayouts(), LayoutParametersTest.extremeLayouts());
	}

	public static Arbitrary<SporkData> sporkDataAcrossTheBounds() {
		return Builders.withBuilder(SporkData::new)
			.use(anyLayouts()).in(with(StorageSporkTest::setLayout))
			.use(Arbitraries.longs()).in(with(SporkData::setMaxBytesPerNode))
			.use(Arbitraries.integers()).in(with(SporkData::setRepairIntervalMinutes))
			.use(justBeyond(1, 0xFFFF)).in(with(SporkData::setTombstoneDays))
			.use(justBeyond(1, 16)).in(with(SporkData::setManifestCopies))
			.use(justBeyond(0, 0xFF)).in(with(SporkData::setPlacementSlack))
			.use(justBeyond(1, 100)).in(with(SporkData::setRepairThresholdPercent))
			.use(justBeyond(0, 90)).in(with(SporkData::setExtraPoolPercent))
			.build();
	}

	public static boolean accepts(SporkData data) {
		return accepts(data::validate);
	}

	@Example
	public void hasValidDefaults() {
		final SporkData data = new StorageSpork().getData();

		data.validate();
		assertThat(data.getMaxBytesPerNode(), equalTo(10_737_418_240L));
		assertThat(data.getChunkSize(), equalTo(1_048_576));
		assertThat(data.getFragmentSize(), equalTo(65_536));
		assertThat(data.getOuterParityPercent(), equalTo(50));
		assertThat(data.getMaxOuterDataChunks(), equalTo(32));
		assertThat(data.getInnerParityPercent(), equalTo(50));
		assertThat(data.getMaxParityPercent(), equalTo(100));
		assertThat(data.getManifestCopies(), equalTo(3));
		assertThat(data.getPlacementSlack(), equalTo(8));
		assertThat(data.getRepairThresholdPercent(), equalTo(50));
		assertThat(data.getExtraPoolPercent(), equalTo(20));
		assertThat(data.getRepairIntervalMinutes(), equalTo(60));
		assertThat(data.getTombstoneDays(), equalTo(30));
		assertThat(data.layout().dataFragments(), equalTo(16));
		assertThat(data.layout().guaranteedFragments(), equalTo(24));
		assertThat(data.layout().maxFragments(), equalTo(32));
		assertThat(data.window(), equalTo(40));
	}

	@Example
	public void isRegisteredAsASporkType() {
		assertThat(GridSpork.Type.get((short) 1030), equalTo(GridSpork.Type.STORAGE));
		assertThat(GridSpork.create(GridSpork.Type.STORAGE).getType(), equalTo(GridSpork.Type.STORAGE));
	}

	@Provide
	public Arbitrary<LayoutParameters> anyLayoutsForTest() {
		return anyLayouts();
	}

	@Provide
	public Arbitrary<LayoutParameters> validLayouts() {
		return LayoutParametersTest.validLayouts();
	}

	@Property
	public void validatesItsLayoutLikeLayoutParameters(@ForAll("anyLayoutsForTest") LayoutParameters layout) {
		final SporkData data = new SporkData();

		setLayout(data, layout);
		assertThat(data.layout(), equalTo(layout));
		assertThat(accepts(data), is(accepts(layout::validate)));
	}

	@Property
	public void opensAWindowOfTheLargestChunkPlusSlack(@ForAll("validLayouts") LayoutParameters layout,
		@ForAll @IntRange(min = 0, max = 255) int placementSlack) {

		final SporkData data = new SporkData();

		setLayout(data, layout);
		data.setPlacementSlack(placementSlack);
		assertThat(data.window(), equalTo(layout.maxFragments() + placementSlack));
	}

	@Property
	public void acceptsManifestCopiesExactlyInRange(@ForAll @IntRange(min = -10, max = 300) int manifestCopies) {
		final SporkData data = new SporkData();

		data.setManifestCopies(manifestCopies);
		assertThat(accepts(data), is(manifestCopies >= 1 && manifestCopies <= 16));
	}

	@Property
	public void acceptsPlacementSlackExactlyInRange(@ForAll @IntRange(min = -10, max = 300) int placementSlack) {
		final SporkData data = new SporkData();

		data.setPlacementSlack(placementSlack);
		assertThat(accepts(data), is(placementSlack >= 0 && placementSlack <= 0xFF));
	}

	@Property
	public void acceptsRepairThresholdsExactlyInRange(@ForAll @IntRange(min = -10, max = 300) int threshold) {
		final SporkData data = new SporkData();

		data.setRepairThresholdPercent(threshold);
		assertThat(accepts(data), is(threshold >= 1 && threshold <= 100));
	}

	@Property
	public void acceptsExtraPoolsExactlyInRange(@ForAll @IntRange(min = -10, max = 300) int extraPoolPercent) {
		final SporkData data = new SporkData();

		data.setExtraPoolPercent(extraPoolPercent);
		assertThat(accepts(data), is(extraPoolPercent >= 0 && extraPoolPercent <= 90));
	}

	@Property
	public void acceptsOnlyPositiveRepairIntervals(@ForAll int repairIntervalMinutes) {
		final SporkData data = new SporkData();

		data.setRepairIntervalMinutes(repairIntervalMinutes);
		assertThat(accepts(data), is(repairIntervalMinutes >= 1));
	}

	@Property
	public void acceptsTombstoneDaysExactlyInRange(@ForAll @IntRange(min = -10, max = 70_000) int tombstoneDays) {
		final SporkData data = new SporkData();

		data.setTombstoneDays(tombstoneDays);
		assertThat(accepts(data), is(tombstoneDays >= 1 && tombstoneDays <= 0xFFFF));
	}

	@Property
	public void acceptsOnlyNonNegativeNodeQuotas(@ForAll long maxBytesPerNode) {
		final SporkData data = new SporkData();

		data.setMaxBytesPerNode(maxBytesPerNode);
		assertThat(accepts(data), is(maxBytesPerNode >= 0));
	}

	private static void setLayout(SporkData data, LayoutParameters layout) {
		data.setChunkSize(layout.getChunkSize());
		data.setFragmentSize(layout.getFragmentSize());
		data.setOuterParityPercent(layout.getOuterParityPercent());
		data.setMaxOuterDataChunks(layout.getMaxOuterDataChunks());
		data.setInnerParityPercent(layout.getInnerParityPercent());
		data.setMaxParityPercent(layout.getMaxParityPercent());
	}

	private static boolean accepts(Runnable validation) {
		try {
			validation.run();
			return true;
		} catch (IllegalArgumentException e) {
			return false;
		}
	}
}

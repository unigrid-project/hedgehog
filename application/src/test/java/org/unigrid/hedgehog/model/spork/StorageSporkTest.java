/*
    Unigrid Hedgehog
    Copyright © 2021-2023 Stiftelsen The Unigrid Foundation, UGD Software AB

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

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import org.unigrid.hedgehog.model.storage.LayoutParameters;
import org.unigrid.hedgehog.model.storage.LayoutParametersTest;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

public class StorageSporkTest {
	@Example
	public void hasValidDefaults() {
		final StorageSpork.SporkData data = new StorageSpork().getData();

		data.validate();
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
	public Arbitrary<LayoutParameters> anyLayouts() {
		return Arbitraries.oneOf(LayoutParametersTest.validLayouts(), new LayoutParametersTest().extremeLayouts());
	}

	@Provide
	public Arbitrary<LayoutParameters> validLayouts() {
		return LayoutParametersTest.validLayouts();
	}

	@Property
	public void validatesItsLayoutLikeLayoutParameters(@ForAll("anyLayouts") LayoutParameters layout) {
		final StorageSpork.SporkData data = withLayout(layout);

		assertThat(data.layout(), equalTo(layout));
		assertThat(accepts(data), is(accepts(layout)));
	}

	@Property
	public void opensAWindowOfTheLargestChunkPlusSlack(@ForAll("validLayouts") LayoutParameters layout,
		@ForAll @IntRange(min = 0, max = 255) int placementSlack) {

		final StorageSpork.SporkData data = withLayout(layout);

		data.setPlacementSlack(placementSlack);
		assertThat(data.window(), equalTo(layout.maxFragments() + placementSlack));
	}

	@Property
	public void acceptsManifestCopiesExactlyInRange(@ForAll @IntRange(min = -10, max = 300) int manifestCopies) {
		final StorageSpork.SporkData data = new StorageSpork.SporkData();

		data.setManifestCopies(manifestCopies);
		assertThat(accepts(data), is(manifestCopies >= 1 && manifestCopies <= 16));
	}

	@Property
	public void acceptsRepairThresholdsExactlyInRange(@ForAll @IntRange(min = -10, max = 300) int threshold) {
		final StorageSpork.SporkData data = new StorageSpork.SporkData();

		data.setRepairThresholdPercent(threshold);
		assertThat(accepts(data), is(threshold >= 1 && threshold <= 100));
	}

	@Property
	public void acceptsExtraPoolsExactlyInRange(@ForAll @IntRange(min = -10, max = 300) int extraPoolPercent) {
		final StorageSpork.SporkData data = new StorageSpork.SporkData();

		data.setExtraPoolPercent(extraPoolPercent);
		assertThat(accepts(data), is(extraPoolPercent >= 0 && extraPoolPercent <= 90));
	}

	@Property
	public void acceptsOnlyPositiveRepairIntervals(@ForAll int repairIntervalMinutes) {
		final StorageSpork.SporkData data = new StorageSpork.SporkData();

		data.setRepairIntervalMinutes(repairIntervalMinutes);
		assertThat(accepts(data), is(repairIntervalMinutes >= 1));
	}

	@Property
	public void acceptsOnlyPositiveTombstoneDays(@ForAll int tombstoneDays) {
		final StorageSpork.SporkData data = new StorageSpork.SporkData();

		data.setTombstoneDays(tombstoneDays);
		assertThat(accepts(data), is(tombstoneDays >= 1));
	}

	@Property
	public void acceptsOnlyNonNegativeNodeQuotas(@ForAll long maxBytesPerNode) {
		final StorageSpork.SporkData data = new StorageSpork.SporkData();

		data.setMaxBytesPerNode(maxBytesPerNode);
		assertThat(accepts(data), is(maxBytesPerNode >= 0));
	}

	private static StorageSpork.SporkData withLayout(LayoutParameters layout) {
		final StorageSpork.SporkData data = new StorageSpork.SporkData();

		data.setChunkSize(layout.getChunkSize());
		data.setFragmentSize(layout.getFragmentSize());
		data.setOuterParityPercent(layout.getOuterParityPercent());
		data.setMaxOuterDataChunks(layout.getMaxOuterDataChunks());
		data.setInnerParityPercent(layout.getInnerParityPercent());
		data.setMaxParityPercent(layout.getMaxParityPercent());
		return data;
	}

	private static boolean accepts(StorageSpork.SporkData data) {
		return accepts(data::validate);
	}

	private static boolean accepts(LayoutParameters layout) {
		return accepts(layout::validate);
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

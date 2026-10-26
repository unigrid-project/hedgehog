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

package org.unigrid.hedgehog.model.spork.content;

import java.util.function.Consumer;
import lombok.AllArgsConstructor;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import org.unigrid.hedgehog.model.spork.CommunicationSpork;
import org.unigrid.hedgehog.model.spork.CommunicationSpork.SporkData;
import org.unigrid.hedgehog.model.spork.CommunicationSporkTest;

public class CommunicationSporkContentTest {
	@AllArgsConstructor
	enum Accepted {
		MAX_ENTRY_BYTES_AT_LOWER_LIMIT(data -> data.setMaxEntryBytes(1024)),
		MAX_ENTRY_BYTES_AT_UPPER_LIMIT(data -> data.setMaxEntryBytes(1_048_576)),
		REORGANIZE_PERIOD_AT_LOWER_LIMIT(data -> data.setReorganizePeriodSeconds(60)),
		CHAIN_CAPACITY_HOLDING_ONE_ENTRY(data -> data.setChainCapacityBytes(data.getMaxEntryBytes())),
		NO_FLAGS(data -> data.setCommunicationFlags(0));

		private final Consumer<SporkData> change;
	}

	@AllArgsConstructor
	enum Refused {
		MAX_ENTRY_BYTES_BELOW_LOWER_LIMIT(data -> data.setMaxEntryBytes(1023)),
		MAX_ENTRY_BYTES_ABOVE_UPPER_LIMIT(data -> data.setMaxEntryBytes(1_048_577)),
		REORGANIZE_PERIOD_OF_ZERO(data -> data.setReorganizePeriodSeconds(0)),
		REORGANIZE_PERIOD_BELOW_LOWER_LIMIT(data -> data.setReorganizePeriodSeconds(59)),
		GROUP_SIZE_OF_ZERO(data -> data.setGroupSize(0)),
		CHAIN_CAPACITY_BELOW_ONE_ENTRY(data -> data.setChainCapacityBytes(data.getMaxEntryBytes() - 1)),
		UNKNOWN_FLAG(data -> data.setCommunicationFlags(0x04));

		private final Consumer<SporkData> change;
	}

	@Provide
	public Arbitrary<SporkData> sporkDataAcrossTheBounds() {
		return CommunicationSporkTest.sporkDataAcrossTheBounds();
	}

	private static CommunicationSpork sporkOf(final SporkData data) {
		final CommunicationSpork spork = new CommunicationSpork();

		spork.setData(data);
		return spork;
	}

	@Property
	public void takesDataAtItsLimits(@ForAll Accepted limit) {
		final SporkData data = new SporkData();

		limit.change.accept(data);
		SporkContent.validate(sporkOf(data), null);
	}

	@Property
	public void refusesDataBeyondItsLimits(@ForAll Refused limit) {
		final SporkData data = new SporkData();

		limit.change.accept(data);
		assertThrows(IllegalArgumentException.class, () -> SporkContent.validate(sporkOf(data), null));
	}

	@Property
	public void takesTheDataThatValidatesAndRefusesTheRest(@ForAll("sporkDataAcrossTheBounds") SporkData data) {
		boolean taken = true;

		try {
			SporkContent.validate(sporkOf(data), null);
		} catch (IllegalArgumentException ex) {
			taken = false;
		}

		assertThat(taken, equalTo(CommunicationSporkTest.accepts(data)));
	}

	/* The refusal is relayed and logged, so it names the field and the value and nothing else */
	@Example
	public void namesTheFieldAndTheValueOfARefusal() {
		final SporkData data = new SporkData();

		data.setGroupSize(0);
		assertThat(assertThrows(IllegalArgumentException.class, () -> SporkContent.validate(sporkOf(data), null))
			.getMessage(), containsString("groupSize"));
	}

	@Example
	public void takesTheDefaultParameters() {
		SporkContent.validate(new CommunicationSpork(), null);
		assertThat(CommunicationSporkTest.accepts(new SporkData()), is(true));
	}
}

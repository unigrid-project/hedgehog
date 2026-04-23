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

package org.unigrid.hedgehog.service.storage;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.stream.IntStream;
import lombok.SneakyThrows;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

public class RepairServiceTest {
	private enum Outcome { SUCCEEDS, FAILS, BREAKS }

	private static final class ScriptedRepairer extends GroupRepairer {
		private final List<Outcome> outcomes;
		private final List<Long> runs = new ArrayList<>();
		private OptionalLong epoch = OptionalLong.empty();

		ScriptedRepairer(List<Outcome> outcomes) {
			super(null, null, null, null, null);
			this.outcomes = outcomes;
		}

		@Override
		public OptionalLong currentEpoch() {
			return epoch;
		}

		@Override
		public void runEpoch() {
			final Outcome outcome = outcomes.get(runs.size() % outcomes.size());

			runs.add(epoch.getAsLong());

			if (outcome == Outcome.FAILS) {
				throw new IllegalStateException("A failing round");
			} else if (outcome == Outcome.BREAKS) {
				throw new StackOverflowError();
			}
		}
	}

	@SneakyThrows
	private static RepairService serviceOf(GroupRepairer repairer) {
		final RepairService service = new RepairService();
		final Field field = RepairService.class.getDeclaredField("repairer");

		field.setAccessible(true);
		field.set(service, repairer);
		return service;
	}

	@Property(tries = 200)
	public void runsEveryLaterEpochOnceWhateverTheRoundsThrow(@ForAll @IntRange(min = 0, max = 3) int idleTicks,
		@ForAll @Size(max = 40) List<@IntRange(min = 0, max = 2) Integer> advances,
		@ForAll @Size(min = 1, max = 10) List<Outcome> outcomes) {

		final ScriptedRepairer repairer = new ScriptedRepairer(outcomes);
		final RepairService service = serviceOf(repairer);
		final List<Long> expected = new ArrayList<>();
		long epoch = 0;

		IntStream.range(0, idleTicks).forEach(tick -> service.tick());
		repairer.epoch = OptionalLong.of(epoch);
		service.tick();

		for (int advance : advances) {
			epoch += advance;
			repairer.epoch = OptionalLong.of(epoch);

			if (advance > 0) {
				expected.add(epoch);
			}

			service.tick();
		}

		assertThat(repairer.runs, equalTo(expected));
	}
}

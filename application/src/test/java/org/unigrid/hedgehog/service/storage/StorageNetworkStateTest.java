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

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;
import net.jqwik.api.stateful.Action;
import net.jqwik.api.stateful.ActionSequence;

public class StorageNetworkStateTest {
	private record Store(byte[] file) implements Action<StorageNetworkModel> {
		@Override
		public StorageNetworkModel run(StorageNetworkModel model) {
			model.store(file);
			return model;
		}
	}

	private record Delete(int pick) implements Action<StorageNetworkModel> {
		@Override
		public boolean precondition(StorageNetworkModel model) {
			return model.hasFiles();
		}

		@Override
		public StorageNetworkModel run(StorageNetworkModel model) {
			model.delete(pick);
			return model;
		}
	}

	private record Kill(int pick) implements Action<StorageNetworkModel> {
		@Override
		public boolean precondition(StorageNetworkModel model) {
			return model.canDamage();
		}

		@Override
		public StorageNetworkModel run(StorageNetworkModel model) {
			model.kill(pick);
			return model;
		}
	}

	private record Wipe(int pick) implements Action<StorageNetworkModel> {
		@Override
		public boolean precondition(StorageNetworkModel model) {
			return model.canDamage();
		}

		@Override
		public StorageNetworkModel run(StorageNetworkModel model) {
			model.wipe(pick);
			return model;
		}
	}

	private record Revive(int pick) implements Action<StorageNetworkModel> {
		@Override
		public boolean precondition(StorageNetworkModel model) {
			return model.hasOffline();
		}

		@Override
		public StorageNetworkModel run(StorageNetworkModel model) {
			model.revive(pick);
			return model;
		}
	}

	private record Join() implements Action<StorageNetworkModel> {
		@Override
		public boolean precondition(StorageNetworkModel model) {
			return model.canDamage();
		}

		@Override
		public StorageNetworkModel run(StorageNetworkModel model) {
			model.join();
			return model;
		}
	}

	private record Heal() implements Action<StorageNetworkModel> {
		@Override
		public StorageNetworkModel run(StorageNetworkModel model) {
			model.heal();
			return model;
		}
	}

	@Provide
	Arbitrary<ActionSequence<StorageNetworkModel>> churn() {
		final Arbitrary<Integer> picks = Arbitraries.integers().greaterOrEqual(0);

		return Arbitraries.sequences(Arbitraries.frequencyOf(
			Tuple.of(3, StorageArbitraries.files(StorageNetworkModel.PARAMETERS).map(Store::new)),
			Tuple.of(1, picks.map(Delete::new)),
			Tuple.of(2, picks.map(Kill::new)),
			Tuple.of(2, picks.map(Wipe::new)),
			Tuple.of(1, picks.map(Revive::new)),
			Tuple.of(1, Arbitraries.just(new Join())),
			Tuple.of(1, Arbitraries.just(new Heal()))
		)).ofSize(30);
	}

	@Property(tries = 15)
	public void keepsEveryLiveFileReadableThroughChurn(@ForAll("churn") ActionSequence<StorageNetworkModel> actions) {
		actions.withInvariant("live files read back unchanged", StorageNetworkModel::assertLiveFilesReadBack)
			.run(new StorageNetworkModel());
	}
}

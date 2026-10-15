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

package org.unigrid.hedgehog.ledger;

import java.io.IOException;
import java.util.Optional;

/* A store on top of another that stops saving when told to: with an IOException, as a disk does when the node dies in
   an event, or with a RuntimeException or an Error, as a store that is broken does. Loading and closing go to the
   store underneath. */
final class FlakyStore implements SignStore {
	private final SignStore store;
	private volatile Throwable failure;

	FlakyStore(SignStore store) {
		this.store = store;
	}

	void failWith(Throwable reason) {
		this.failure = reason;
	}

	@Override
	public Optional<SignState> load() throws IOException {
		return store.load();
	}

	@Override
	public void save(SignState state, int largestProof) throws IOException {
		if (failure instanceof IOException io) {
			throw io;
		}

		if (failure instanceof RuntimeException runtime) {
			throw runtime;
		}

		if (failure instanceof Error error) {
			throw error;
		}

		store.save(state, largestProof);
	}

	@Override
	public void close() throws IOException {
		store.close();
	}
}

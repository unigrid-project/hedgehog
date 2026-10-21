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

import java.io.Closeable;
import java.io.IOException;
import java.util.Optional;

/* Where a validator keeps what it has signed. `largestProof` is the size of the validator set that the state is saved
   under, which sets the largest state that can be read back. The store keeps it with the state, as the set may have
   changed by the time of the next start, and load reads the state under the size it was saved with. save returns only
   once the state is durable, as the caller sends nothing that depends on it before then, and refuses a state that
   load would refuse for its size. load answers empty only when nothing was ever saved: a state that is there but
   cannot be trusted is an exception, never an empty answer, since starting from nothing could sign twice. A store
   that keeps something open, such as the lock that keeps a second node off the same state, lets go of it in close(). */
public interface SignStore extends Closeable {
	Optional<SignState> load() throws IOException;

	void save(SignState state, int largestProof) throws IOException;

	@Override
	default void close() throws IOException {
		/* A store that keeps nothing open has nothing to let go of */
	}
}

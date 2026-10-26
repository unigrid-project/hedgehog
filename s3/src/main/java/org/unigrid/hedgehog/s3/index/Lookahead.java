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

package org.unigrid.hedgehog.s3.index;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;

/* An iterator that finds its next element ahead of time, where null marks the end */
abstract class Lookahead<T> implements Iterator<T> {
	private T next;
	private boolean ready;

	protected abstract T advance();

	@Override
	public boolean hasNext() {
		if (!ready) {
			next = advance();
			ready = true;
		}

		return Objects.nonNull(next);
	}

	@Override
	public T next() {
		if (!hasNext()) {
			throw new NoSuchElementException();
		}

		ready = false;
		return next;
	}

	T peek() {
		return hasNext() ? next : null;
	}
}

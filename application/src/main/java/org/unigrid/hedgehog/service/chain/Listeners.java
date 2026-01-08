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

package org.unigrid.hedgehog.service.chain;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;

/* The listeners of one kind. One that fails is logged and the others are still told. */
@Slf4j
final class Listeners<T> {
	private final List<T> registered = new CopyOnWriteArrayList<>();

	void add(final T listener) {
		registered.add(listener);
	}

	void tell(final Consumer<T> call) {
		for (final T listener : registered) {
			try {
				call.accept(listener);
			} catch (RuntimeException ex) {
				log.warn("A chain listener failed with {}", ex.getClass().getName());
			}
		}
	}
}

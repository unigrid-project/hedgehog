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

package org.unigrid.hedgehog.service.ledger;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.jboss.weld.environment.se.WeldContainer;
import org.unigrid.hedgehog.jqwik.NamedCDIProvider;

/* Reaching into the container of a node by its name. What runs within a container is named after it by the threads it
   makes, which is how the handlers and the services of that node find their own beans. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class FleetContainers {
	static <T> T within(final String container, final Supplier<T> action) {
		final AtomicReference<T> result = new AtomicReference<>();

		NamedCDIProvider.within(container, () -> result.set(action.get()));
		return result.get();
	}

	static <T> T bean(final String container, final Class<T> type) {
		return WeldContainer.instance(container).select(type).get();
	}
}

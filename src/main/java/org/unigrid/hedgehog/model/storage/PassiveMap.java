/*
	The Janus Wallet
	Copyright © 2021-2022 The Unigrid Foundation, UGD Software AB

	This program is free software: you can redistribute it and/or modify it under the terms of the
	addended GNU Affero General Public License as published by the Free Software Foundation, version 3
	of the License (see COPYING and COPYING.addendum).

	This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
	even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
	GNU Affero General Public License for more details.

	You should have received an addended copy of the GNU Affero General Public License with this program.
	If not, see <http://www.gnu.org/licenses/> and <https://github.com/unigrid-project/janus-java>.
 */
package org.unigrid.hedgehog.model.storage;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import org.apache.commons.collections4.map.AbstractMapDecorator;
import org.apache.commons.collections4.map.PassiveExpiringMap;

@ApplicationScoped
public class PassiveMap<K, V> extends AbstractMapDecorator<K, V> {

	@Inject
	private Storage storage;

	public PassiveMap(PassiveExpiringMap<K, V> map) {
		super(map);
	}

	public Optional<V> getOptional(K key) {
		final V value = get(key);

		if (get(key) == null) {
			return Optional.empty();
		}

		return Optional.of(value);
	}

	public void put(String key, BlockData blockData) {
		storage.store(key, blockData);
	}
}

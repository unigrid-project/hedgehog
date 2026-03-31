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

    package org.unigrid.hedgehog.model.collection;

    import java.util.Map;
    import java.util.Optional;
    
    /**
     * Wrapper around a standard Map that provides Optional for safe access.
     */
    public class OptionalMap<K, V> {
    
        private final Map<K, V> map;
    
        public OptionalMap(Map<K, V> map) {
            this.map = map;
        }
    
        public Optional<V> getOptional(K key) {
            return Optional.ofNullable(map.get(key));
        }
    
        public void put(K key, V value) {
            map.put(key, value);
        }
    
        public boolean containsKey(K key) {
            return map.containsKey(key);
        }
    
        public Map<K, V> asMap() {
            return map;
        }
    }
    
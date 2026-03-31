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

	import io.netty.util.collection.IntObjectHashMap; // Exempel på Netty-specifik map om du vill
	import java.util.AbstractMap.SimpleEntry;
	import java.util.HashMap;
	import java.util.Map;
	import java.util.Map.Entry;
	
	/**
	 * Utility class for creating nullable maps quickly.
	 * Supports up to 7 entries out of the box.
	 */
	@SuppressWarnings("checkstyle:ParameterNumber")
	public class NullableMap {
	
		@SafeVarargs
		public static <K, V> Map<K, V> ofEntries(Entry<K, V>... entries) {
			final Map<K, V> map = new HashMap<>();
			for (Entry<K, V> entry : entries) {
				map.put(entry.getKey(), entry.getValue());
			}
			return map;
		}
	
		public static <K, V> Map<K, V> of(K k1, V v1) {
			return ofEntries(new SimpleEntry<>(k1, v1));
		}
	
		public static <K, V> Map<K, V> of(K k1, V v1, K k2, V v2) {
			return ofEntries(new SimpleEntry<>(k1, v1), new SimpleEntry<>(k2, v2));
		}
	
		public static <K, V> Map<K, V> of(K k1, V v1, K k2, V v2, K k3, V v3) {
			return ofEntries(new SimpleEntry<>(k1, v1), new SimpleEntry<>(k2, v2),
					new SimpleEntry<>(k3, v3));
		}
	
		public static <K, V> Map<K, V> of(K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4) {
			return ofEntries(new SimpleEntry<>(k1, v1), new SimpleEntry<>(k2, v2),
					new SimpleEntry<>(k3, v3), new SimpleEntry<>(k4, v4));
		}
	
		public static <K, V> Map<K, V> of(K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4,
										  K k5, V v5) {
			return ofEntries(new SimpleEntry<>(k1, v1), new SimpleEntry<>(k2, v2),
					new SimpleEntry<>(k3, v3), new SimpleEntry<>(k4, v4),
					new SimpleEntry<>(k5, v5));
		}
	
		public static <K, V> Map<K, V> of(K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4,
										  K k5, V v5, K k6, V v6) {
			return ofEntries(new SimpleEntry<>(k1, v1), new SimpleEntry<>(k2, v2),
					new SimpleEntry<>(k3, v3), new SimpleEntry<>(k4, v4),
					new SimpleEntry<>(k5, v5), new SimpleEntry<>(k6, v6));
		}
	
		public static <K, V> Map<K, V> of(K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4,
										  K k5, V v5, K k6, V v6, K k7, V v7) {
			return ofEntries(new SimpleEntry<>(k1, v1), new SimpleEntry<>(k2, v2),
					new SimpleEntry<>(k3, v3), new SimpleEntry<>(k4, v4),
					new SimpleEntry<>(k5, v5), new SimpleEntry<>(k6, v6),
					new SimpleEntry<>(k7, v7));
		}
	
		/**
		 * Example Netty-specific map for performance optimization
		 */
		public static <V> IntObjectHashMap<V> nettyMap() {
			return new IntObjectHashMap<>();
		}
	}
	
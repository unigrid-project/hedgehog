/*
    Unigrid Hedgehog
    Copyright © 2021-2022 The Unigrid Foundation, UGD Software AB

    This program is free software: you can redistribute it and/or modify it under the terms of the
    addended GNU Affero General Public License as published by the The Unigrid Foundation and
    the Free Software Foundation, version 3 of the License (see COPYING and COPYING.addendum).

    This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
    even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU Affero General Public License and the addendum for more details.

    You should have received an addended copy of the GNU Affero General Public License with this program.
    If not, see <http://www.gnu.org/licenses/> and <https://github.com/unigrid-project/hedgehog>.
 */

package org.unigrid.hedgehog.model.network.chunk;

import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Pair;
import org.reflections.Reflections;
import org.unigrid.hedgehog.model.collection.OptionalMap;
import org.unigrid.hedgehog.model.network.codec.chunk.TypedCodec;

@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class ChunkScanner {
	public static <K, V> OptionalMap<K, V> scan(ChunkType chunkType, ChunkGroup chunkGroup) {
		final String packageName = TypedCodec.class.getPackageName();
		final Set<Class<?>> classes = new Reflections(packageName).getTypesAnnotatedWith(Chunk.class);

		final Set<Class<?>> filteredClasses = classes.stream().filter(clazz -> {
			final Chunk chunk = clazz.getAnnotation(Chunk.class);
			return chunk.type() == chunkType && chunk.group() == chunkGroup;
		}).collect(Collectors.toSet());

		final Map<K, V> chunks = (Map<K, V>) filteredClasses.stream().map(x -> {
			try {
				final TypedCodec<K> instance = (TypedCodec<K>) x.getDeclaredConstructor().newInstance();
				return Pair.of(instance.getCodecType(), instance);
			} catch (Exception ex) {
				Logger.getLogger(ChunkScanner.class.getName()).log(Level.SEVERE, null, ex);
				throw new IllegalStateException("Unable to instantiate chunk converter.", ex);
			}
		}).collect(Collectors.toMap(p -> p.getLeft(), p -> p.getRight()));

		return new OptionalMap(chunks);
	}
}

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

package org.unigrid.hedgehog.model.spork;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Pair;
import org.reflections.Reflections;
import org.reflections.util.ClasspathHelper;
import org.reflections.util.ConfigurationBuilder;
import org.reflections.util.FilterBuilder;
import org.unigrid.hedgehog.model.collection.OptionalMap;
import org.unigrid.hedgehog.model.network.chunk.Chunk;
import org.unigrid.hedgehog.model.network.chunk.ChunkGroup;
import org.unigrid.hedgehog.model.network.chunk.ChunkType;
import org.unigrid.hedgehog.model.network.chunk.TypedCodec;

@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class ChunkScanner {
	/* Every stream of a peer builds a decoder that looks the codecs up here, on the event loop of its connection,
	   and scanning the classpath reads the whole jar. So a type and group is scanned once, and the codecs it found,
	   which keep nothing of what they read, serve every stream. */
	private static final Map<Pair<ChunkType, ChunkGroup>, OptionalMap<?, ?>> SCANNED = new ConcurrentHashMap<>();

	public static <K, V> OptionalMap<K, V> scan(ChunkType chunkType, ChunkGroup chunkGroup) {
		return (OptionalMap<K, V>) SCANNED.computeIfAbsent(Pair.of(chunkType, chunkGroup),
			key -> load(chunkType, chunkGroup));
	}

	private static <K, V> OptionalMap<K, V> load(ChunkType chunkType, ChunkGroup chunkGroup) {
		/* The package is also on the test classpath, where a plain package lookup would find only test classes */
		final Set<Class<?>> classes = new Reflections(new ConfigurationBuilder()
			.addUrls(ClasspathHelper.forClass(ChunkScanner.class))
			.filterInputsBy(new FilterBuilder().includePackage(ChunkScanner.class.getPackageName()))
		).getTypesAnnotatedWith(Chunk.class);

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

		return new OptionalMap(Collections.unmodifiableMap(chunks));
	}
}

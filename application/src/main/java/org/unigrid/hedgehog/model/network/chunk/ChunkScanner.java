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
	package org.unigrid.hedgehog.model.network.chunk;

	import java.lang.reflect.InvocationTargetException;
	import java.util.Map;
	import java.util.Set;
	import java.util.logging.Level;
	import java.util.logging.Logger;
	import java.util.stream.Collectors;
	
	import org.reflections.Reflections;
	import org.unigrid.hedgehog.model.collection.OptionalMap;
	import org.unigrid.hedgehog.model.network.codec.chunk.TypedCodec;
	
	/**
	 * Scanner för alla Chunk-annoterade klasser.
	 */
	public final class ChunkScanner {
	
		private static final Logger LOGGER =
				Logger.getLogger(ChunkScanner.class.getName());
	
		private ChunkScanner() {
			// utility class
		}
	
		/**
		 * Skannar alla Chunk-klasser med specifik typ och grupp.
		 */
		public static <K, V extends TypedCodec<K>> OptionalMap<K, V> scan(
				ChunkType chunkType,
				ChunkGroup chunkGroup
		) {
			final String packageName = TypedCodec.class.getPackageName();
	
			// Hämta alla klasser med @Chunk
			final Set<Class<?>> annotatedClasses = new Reflections(packageName)
					.getTypesAnnotatedWith(Chunk.class);
	
			final Map<K, V> chunks = annotatedClasses.stream()
	
					// Säkerställ att klassen implementerar TypedCodec
					.filter(clazz -> TypedCodec.class.isAssignableFrom(clazz))
	
					// Filtrera på Chunk-attribut
					.filter(clazz -> {
						Chunk chunk = clazz.getAnnotation(Chunk.class);
						return chunk.type() == chunkType
								&& chunk.group() == chunkGroup;
					})
	
					// Instansiera codecs
					.map(clazz -> {
						try {
							@SuppressWarnings("unchecked")
							Class<? extends TypedCodec<?>> codecClass =
									(Class<? extends TypedCodec<?>>) clazz;
	
							@SuppressWarnings("unchecked")
							V instance = (V) codecClass
									.getDeclaredConstructor()
									.newInstance();
	
							return Map.entry(instance.getCodecType(), instance);
	
						} catch (NoSuchMethodException |
								 InstantiationException |
								 IllegalAccessException |
								 InvocationTargetException e) {
	
							LOGGER.log(
									Level.SEVERE,
									"Unable to instantiate chunk codec: " + clazz.getName(),
									e
							);
							throw new IllegalStateException(
									"Unable to instantiate chunk codec: " + clazz.getName(),
									e
							);
						}
					})
	
					.collect(Collectors.toMap(
							Map.Entry::getKey,
							Map.Entry::getValue
					));
	
			return new OptionalMap<>(chunks);
		}
	}
	
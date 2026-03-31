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
	package org.unigrid.hedgehog.model.network.channel;

	import java.lang.annotation.Annotation;
	import java.lang.reflect.InvocationTargetException;
	import java.net.URL;
	import java.util.*;
	import java.util.stream.Collectors;
	
	import org.reflections.Reflections;
	import org.reflections.scanners.Scanners;
	import org.reflections.util.ClasspathHelper;
	import org.reflections.util.ConfigurationBuilder;
	
	/**
	 * Samlar alla annoterade kanalkomponenter: Codec, Handler, Scheduler.
	 */
	public final class ChannelCollector {
	
		private ChannelCollector() { }
	
		private static Reflections reflections(URL... urls) {
			return new Reflections(
					new ConfigurationBuilder()
							.addUrls(urls)
							.setScanners(Scanners.TypesAnnotated)
			);
		}
	
		private static URL[] locations(Class<?> defaultLocation, URL... urls) {
			if (urls == null || urls.length == 0) {
				return new URL[]{ClasspathHelper.forClass(defaultLocation)};
			}
			return urls;
		}
	
		// ===================== TYP-SÄKER FIND =====================
		@SuppressWarnings("unchecked")
		private static <T> List<T> find(Reflections reflections, Class<? extends Annotation> annotationClass, Enum<?> type) {
			List<T> collected = new ArrayList<>();
			for (Class<?> clazz : reflections.getTypesAnnotatedWith(annotationClass)) {
				try {
					collected.add((T) clazz.getDeclaredConstructor().newInstance());
				} catch (NoSuchMethodException | InstantiationException | IllegalAccessException | InvocationTargetException e) {
					throw new RuntimeException("Failed to instantiate " + clazz.getName(), e);
				}
			}
	
			return collected.stream()
					.filter(Objects::nonNull)
					.filter(o -> matchesRepeatable(o, type))
					.sorted(Comparator.comparingInt(o -> priorityRepeatable(o, type)))
					.collect(Collectors.toList());
		}
	
		// ===================== REPEATABLE SUPPORT =====================
		private static boolean matchesRepeatable(Object o, Enum<?> type) {
			Class<?> clazz = o.getClass();
	
			if (type instanceof ChannelCodec.Type codecType) {
				for (ChannelCodec cc : clazz.getAnnotationsByType(ChannelCodec.class)) {
					if (Arrays.asList(cc.value()).contains(codecType)) return true;
				}
			}
	
			if (type instanceof ChannelHandler.Type handlerType) {
				for (ChannelHandler ch : clazz.getAnnotationsByType(ChannelHandler.class)) {
					if (Arrays.asList(ch.value()).contains(handlerType)) return true;
				}
			}
	
			if (type instanceof ChannelScheduler.Type schedulerType) {
				for (ChannelScheduler cs : clazz.getAnnotationsByType(ChannelScheduler.class)) {
					if (Arrays.asList(cs.value()).contains(schedulerType)) return true;
				}
			}
	
			return false;
		}
	
		private static int priorityRepeatable(Object o, Enum<?> type) {
			Class<?> clazz = o.getClass();
			int priority = Integer.MAX_VALUE;
	
			if (type instanceof ChannelCodec.Type) {
				for (ChannelCodec cc : clazz.getAnnotationsByType(ChannelCodec.class)) {
					priority = Math.min(priority, cc.priority());
				}
			}
	
			if (type instanceof ChannelHandler.Type) {
				for (ChannelHandler ch : clazz.getAnnotationsByType(ChannelHandler.class)) {
					priority = Math.min(priority, ch.priority());
				}
			}
	
			if (type instanceof ChannelScheduler.Type) {
				for (ChannelScheduler cs : clazz.getAnnotationsByType(ChannelScheduler.class)) {
					priority = Math.min(priority, cs.priority());
				}
			}
	
			return priority == Integer.MAX_VALUE ? 0 : priority;
		}
	
		// ===================== PUBLIC API =====================
		public static <T> List<T> collectCodecs(ChannelCodec.Type type, URL... urls) {
			URL[] locations = locations(org.unigrid.hedgehog.model.network.codec.Package.class, urls);
			return find(reflections(locations), ChannelCodec.class, type);
		}
	
		public static <T> List<T> collectHandlers(ChannelHandler.Type type, URL... urls) {
			URL[] locations = locations(org.unigrid.hedgehog.model.network.handler.Package.class, urls);
			return find(reflections(locations), ChannelHandler.class, type);
		}
	
		public static <T> List<T> collectSchedulers(ChannelScheduler.Type type, URL... urls) {
			URL[] locations = locations(org.unigrid.hedgehog.model.network.schedule.Package.class, urls);
			return find(reflections(locations), ChannelScheduler.class, type);
		}
	}
	
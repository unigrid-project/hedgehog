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
import java.net.URL;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ArrayUtils;
import org.reflections.Reflections;
import org.reflections.util.ClasspathHelper;
import org.reflections.util.ConfigurationBuilder;
import org.unigrid.hedgehog.model.network.channel.ChannelCodec.Type;
import org.reflections.scanners.Scanners;

@Slf4j
public class ChannelCollector {
	private static Reflections reflections(URL... urls) {
		return new Reflections(new ConfigurationBuilder()
			.setScanners(Scanners.TypesAnnotated, Scanners.Resources)
			.addUrls(urls)
		);
	}

	@SneakyThrows
	private static <T> List<T> find(Reflections reflections, Class<? extends Annotation> clazz, Type type) {
		final List<T> collected = new ArrayList<>();

		for (Class<?> handler : reflections.getTypesAnnotatedWith(clazz)) {
			collected.add((T) handler.getDeclaredConstructor().newInstance());
		}

		collected.sort(Comparator.comparing(o -> {
			final ChannelCodec channelCodec = o.getClass().getAnnotation(ChannelCodec.class);
			return channelCodec.priority();
		}));

		return collected.stream().filter(o -> {
			final ChannelCodec channelCodec = o.getClass().getAnnotation(ChannelCodec.class);
			return ArrayUtils.contains(channelCodec.value(), type);
		}).collect(Collectors.toList());
	}

	private static URL[] locations(Class<?> defaultLocation, URL... urls) {
		if (urls.length == 0) {
			return new URL[] { ClasspathHelper.forClass(defaultLocation) };
		}

		return urls;
	}

	public static <T extends ChannelHandler> List<T> collectCodecs(Type type, URL... urls) {
		final URL[] locations = locations(org.unigrid.hedgehog.model.network.codec.Package.class, urls);
		return find(reflections(locations), ChannelCodec.class, type);
	}

	public static <T extends ChannelHandler> List<T> collectHandlers(Type type, URL... urls) {
		final URL[] locations = locations(org.unigrid.hedgehog.model.network.handler.Package.class, urls);
		return find(reflections(locations), ChannelHandler.class, type);
	}

	public static <T extends ChannelHandler> List<T> collectSchedules(Type type, URL... urls) {
		final URL[] locations = locations(org.unigrid.hedgehog.model.network.schedule.Package.class, urls);
		return find(reflections(locations), ChannelScheduler.class, type);
	}	
}

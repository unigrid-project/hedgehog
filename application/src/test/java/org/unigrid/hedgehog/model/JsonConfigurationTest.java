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

package org.unigrid.hedgehog.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.ext.ContextResolver;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.MessageBodyWriter;
import jakarta.ws.rs.ext.Providers;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import org.glassfish.jersey.jackson.internal.jackson.jaxrs.json.JacksonJaxbJsonProvider;
import org.unigrid.hedgehog.model.bootstrap.SignatureStatus;
import org.unigrid.hedgehog.model.bootstrap.SnapshotInfo;

public class JsonConfigurationTest {
	private static final int REQUESTS = 100;
	private static final Annotation[] NO_ANNOTATIONS = new Annotation[0];

	@SuppressWarnings("unchecked")
	private static final Class<Object> INFO_TYPE = (Class<Object>) (Class<?>) SnapshotInfo.class;

	private final AtomicInteger asked = new AtomicInteger();
	private final JsonConfiguration configuration = new JsonConfiguration();

	/* The provider Jersey registers for the REST interface, with the mapper it asks the resolver for counted */
	private JacksonJaxbJsonProvider provider() {
		final Providers providers = new Providers() {
			@Override
			public <T> MessageBodyReader<T> getMessageBodyReader(Class<T> type, Type genericType,
				Annotation[] annotations, MediaType mediaType) {

				return null;
			}

			@Override
			public <T> MessageBodyWriter<T> getMessageBodyWriter(Class<T> type, Type genericType,
				Annotation[] annotations, MediaType mediaType) {

				return null;
			}

			@Override
			public <T extends Throwable> ExceptionMapper<T> getExceptionMapper(Class<T> type) {
				return null;
			}

			@Override
			@SuppressWarnings("unchecked")
			public <T> ContextResolver<T> getContextResolver(Class<T> contextType, MediaType mediaType) {
				return (ContextResolver<T>) (ContextResolver<ObjectMapper>) type -> {
					asked.incrementAndGet();
					return configuration.getContext(type);
				};
			}
		};

		return new JacksonJaxbJsonProvider() {
			{
				_providers = providers;
			}
		};
	}

	private static SnapshotInfo info() {
		return SnapshotInfo.builder().tipHash("00").tipHeight(1).built(Instant.parse("2026-01-02T03:04:05Z"))
			.totalUnspent(new BigDecimal("1.5")).signature(SignatureStatus.UNSIGNED).build();
	}

	@SneakyThrows
	private static byte[] written(JacksonJaxbJsonProvider provider) {
		final ByteArrayOutputStream out = new ByteArrayOutputStream();

		provider.writeTo(info(), SnapshotInfo.class, SnapshotInfo.class, NO_ANNOTATIONS,
			MediaType.APPLICATION_JSON_TYPE, new MultivaluedHashMap<>(), out);

		return out.toByteArray();
	}

	/* Building a mapper and warming its serializers is the cost of one request, so the REST interface must not
	   have one built for every answer it writes or every body it reads. */
	@Example
	public void shouldAskForAMapperOnceHoweverManyAnswersAreWritten() {
		final JacksonJaxbJsonProvider provider = provider();

		for (int i = 0; i < REQUESTS; i++) {
			written(provider);
		}

		assertThat(asked.get(), is(1));
	}

	@Example
	@SneakyThrows
	public void shouldAskForAMapperOnceHoweverManyBodiesAreRead() {
		final JacksonJaxbJsonProvider provider = provider();
		final byte[] body = written(provider);
		final int askedToWrite = asked.get();

		for (int i = 0; i < REQUESTS; i++) {
			provider.readFrom(INFO_TYPE, SnapshotInfo.class, NO_ANNOTATIONS,
				MediaType.APPLICATION_JSON_TYPE, new MultivaluedHashMap<>(), new ByteArrayInputStream(body));
		}

		assertThat(asked.get() - askedToWrite, is(1));
	}

	@Example
	@SneakyThrows
	public void shouldWriteTheSnapshotInfoTheSameThroughTheProviderAndTheMapper() {
		final String direct = configuration.getContext(SnapshotInfo.class).writeValueAsString(info());

		assertThat(new String(written(provider()), StandardCharsets.UTF_8), equalTo(direct));
	}
}

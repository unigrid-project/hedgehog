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

package org.unigrid.hedgehog.s3.error;

import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.NotAllowedException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Request;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.unigrid.hedgehog.s3.entity.MalformedXmlException;
import org.unigrid.hedgehog.s3.index.BucketNotEmptyException;
import org.unigrid.hedgehog.s3.index.ConditionFailedException;
import org.unigrid.hedgehog.s3.index.NoSuchBucketException;
import org.unigrid.hedgehog.s3.index.NoSuchUploadException;
import org.unigrid.hedgehog.s3.spi.BlobUnavailableException;

@Slf4j
public class S3ExceptionMapper implements ExceptionMapper<Throwable> {
	private static final Duration RETRY_AFTER = Duration.ofSeconds(5);

	/* In order, so a subclass is named before its superclass. Every path an S3 operation uses is routed, so one that
	   matches nothing names an unsupported operation. */
	private static final List<Map.Entry<Class<? extends Throwable>, S3ErrorCode>> RECOGNISED = List.of(
		Map.entry(BlobUnavailableException.class, S3ErrorCode.ServiceUnavailable),
		Map.entry(MalformedXmlException.class, S3ErrorCode.MalformedXML),
		Map.entry(NoSuchBucketException.class, S3ErrorCode.NoSuchBucket),
		Map.entry(BucketNotEmptyException.class, S3ErrorCode.BucketNotEmpty),
		Map.entry(NoSuchUploadException.class, S3ErrorCode.NoSuchUpload),
		Map.entry(ConditionFailedException.class, S3ErrorCode.PreconditionFailed),
		Map.entry(NotAllowedException.class, S3ErrorCode.MethodNotAllowed),
		Map.entry(NotFoundException.class, S3ErrorCode.NotImplemented),
		Map.entry(ClientErrorException.class, S3ErrorCode.InvalidRequest)
	);

	@Context private UriInfo uri;
	@Context private Request request;

	/* A failing request stream may be wrapped by whatever read it, so its cause wins over the wrapper */
	static S3Exception translate(final Throwable thrown) {
		final List<Throwable> chain = Stream.iterate(thrown, Objects::nonNull, Throwable::getCause).toList();

		return chain.stream().filter(S3Exception.class::isInstance).map(S3Exception.class::cast).findFirst()
			.or(() -> chain.stream().map(S3ExceptionMapper::recognise).flatMap(Optional::stream).findFirst())
			.orElseGet(() -> unexpected(thrown));
	}

	private static Optional<S3Exception> recognise(final Throwable thrown) {
		return RECOGNISED.stream().filter(known -> known.getKey().isInstance(thrown)).findFirst()
			.map(known -> new S3Exception(known.getValue()));
	}

	private static S3Exception unexpected(final Throwable thrown) {
		log.error("Failed to serve an S3 request", thrown);
		return new S3Exception(S3ErrorCode.InternalError);
	}

	@Override
	public Response toResponse(final Throwable thrown) {
		final S3Exception error = translate(thrown);
		final Response.ResponseBuilder response = Response.status(error.getCode().getStatus());

		error.getHeaders().forEach(response::header);

		if (error.getCode() == S3ErrorCode.ServiceUnavailable) {
			response.header(HttpHeaders.RETRY_AFTER, RETRY_AFTER.toSeconds());
		}

		if (!HttpMethod.HEAD.equals(request.getMethod()) && error.getCode() != S3ErrorCode.NotModified) {
			response.type(MediaType.APPLICATION_XML_TYPE)
				.entity(new S3Error(error.getCode().name(), error.getMessage(), "/" + uri.getPath(), null));
		}

		return response.build();
	}
}

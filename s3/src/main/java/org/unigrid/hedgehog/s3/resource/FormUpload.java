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

package org.unigrid.hedgehog.s3.resource;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import lombok.RequiredArgsConstructor;
import org.unigrid.hedgehog.s3.auth.PostPolicy;
import org.unigrid.hedgehog.s3.auth.SigV4;
import org.unigrid.hedgehog.s3.checksum.ChecksumInputStream;
import org.unigrid.hedgehog.s3.entity.MalformedXmlException;
import org.unigrid.hedgehog.s3.error.S3ErrorCode;
import org.unigrid.hedgehog.s3.error.S3Exception;
import org.unigrid.hedgehog.s3.http.FormData;
import org.unigrid.hedgehog.s3.index.BlobRef;
import org.unigrid.hedgehog.s3.index.Bucket;
import org.unigrid.hedgehog.s3.index.Namespace;
import org.unigrid.hedgehog.s3.index.NamespaceException;
import org.unigrid.hedgehog.s3.index.ObjectVersion;
import org.unigrid.hedgehog.s3.spi.BlobStore;
import org.unigrid.hedgehog.s3.spi.BlobStoreException;
import org.unigrid.hedgehog.s3.spi.CredentialStore;

/*
   A browser form upload, posted to the bucket as multipart/form-data. Its fields come before the file, and the
   policy they are signed by is checked before anything is stored. The file then streams into a blob the way the
   body of PutObject does, refused as soon as it outgrows what the policy allows, and whatever follows it is ignored
   as S3 ignores it.
*/
@RequiredArgsConstructor
final class FormUpload {
	private static final int MAX_FIELD = 20 * 1024;
	private static final int MAX_FIELDS = 64;
	private static final String FILE = "file";
	private static final String FILENAME = "${filename}";
	private static final String ACL = "acl";
	private static final String TAGGING = "tagging";
	private static final String STATUS = "success_action_status";
	private static final String REDIRECT = "success_action_redirect";
	private static final Set<String> WEB = Set.of("http", "https");

	/* Every object belongs to the owner of its bucket alone, as in an S3 bucket with ACLs disabled */
	private static final Set<String> OWNER_ONLY = Set.of("private", "bucket-owner-full-control");

	private final Namespace namespace;
	private final BlobStore blobs;
	private final CredentialStore credentials;
	private final Clock clock;

	Response store(final String bucket, final ContainerRequestContext request) throws IOException, BlobStoreException,
		NamespaceException, MalformedXmlException {

		final Map<String, String> fields = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
		final FormData.Part file = fields(FormData.of(request.getEntityStream(),
			request.getHeaderString(HttpHeaders.CONTENT_TYPE)), fields
		);

		fields.computeIfPresent(PostPolicy.KEY, (name, key) -> key.replace(FILENAME, file.filename().orElse("")));

		final PostPolicy policy = PostPolicy.verify(fields, bucket, credentials, clock.instant());
		final String key = Requests.key(Optional.ofNullable(fields.get(PostPolicy.KEY)).orElseThrow(() ->
			new S3Exception(S3ErrorCode.InvalidArgument, "Bucket POST must contain a field named 'key'.")
		));

		if (fields.containsKey(ACL) && !OWNER_ONLY.contains(fields.get(ACL))) {
			throw new S3Exception(S3ErrorCode.AccessControlListNotSupported);
		}

		ObjectHeaders.refuseUnsupported(fields.keySet(), fields::get);

		final Bucket target = Requests.bucket(namespace, bucket);
		final Map<String, String> tags = fields.containsKey(TAGGING) ? Tags.field(fields.get(TAGGING)) : Map.of();
		final Instant now = Requests.now(clock);
		final ObjectVersion.ObjectVersionBuilder version = ObjectLocks.lock(ObjectVersion.builder().bucket(bucket)
			.key(key).lastModified(now).headers(ObjectHeaders.content(fields::get))
			.metadata(ObjectHeaders.metadata(fields.keySet(), fields::get)).tags(tags), target, fields::get, now
		);
		final Body body = store(file.body(), policy);
		final ObjectVersion stored = body.commit(blobs, () -> namespace.put(version.etag(body.etag())
			.parts(body.parts(now)).build(), latest -> true)
		);

		return Responses.versioned(answer(fields, request.getUriInfo().getBaseUri(), stored)
			.tag(new EntityTag(stored.etag())), target, stored).build();
	}

	/* The fields before the file, of which S3 takes no more than a few and none of them long, and the file itself */
	private static FormData.Part fields(final FormData form, final Map<String, String> fields) throws IOException {
		for (Optional<FormData.Part> part = form.next(); part.isPresent(); part = form.next()) {
			if (FILE.equalsIgnoreCase(part.get().name())) {
				return part.get();
			}

			if (fields.size() == MAX_FIELDS) {
				throw new S3Exception(S3ErrorCode.MaxPostPreDataLengthExceededError);
			}

			if (Objects.nonNull(fields.putIfAbsent(part.get().name(), value(part.get())))) {
				throw new S3Exception(S3ErrorCode.InvalidArgument, "The form field " + part.get().name()
					+ " was given more than once."
				);
			}
		}

		throw new S3Exception(S3ErrorCode.IncorrectNumberOfFilesInPostRequest);
	}

	private static String value(final FormData.Part field) throws IOException {
		final byte[] value = field.body().readNBytes(MAX_FIELD + 1);

		if (value.length > MAX_FIELD) {
			throw new S3Exception(S3ErrorCode.MaxPostPreDataLengthExceededError);
		}

		return new String(value, StandardCharsets.UTF_8);
	}

	/* Refused in the read that takes it past the most the policy allows, and at its end when it holds less than the
	   least, so a refused file is never kept. An empty file needs no blob. */
	private Body store(final InputStream file, final PostPolicy policy) throws IOException, BlobStoreException {
		final long maximum = Math.min(policy.maximumLength(), ObjectResource.MAX_SIZE);
		final ChecksumInputStream content = new ChecksumInputStream(file, Set.of(), ended -> {
			if (ended.count() < policy.minimumLength()) {
				throw new S3Exception(S3ErrorCode.EntityTooSmall);
			}
		}) {
			@Override
			public int read(final byte[] buffer, final int offset, final int length) throws IOException {
				final int read = super.read(buffer, offset, length);

				if (count() > maximum) {
					throw new S3Exception(S3ErrorCode.EntityTooLarge);
				}

				return read;
			}
		};
		final Body body = new Body(List.of(new BlobRef(blobs.store(content), 0, content.count())),
			HexFormat.of().formatHex(content.md5()), Map.of()
		);

		if (content.count() > 0) {
			return body;
		}

		body.discard(blobs);
		return new Body(List.of(), body.etag(), Map.of());
	}

	private static Response.ResponseBuilder answer(final Map<String, String> fields, final URI base,
		final ObjectVersion stored) {

		final String etag = Responses.quoted(stored.etag());
		final URI location = base.resolve(SigV4.uriEncode(stored.bucket() + "/" + stored.key(), true));
		final Optional<URI> redirect = Optional.ofNullable(fields.get(REDIRECT)).flatMap(FormUpload::web);

		if (redirect.isPresent()) {
			return Response.seeOther(redirected(redirect.get(), stored, etag));
		}

		return switch (Objects.requireNonNullElse(fields.get(STATUS), "")) {
			case "200" -> Response.ok().location(location);
			case "201" -> Response.status(Response.Status.CREATED).location(location)
				.type(MediaType.APPLICATION_XML_TYPE)
				.entity(new PostResponse(location.toString(), stored.bucket(), stored.key(), etag));
			default -> Response.noContent().location(location);
		};
	}

	/* S3 answers with the status instead of sending the browser to a target it cannot go to */
	private static Optional<URI> web(final String target) {
		try {
			final URI uri = new URI(target);

			return Optional.of(uri).filter(found -> Objects.nonNull(found.getScheme())
				&& WEB.contains(found.getScheme().toLowerCase(Locale.ROOT)));
		} catch (URISyntaxException ex) {
			return Optional.empty();
		}
	}

	/* The browser is sent on with the bucket, key and ETag of what was stored */
	private static URI redirected(final URI target, final ObjectVersion stored, final String etag) {
		final String raw = target.toString();
		final int fragment = raw.indexOf('#') < 0 ? raw.length() : raw.indexOf('#');
		final String query = "bucket=" + encoded(stored.bucket()) + "&key=" + encoded(stored.key()) + "&etag="
			+ encoded(etag);

		return URI.create(raw.substring(0, fragment) + (Objects.isNull(target.getRawQuery()) ? "?" : "&") + query
			+ raw.substring(fragment)
		);
	}

	private static String encoded(final String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}
}

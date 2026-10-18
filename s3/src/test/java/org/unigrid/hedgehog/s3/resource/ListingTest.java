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

import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import org.unigrid.hedgehog.s3.index.ObjectVersion;
import static org.unigrid.hedgehog.s3.jqwik.Expect.assertThrows;
import static org.unigrid.hedgehog.s3.resource.ObjectResourceTest.BUCKET;
import static org.unigrid.hedgehog.s3.resource.ObjectResourceTest.code;
import org.unigrid.hedgehog.s3.server.S3Fixture;
import org.unigrid.hedgehog.s3.server.S3Node;
import software.amazon.awssdk.services.s3.model.CommonPrefix;
import software.amazon.awssdk.services.s3.model.EncodingType;
import software.amazon.awssdk.services.s3.model.ListObjectsResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.S3Object;

public class ListingTest {
	private static final List<String> KEYS = Stream.concat(IntStream.range(0, 120)
		.mapToObj(i -> "k%03d".formatted(i)), Stream.of("a/1", "a/2", "a/b/3", "a/b/c/4", "b", "b/", "b//c",
		"c d+e", "ö/€ 中", "ö/z", "x&y<z>\"'", "z~*().!", "photos/2024/summer/1.jpg", "photos/2024/winter/2.jpg",
		"photos/2025/3.jpg")).toList();

	private static S3Node node() throws Exception {
		final S3Node node = ObjectResourceTest.node();

		for (String key : KEYS) {
			node.getNamespace().put(ObjectVersion.builder().bucket(BUCKET).key(key).lastModified(Instant.now())
				.etag("d41d8cd98f00b204e9800998ecf8427e").build(), latest -> true
			);
		}

		return node;
	}

	/* What S3 lists: the keys under the prefix, rolled up at the first delimiter past it */
	private static List<String> expected(final String prefix, final String delimiter) {
		final TreeSet<String> listed = new TreeSet<>();

		for (String key : KEYS.stream().filter(key -> key.startsWith(prefix)).toList()) {
			final int at = delimiter.isEmpty() ? -1 : key.indexOf(delimiter, prefix.length());
			listed.add(at < 0 ? key : key.substring(0, at + delimiter.length()));
		}

		return new ArrayList<>(listed);
	}

	private static List<String> listV2(final S3Node node, final String prefix, final String delimiter,
		final int pageSize, final EncodingType encoding) {

		final TreeSet<String> listed = new TreeSet<>();
		String token = null;

		do {
			final String resume = token;
			final ListObjectsV2Response page = node.getClient().listObjectsV2(request -> request.bucket(BUCKET)
				.prefix(prefix).delimiter(delimiter.isEmpty() ? null : delimiter).maxKeys(pageSize)
				.continuationToken(resume).encodingType(encoding));

			assertThat(page.keyCount(), equalTo(page.contents().size() + page.commonPrefixes().size()));
			assertThat(page.keyCount() <= pageSize, equalTo(true));
			page.contents().forEach(object -> listed.add(object.key()));
			page.commonPrefixes().forEach(common -> listed.add(common.prefix()));
			token = page.nextContinuationToken();
			assertThat(Objects.nonNull(token), equalTo(page.isTruncated()));
		} while (Objects.nonNull(token));

		return new ArrayList<>(listed);
	}

	private static List<String> listV1(final S3Node node, final String prefix, final String delimiter,
		final int pageSize) {

		final List<String> listed = new ArrayList<>();
		String marker = null;

		do {
			final String resume = marker;
			final ListObjectsResponse page = node.getClient().listObjects(request -> request.bucket(BUCKET)
				.prefix(prefix).delimiter(delimiter.isEmpty() ? null : delimiter).maxKeys(pageSize).marker(resume));

			Stream.concat(page.contents().stream().map(S3Object::key), page.commonPrefixes().stream()
				.map(CommonPrefix::prefix)).sorted().forEach(listed::add);
			marker = page.isTruncated() ? page.nextMarker() : null;
		} while (Objects.nonNull(marker));

		return listed;
	}

	@Example
	public void listsEveryKeyAcrossPagesInBothVersions() throws Exception {
		try (S3Node node = node()) {
			for (String[] query : new String[][] { { "", "" }, { "", "/" }, { "a/", "/" }, { "photos/", "/" },
				{ "photos/2024/", "/" }, { "k1", "" }, { "b", "/" }, { "ö", "/" }, { "", "2" }, { "none", "/" } }) {

				final List<String> expected = expected(query[0], query[1]);

				for (int pageSize : new int[] { 1, 7, 1000 }) {
					assertThat(listV2(node, query[0], query[1], pageSize, null), equalTo(expected));
					assertThat(listV1(node, query[0], query[1], pageSize), equalTo(expected));
				}
			}
		}
	}

	@Example
	public void encodesWhatItListsWhenAsked() throws Exception {
		try (S3Node node = node()) {
			assertThat(listV2(node, "", "/", 4, EncodingType.URL), equalTo(expected("", "/")));
			assertThat(listV2(node, "ö/", "/", 1000, EncodingType.URL), equalTo(expected("ö/", "/")));

			final HttpURLConnection connection = S3Fixture.signed(node.getPort(), "GET", "/" + BUCKET
				+ "?list-type=2&encoding-type=url&prefix=c");

			assertThat(new String(connection.getInputStream().readAllBytes(), StandardCharsets.UTF_8),
				containsString("<Prefix>c</Prefix><MaxKeys>1000</MaxKeys><IsTruncated>false</IsTruncated>"
				+ "<EncodingType>url</EncodingType><KeyCount>1</KeyCount><Contents><Key>c+d%2Be</Key>")
			);
		}
	}

	@Example
	public void startsAfterTheKeyItIsGiven() throws Exception {
		try (S3Node node = node()) {
			final ListObjectsV2Response page = node.getClient().listObjectsV2(request -> request.bucket(BUCKET)
				.startAfter("k117").maxKeys(3));

			assertThat(page.contents().stream().map(S3Object::key).toList(),
				contains("k118", "k119", "photos/2024/summer/1.jpg")
			);
			assertThat(page.startAfter(), equalTo("k117"));
			assertThat(page.isTruncated(), equalTo(true));
		}
	}

	@Example
	public void describesEachObjectAndItsOwnerAsAsked() throws Exception {
		try (S3Node node = ObjectResourceTest.node()) {
			ObjectResourceTest.put(node.getClient(), "key", "content".getBytes(StandardCharsets.UTF_8));

			final S3Object listed = node.getClient().listObjectsV2(request -> request.bucket(BUCKET)).contents()
				.getFirst();
			final S3Object owned = node.getClient().listObjectsV2(request -> request.bucket(BUCKET).fetchOwner(true))
				.contents().getFirst();
			final S3Object v1 = node.getClient().listObjects(request -> request.bucket(BUCKET)).contents().getFirst();

			assertThat(listed.key(), equalTo("key"));
			assertThat(listed.size(), equalTo(7L));
			assertThat(listed.eTag(), equalTo(ObjectResourceTest.md5("content".getBytes(StandardCharsets.UTF_8))));
			assertThat(listed.storageClassAsString(), equalTo("STANDARD"));
			assertThat(listed.owner(), nullValue());
			assertThat(owned.owner().id(), equalTo("hedgehog"));
			assertThat(v1.owner().id(), equalTo("hedgehog"));
			assertThat(listed.lastModified(), equalTo(v1.lastModified()));
		}
	}

	@Example
	public void refusesWhatItCannotList() throws Exception {
		try (S3Node node = node()) {
			assertThrows(NoSuchBucketException.class, () -> node.getClient().listObjectsV2(request -> request
				.bucket("nowhere"))
			);
			assertThat(code(() -> node.getClient().listObjectsV2(request -> request.bucket(BUCKET)
				.continuationToken("%%%"))), equalTo("InvalidArgument")
			);
			assertThat(code(() -> node.getClient().listObjectsV2(request -> request.bucket(BUCKET).maxKeys(-1))),
				equalTo("InvalidArgument")
			);
			assertThat(code(() -> node.getClient().listObjects(request -> request.bucket(BUCKET)
				.encodingType("base64"))), equalTo("InvalidArgument")
			);
			assertThat(node.getClient().listObjectsV2(request -> request.bucket(BUCKET).maxKeys(0)).contents(),
				empty()
			);
		}
	}
}

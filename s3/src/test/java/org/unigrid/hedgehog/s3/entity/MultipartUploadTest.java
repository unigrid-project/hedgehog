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

package org.unigrid.hedgehog.s3.entity;

import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.From;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

public class MultipartUploadTest {
	private final JAXBContext context;

	public MultipartUploadTest() throws JAXBException {
		context = JAXBContext.newInstance(InitiateMultipartUploadResult.class, CompleteMultipartUpload.class,
			CompleteMultipartUploadResult.class);
	}

	private String marshal(final Object value) throws JAXBException {
		final StringWriter writer = new StringWriter();

		context.createMarshaller().marshal(value, writer);
		return writer.toString();
	}

	private <T> T unmarshal(final String xml, final Class<T> type) throws JAXBException {
		return type.cast(context.createUnmarshaller().unmarshal(new StringReader(xml)));
	}

	@Provide
	Arbitrary<String> names() {
		return Arbitraries.strings().alpha().numeric().withChars("._-/").ofMinLength(1).ofMaxLength(40);
	}

	@Provide
	Arbitrary<Integer> partNumbers() {
		return Arbitraries.integers().between(1, 10_000);
	}

	@Property
	public void readsBackTheInitiateResultItWrote(@ForAll("names") final String bucket,
		@ForAll("names") final String key, @ForAll("names") final String uploadId) throws JAXBException {
		final InitiateMultipartUploadResult result = new InitiateMultipartUploadResult(bucket, key, uploadId);

		assertThat(unmarshal(marshal(result), InitiateMultipartUploadResult.class), equalTo(result));
	}

	@Property
	public void readsBackTheCompleteRequestItWrote(
		@ForAll final List<@From("partNumbers") Integer> partNumbers) throws JAXBException {
		final CompleteMultipartUpload request = new CompleteMultipartUpload(partNumbers.stream()
			.map(this::part).toList());

		assertThat(unmarshal(marshal(request), CompleteMultipartUpload.class), equalTo(request));
	}

	private Part part(final Integer partNumber) {
		return new Part(partNumber, null, "\"etag-" + partNumber + "\"", null, "crc32", null, null, null, null);
	}

	@Property
	public void readsBackTheCompleteResultItWrote(@ForAll("names") final String key) throws JAXBException {
		final CompleteMultipartUploadResult result = new CompleteMultipartUploadResult("https://example/" + key,
			"photos", key, "\"etag\"", "crc32", "crc32c", "crc64", "sha1", "sha256", "COMPOSITE");

		assertThat(unmarshal(marshal(result), CompleteMultipartUploadResult.class), equalTo(result));
	}

	@Example
	public void writesTheInitiateResultShape() throws JAXBException {
		assertThat(marshal(new InitiateMultipartUploadResult("photos", "a.jpg", "upload-1")), containsString(
			"<InitiateMultipartUploadResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">"
			+ "<Bucket>photos</Bucket><Key>a.jpg</Key><UploadId>upload-1</UploadId>"
			+ "</InitiateMultipartUploadResult>"
		));
	}

	@Example
	public void writesTheCompleteRequestShape() throws JAXBException {
		final CompleteMultipartUpload request = new CompleteMultipartUpload(List.of(
			new Part(1, null, "\"e1\"", null, "c1", null, null, null, null),
			new Part(2, null, "\"e2\"", null, null, null, null, null, null)));

		assertThat(marshal(request), containsString(
			"<CompleteMultipartUpload xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">"
			+ "<Part><PartNumber>1</PartNumber><ETag>\"e1\"</ETag><ChecksumCRC32>c1</ChecksumCRC32></Part>"
			+ "<Part><PartNumber>2</PartNumber><ETag>\"e2\"</ETag></Part></CompleteMultipartUpload>"
		));
	}

	@Example
	public void writesTheCompleteResultShape() throws JAXBException {
		final CompleteMultipartUploadResult result = new CompleteMultipartUploadResult("https://example/a.jpg",
			"photos", "a.jpg", "\"etag\"", "crc32", "crc32c", "crc64", "sha1", "sha256", "COMPOSITE");

		assertThat(marshal(result), containsString(
			"<CompleteMultipartUploadResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">"
			+ "<Location>https://example/a.jpg</Location><Bucket>photos</Bucket><Key>a.jpg</Key>"
			+ "<ETag>\"etag\"</ETag><ChecksumCRC32>crc32</ChecksumCRC32>"
			+ "<ChecksumCRC32C>crc32c</ChecksumCRC32C><ChecksumCRC64NVME>crc64</ChecksumCRC64NVME>"
			+ "<ChecksumSHA1>sha1</ChecksumSHA1><ChecksumSHA256>sha256</ChecksumSHA256>"
			+ "<ChecksumType>COMPOSITE</ChecksumType></CompleteMultipartUploadResult>"
		));
	}
}

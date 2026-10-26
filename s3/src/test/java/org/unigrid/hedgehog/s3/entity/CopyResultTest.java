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
import java.time.Instant;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

public class CopyResultTest {
	private static final long YEAR_9999 = Instant.parse("9999-12-31T23:59:59.999Z").toEpochMilli();

	private final JAXBContext context;

	public CopyResultTest() throws JAXBException {
		context = JAXBContext.newInstance(CopyObjectResult.class, CopyPartResult.class);
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
	Arbitrary<String> eTags() {
		return Arbitraries.strings().alpha().numeric().ofMinLength(1).ofMaxLength(20);
	}

	@Provide
	Arbitrary<Instant> timestamps() {
		return Arbitraries.longs().between(0, YEAR_9999).map(Instant::ofEpochMilli);
	}

	@Property
	public void readsBackTheCopyObjectResultItWrote(@ForAll("eTags") final String eTag,
		@ForAll("timestamps") final Instant lastModified) throws JAXBException {
		final CopyObjectResult result = new CopyObjectResult(eTag, lastModified, "crc32", "crc32c", "crc64",
			"sha1", "sha256");

		assertThat(unmarshal(marshal(result), CopyObjectResult.class), equalTo(result));
	}

	@Property
	public void readsBackTheCopyPartResultItWrote(@ForAll("eTags") final String eTag,
		@ForAll("timestamps") final Instant lastModified) throws JAXBException {
		final CopyPartResult result = new CopyPartResult(eTag, lastModified, "crc32", "crc32c", "crc64",
			"sha1", "sha256");

		assertThat(unmarshal(marshal(result), CopyPartResult.class), equalTo(result));
	}

	@Example
	public void writesTheCopyObjectResultShape() throws JAXBException {
		final CopyObjectResult result = new CopyObjectResult("\"etag\"", Instant.EPOCH, "crc32", "crc32c",
			"crc64", "sha1", "sha256");

		assertThat(marshal(result), containsString(
			"<CopyObjectResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"><ETag>\"etag\"</ETag>"
			+ "<LastModified>1970-01-01T00:00:00.000Z</LastModified><ChecksumCRC32>crc32</ChecksumCRC32>"
			+ "<ChecksumCRC32C>crc32c</ChecksumCRC32C><ChecksumCRC64NVME>crc64</ChecksumCRC64NVME>"
			+ "<ChecksumSHA1>sha1</ChecksumSHA1><ChecksumSHA256>sha256</ChecksumSHA256></CopyObjectResult>"
		));
	}

	@Example
	public void writesTheCopyPartResultShape() throws JAXBException {
		final CopyPartResult result = new CopyPartResult("\"etag\"", Instant.EPOCH, "crc32", "crc32c",
			"crc64", "sha1", "sha256");

		assertThat(marshal(result), containsString(
			"<CopyPartResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"><ETag>\"etag\"</ETag>"
			+ "<LastModified>1970-01-01T00:00:00.000Z</LastModified><ChecksumCRC32>crc32</ChecksumCRC32>"
			+ "<ChecksumCRC32C>crc32c</ChecksumCRC32C><ChecksumCRC64NVME>crc64</ChecksumCRC64NVME>"
			+ "<ChecksumSHA1>sha1</ChecksumSHA1><ChecksumSHA256>sha256</ChecksumSHA256></CopyPartResult>"
		));
	}
}

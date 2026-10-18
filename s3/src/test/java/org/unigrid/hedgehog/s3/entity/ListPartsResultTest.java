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
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

public class ListPartsResultTest {
	private static final long YEAR_9999 = Instant.parse("9999-12-31T23:59:59.999Z").toEpochMilli();

	private final JAXBContext context;

	public ListPartsResultTest() throws JAXBException {
		context = JAXBContext.newInstance(ListPartsResult.class);
	}

	private String marshal(final ListPartsResult result) throws JAXBException {
		final StringWriter writer = new StringWriter();

		context.createMarshaller().marshal(result, writer);
		return writer.toString();
	}

	private ListPartsResult unmarshal(final String xml) throws JAXBException {
		return (ListPartsResult) context.createUnmarshaller().unmarshal(new StringReader(xml));
	}

	@Provide
	Arbitrary<String> names() {
		return Arbitraries.strings().alpha().numeric().withChars("._-/").ofMinLength(1).ofMaxLength(40);
	}

	@Provide
	Arbitrary<Instant> timestamps() {
		return Arbitraries.longs().between(0, YEAR_9999).map(Instant::ofEpochMilli);
	}

	@Property
	public void readsBackTheListPartsResultItWrote(@ForAll("names") final String key,
		@ForAll("timestamps") final Instant lastModified) throws JAXBException {
		final Part part = new Part(1, lastModified, "\"etag\"", 10L, "crc32", "crc32c", "crc64", "sha1", "sha256");
		final ListPartsResult result = new ListPartsResult("photos", key, "upload-1", 0, 1, 1000, false,
			List.of(part), new Owner("initiator", "Initiator"), new Owner("owner", "Owner"), "STANDARD", "CRC32",
			"COMPOSITE");

		assertThat(unmarshal(marshal(result)), equalTo(result));
	}

	@Example
	public void writesTheListPartsResultShape() throws JAXBException {
		final Part part = new Part(1, Instant.EPOCH, "\"etag\"", 10L, "crc32", null, null, null, null);
		final ListPartsResult result = new ListPartsResult("photos", "a.jpg", "upload-1", 0, 1, 1000, false,
			List.of(part), new Owner("initiator", "Initiator"), new Owner("owner", "Owner"), "STANDARD", "CRC32",
			"COMPOSITE");

		assertThat(marshal(result), containsString(
			"<ListPartsResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"><Bucket>photos</Bucket>"
			+ "<Key>a.jpg</Key><UploadId>upload-1</UploadId><PartNumberMarker>0</PartNumberMarker>"
			+ "<NextPartNumberMarker>1</NextPartNumberMarker><MaxParts>1000</MaxParts>"
			+ "<IsTruncated>false</IsTruncated><Part><PartNumber>1</PartNumber>"
			+ "<LastModified>1970-01-01T00:00:00.000Z</LastModified><ETag>\"etag\"</ETag><Size>10</Size>"
			+ "<ChecksumCRC32>crc32</ChecksumCRC32></Part><Initiator><ID>initiator</ID>"
			+ "<DisplayName>Initiator</DisplayName></Initiator><Owner><ID>owner</ID>"
			+ "<DisplayName>Owner</DisplayName></Owner><StorageClass>STANDARD</StorageClass>"
			+ "<ChecksumAlgorithm>CRC32</ChecksumAlgorithm><ChecksumType>COMPOSITE</ChecksumType>"
			+ "</ListPartsResult>"
		));
	}
}

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

public class ListMultipartUploadsResultTest {
	private static final long YEAR_9999 = Instant.parse("9999-12-31T23:59:59.999Z").toEpochMilli();

	private final JAXBContext context;

	public ListMultipartUploadsResultTest() throws JAXBException {
		context = JAXBContext.newInstance(ListMultipartUploadsResult.class);
	}

	private String marshal(final ListMultipartUploadsResult result) throws JAXBException {
		final StringWriter writer = new StringWriter();

		context.createMarshaller().marshal(result, writer);
		return writer.toString();
	}

	private ListMultipartUploadsResult unmarshal(final String xml) throws JAXBException {
		return (ListMultipartUploadsResult) context.createUnmarshaller().unmarshal(new StringReader(xml));
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
	public void readsBackTheListMultipartUploadsResultItWrote(@ForAll("names") final String key,
		@ForAll("timestamps") final Instant initiated) throws JAXBException {
		final Upload upload = new Upload(key, "upload-1", new Owner("initiator", "Initiator"),
			new Owner("owner", "Owner"), "STANDARD", initiated, "CRC32");
		final ListMultipartUploadsResult result = new ListMultipartUploadsResult("photos", "key-marker",
			"upload-marker", "next-key-marker", "next-upload-marker", "2024/", "/", 1000, true, "url",
			List.of(upload), List.of(new CommonPrefix("2024/01/")));

		assertThat(unmarshal(marshal(result)), equalTo(result));
	}

	@Example
	public void writesTheListMultipartUploadsResultShape() throws JAXBException {
		final Upload upload = new Upload("a.jpg", "upload-1", new Owner("initiator", "Initiator"),
			new Owner("owner", "Owner"), "STANDARD", Instant.EPOCH, "CRC32");
		final ListMultipartUploadsResult result = new ListMultipartUploadsResult("photos", "key-marker",
			"upload-marker", "next-key-marker", "next-upload-marker", "2024/", "/", 1000, true, "url",
			List.of(upload), List.of(new CommonPrefix("2024/01/")));

		assertThat(marshal(result), containsString(
			"<ListMultipartUploadsResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">"
			+ "<Bucket>photos</Bucket><KeyMarker>key-marker</KeyMarker>"
			+ "<UploadIdMarker>upload-marker</UploadIdMarker>"
			+ "<NextKeyMarker>next-key-marker</NextKeyMarker><NextUploadIdMarker>next-upload-marker"
			+ "</NextUploadIdMarker><Prefix>2024/</Prefix><Delimiter>/</Delimiter><MaxUploads>1000</MaxUploads>"
			+ "<IsTruncated>true</IsTruncated><EncodingType>url</EncodingType><Upload><Key>a.jpg</Key>"
			+ "<UploadId>upload-1</UploadId><Initiator><ID>initiator</ID><DisplayName>Initiator</DisplayName>"
			+ "</Initiator><Owner><ID>owner</ID><DisplayName>Owner</DisplayName></Owner>"
			+ "<StorageClass>STANDARD</StorageClass><Initiated>1970-01-01T00:00:00.000Z</Initiated>"
			+ "<ChecksumAlgorithm>CRC32</ChecksumAlgorithm></Upload><CommonPrefixes><Prefix>2024/01/</Prefix>"
			+ "</CommonPrefixes></ListMultipartUploadsResult>"
		));
	}
}

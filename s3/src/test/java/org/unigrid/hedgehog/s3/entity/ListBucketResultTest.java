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
import static org.hamcrest.Matchers.not;

public class ListBucketResultTest {
	private static final long YEAR_9999 = Instant.parse("9999-12-31T23:59:59.999Z").toEpochMilli();

	private final JAXBContext context;

	public ListBucketResultTest() throws JAXBException {
		context = JAXBContext.newInstance(ListBucketResult.class);
	}

	private String marshal(final ListBucketResult result) throws JAXBException {
		final StringWriter writer = new StringWriter();

		context.createMarshaller().marshal(result, writer);
		return writer.toString();
	}

	private ListBucketResult unmarshal(final String xml) throws JAXBException {
		return (ListBucketResult) context.createUnmarshaller().unmarshal(new StringReader(xml));
	}

	@Provide
	Arbitrary<String> keys() {
		return Arbitraries.strings().alpha().numeric().withChars("._-/").ofMinLength(1).ofMaxLength(40);
	}

	@Provide
	Arbitrary<Instant> timestamps() {
		return Arbitraries.longs().between(0, YEAR_9999).map(Instant::ofEpochMilli);
	}

	@Property
	public void readsBackTheListingItWrote(@ForAll("keys") final String key,
		@ForAll("timestamps") final Instant lastModified) throws JAXBException {
		final ObjectSummary summary = new ObjectSummary(key, lastModified, "\"etag\"", List.of("CRC32", "SHA1"),
			"COMPOSITE", 42L, "STANDARD", new Owner("owner-id", "Owner Name"));
		final ListBucketResult result = new ListBucketResult("photos", "2024/", "marker", "next-marker", 1000, "/",
			true, "url", List.of(summary), List.of(new CommonPrefix("2024/01/")));

		assertThat(unmarshal(marshal(result)), equalTo(result));
	}

	@Example
	public void writesFlatContentsAndCommonPrefixes() throws JAXBException {
		final ObjectSummary summary = new ObjectSummary("2024/01/a.jpg", Instant.EPOCH, "\"etag\"",
			List.of("CRC32"), null, 7L, "STANDARD", null);
		final ListBucketResult result = new ListBucketResult("photos", "2024/", "marker", "next", 2, "/", false,
			null, List.of(summary), List.of(new CommonPrefix("2024/02/")));
		final String xml = marshal(result);

		assertThat(xml, containsString(
			"<ListBucketResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"><Name>photos</Name>"
			+ "<Prefix>2024/</Prefix><Marker>marker</Marker><NextMarker>next</NextMarker><MaxKeys>2</MaxKeys>"
			+ "<Delimiter>/</Delimiter><IsTruncated>false</IsTruncated><Contents><Key>2024/01/a.jpg</Key>"
			+ "<LastModified>1970-01-01T00:00:00.000Z</LastModified><ETag>\"etag\"</ETag>"
			+ "<ChecksumAlgorithm>CRC32</ChecksumAlgorithm><Size>7</Size><StorageClass>STANDARD</StorageClass>"
			+ "</Contents><CommonPrefixes><Prefix>2024/02/</Prefix></CommonPrefixes></ListBucketResult>"
		));
		assertThat(xml, not(containsString("<EncodingType>")));
		assertThat(xml, not(containsString("<Owner>")));
	}
}

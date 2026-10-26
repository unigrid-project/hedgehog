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

public class ListBucketResultV2Test {
	private static final long YEAR_9999 = Instant.parse("9999-12-31T23:59:59.999Z").toEpochMilli();

	private final JAXBContext context;

	public ListBucketResultV2Test() throws JAXBException {
		context = JAXBContext.newInstance(ListBucketResultV2.class);
	}

	private String marshal(final ListBucketResultV2 result) throws JAXBException {
		final StringWriter writer = new StringWriter();

		context.createMarshaller().marshal(result, writer);
		return writer.toString();
	}

	private ListBucketResultV2 unmarshal(final String xml) throws JAXBException {
		return (ListBucketResultV2) context.createUnmarshaller().unmarshal(new StringReader(xml));
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
		final ObjectSummary summary = new ObjectSummary(key, lastModified, "\"etag\"", List.of("CRC32"),
			"COMPOSITE", 42L, "STANDARD", new Owner("owner-id", "Owner Name"));
		final ListBucketResultV2 result = new ListBucketResultV2("photos", "2024/", 1000, "/", false, "url", 1,
			"token", "next-token", "2024/", List.of(summary), List.of(new CommonPrefix("2024/01/")));

		assertThat(unmarshal(marshal(result)), equalTo(result));
	}

	@Example
	public void writesTheVersionTwoListingShape() throws JAXBException {
		final ObjectSummary summary = new ObjectSummary("2024/01/a.jpg", Instant.EPOCH, "\"etag\"",
			List.of(), null, 7L, null, null);
		final ListBucketResultV2 result = new ListBucketResultV2("photos", "2024/", 2, "/", true, "url", 1,
			"token", "next-token", "2024/01/a.jpg", List.of(summary), List.of(new CommonPrefix("2024/02/")));

		assertThat(marshal(result), containsString(
			"<ListBucketResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"><Name>photos</Name>"
			+ "<Prefix>2024/</Prefix><MaxKeys>2</MaxKeys><Delimiter>/</Delimiter><IsTruncated>true</IsTruncated>"
			+ "<EncodingType>url</EncodingType><KeyCount>1</KeyCount>"
			+ "<ContinuationToken>token</ContinuationToken>"
			+ "<NextContinuationToken>next-token</NextContinuationToken><StartAfter>2024/01/a.jpg</StartAfter>"
			+ "<Contents><Key>2024/01/a.jpg</Key><LastModified>1970-01-01T00:00:00.000Z</LastModified>"
			+ "<ETag>\"etag\"</ETag><Size>7</Size></Contents><CommonPrefixes><Prefix>2024/02/</Prefix>"
			+ "</CommonPrefixes></ListBucketResult>"
		));
	}
}

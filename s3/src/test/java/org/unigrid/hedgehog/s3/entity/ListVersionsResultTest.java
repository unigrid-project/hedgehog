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

public class ListVersionsResultTest {
	private static final long YEAR_9999 = Instant.parse("9999-12-31T23:59:59.999Z").toEpochMilli();

	private final JAXBContext context;

	public ListVersionsResultTest() throws JAXBException {
		context = JAXBContext.newInstance(ListVersionsResult.class);
	}

	private String marshal(final ListVersionsResult result) throws JAXBException {
		final StringWriter writer = new StringWriter();

		context.createMarshaller().marshal(result, writer);
		return writer.toString();
	}

	private ListVersionsResult unmarshal(final String xml) throws JAXBException {
		return (ListVersionsResult) context.createUnmarshaller().unmarshal(new StringReader(xml));
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
	public void readsBackTheVersionListingItWrote(@ForAll("keys") final String key,
		@ForAll("timestamps") final Instant lastModified) throws JAXBException {
		final Owner owner = new Owner("owner-id", "Owner Name");
		final List<Object> entries = List.of(
			new Version(key, "v2", true, lastModified, "\"e2\"", 20L, "STANDARD", owner, List.of("CRC32")),
			new DeleteMarker(key, "v1", false, lastModified, owner),
			new Version(key, "v0", false, lastModified, "\"e0\"", 10L, "STANDARD", owner, List.of()));
		final ListVersionsResult result = new ListVersionsResult("photos", "2024/", "key-marker",
			"version-marker", "next-key-marker", "next-version-marker", 1000, "/", true, "url", entries,
			List.of(new CommonPrefix("2024/01/")));

		assertThat(unmarshal(marshal(result)), equalTo(result));
	}

	@Example
	public void writesVersionsAndDeleteMarkersInListOrder() throws JAXBException {
		final Owner owner = new Owner("id", "name");
		final ListVersionsResult result = new ListVersionsResult("photos", null, null, null, null, null, 1000,
			null, false, null,
			List.of(new Version("a.jpg", "v2", true, Instant.EPOCH, "\"e2\"", 10L, "STANDARD", owner, List.of()),
				new DeleteMarker("a.jpg", "v1", false, Instant.EPOCH, owner)),
			List.of());

		assertThat(marshal(result), containsString(
			"<ListVersionsResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"><Name>photos</Name>"
			+ "<MaxKeys>1000</MaxKeys><IsTruncated>false</IsTruncated><Version><Key>a.jpg</Key>"
			+ "<VersionId>v2</VersionId><IsLatest>true</IsLatest>"
			+ "<LastModified>1970-01-01T00:00:00.000Z</LastModified><ETag>\"e2\"</ETag><Size>10</Size>"
			+ "<StorageClass>STANDARD</StorageClass><Owner><ID>id</ID><DisplayName>name</DisplayName></Owner>"
			+ "</Version><DeleteMarker><Key>a.jpg</Key><VersionId>v1</VersionId><IsLatest>false</IsLatest>"
			+ "<LastModified>1970-01-01T00:00:00.000Z</LastModified><Owner><ID>id</ID>"
			+ "<DisplayName>name</DisplayName></Owner></DeleteMarker></ListVersionsResult>"
		));
	}
}

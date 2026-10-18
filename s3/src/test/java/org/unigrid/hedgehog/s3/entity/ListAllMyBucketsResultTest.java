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
import net.jqwik.api.Combinators;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.From;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

public class ListAllMyBucketsResultTest {
	private static final long YEAR_9999 = Instant.parse("9999-12-31T23:59:59.999Z").toEpochMilli();

	private final JAXBContext context;

	public ListAllMyBucketsResultTest() throws JAXBException {
		context = JAXBContext.newInstance(ListAllMyBucketsResult.class);
	}

	private String marshal(final ListAllMyBucketsResult result) throws JAXBException {
		final StringWriter writer = new StringWriter();

		context.createMarshaller().marshal(result, writer);
		return writer.toString();
	}

	private ListAllMyBucketsResult unmarshal(final String xml) throws JAXBException {
		return (ListAllMyBucketsResult) context.createUnmarshaller().unmarshal(new StringReader(xml));
	}

	@Provide
	Arbitrary<Bucket> buckets() {
		return Combinators.combine(Arbitraries.strings().withCharRange('a', 'z').numeric().withChars(".-")
			.ofMinLength(3).ofMaxLength(63), Arbitraries.longs().between(0, YEAR_9999))
			.as((name, millis) -> new Bucket(Instant.ofEpochMilli(millis), name));
	}

	@Property
	public void readsBackTheBucketsItWrote(@ForAll final List<@From("buckets") Bucket> buckets) throws JAXBException {
		final ListAllMyBucketsResult result = new ListAllMyBucketsResult(buckets);

		assertThat(unmarshal(marshal(result)), equalTo(result));
	}

	@Example
	public void writesBucketsInTheS3Namespace() throws JAXBException {
		final Bucket bucket = new Bucket(Instant.EPOCH, "photos");

		assertThat(marshal(new ListAllMyBucketsResult(List.of(bucket))), containsString(
			"<ListAllMyBucketsResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"><Buckets><Bucket>"
			+ "<CreationDate>1970-01-01T00:00:00.000Z</CreationDate><Name>photos</Name></Bucket></Buckets>"
			+ "</ListAllMyBucketsResult>"
		));
	}
}

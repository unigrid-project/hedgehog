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

public class DeleteTest {
	private final JAXBContext context;

	public DeleteTest() throws JAXBException {
		context = JAXBContext.newInstance(Delete.class, DeleteResult.class);
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
	Arbitrary<String> keys() {
		return Arbitraries.strings().alpha().numeric().withChars("._-/").ofMinLength(1).ofMaxLength(40);
	}

	@Property
	public void readsBackTheDeleteRequestItWrote(@ForAll final List<@From("keys") String> keys,
		@ForAll final boolean quiet) throws JAXBException {
		final Delete delete = new Delete(quiet, keys.stream().map(key -> new DeleteObject(key, "v1")).toList());

		assertThat(unmarshal(marshal(delete), Delete.class), equalTo(delete));
	}

	@Property
	public void readsBackTheDeleteResultItWrote(@ForAll final List<@From("keys") String> keys) throws JAXBException {
		final DeleteResult result = new DeleteResult(
			keys.stream().map(key -> new DeletedObject(key, "v1", true, "v0")).toList(),
			keys.stream().map(key -> new DeleteError(key, null, "AccessDenied", "denied")).toList());

		assertThat(unmarshal(marshal(result), DeleteResult.class), equalTo(result));
	}

	@Example
	public void writesTheDeleteRequestShape() throws JAXBException {
		final Delete delete = new Delete(true, List.of(new DeleteObject("a.jpg", "v1"),
			new DeleteObject("b.jpg", null)));

		assertThat(marshal(delete), containsString(
			"<Delete xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"><Quiet>true</Quiet>"
			+ "<Object><Key>a.jpg</Key><VersionId>v1</VersionId></Object>"
			+ "<Object><Key>b.jpg</Key></Object></Delete>"
		));
	}

	@Example
	public void writesTheDeleteResultShape() throws JAXBException {
		final DeleteResult result = new DeleteResult(
			List.of(new DeletedObject("a.jpg", "v1", true, "v0")),
			List.of(new DeleteError("b.jpg", null, "AccessDenied", "denied")));

		assertThat(marshal(result), containsString(
			"<DeleteResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"><Deleted><Key>a.jpg</Key>"
			+ "<VersionId>v1</VersionId><DeleteMarker>true</DeleteMarker>"
			+ "<DeleteMarkerVersionId>v0</DeleteMarkerVersionId></Deleted><Error><Key>b.jpg</Key>"
			+ "<Code>AccessDenied</Code><Message>denied</Message></Error></DeleteResult>"
		));
	}
}

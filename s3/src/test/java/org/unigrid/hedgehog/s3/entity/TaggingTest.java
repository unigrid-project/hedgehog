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

public class TaggingTest {
	private final JAXBContext context;

	public TaggingTest() throws JAXBException {
		context = JAXBContext.newInstance(Tagging.class);
	}

	private String marshal(final Tagging tagging) throws JAXBException {
		final StringWriter writer = new StringWriter();

		context.createMarshaller().marshal(tagging, writer);
		return writer.toString();
	}

	private Tagging unmarshal(final String xml) throws JAXBException {
		return (Tagging) context.createUnmarshaller().unmarshal(new StringReader(xml));
	}

	@Provide
	Arbitrary<String> keys() {
		return Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(20);
	}

	@Property
	public void readsBackTheTaggingItWrote(@ForAll final List<@From("keys") String> keys) throws JAXBException {
		final Tagging tagging = new Tagging(keys.stream().map(key -> new Tag(key, "value-" + key)).toList());

		assertThat(unmarshal(marshal(tagging)), equalTo(tagging));
	}

	@Example
	public void writesTagsInsideTheTagSetWrapper() throws JAXBException {
		final Tagging tagging = new Tagging(List.of(new Tag("env", "prod"), new Tag("team", "s3")));

		assertThat(marshal(tagging), containsString(
			"<Tagging xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"><TagSet>"
			+ "<Tag><Key>env</Key><Value>prod</Value></Tag><Tag><Key>team</Key><Value>s3</Value></Tag>"
			+ "</TagSet></Tagging>"
		));
	}
}

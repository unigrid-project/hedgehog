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
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

public class BucketConfigurationTest {
	private final JAXBContext context;

	public BucketConfigurationTest() throws JAXBException {
		context = JAXBContext.newInstance(CreateBucketConfiguration.class, LocationConstraint.class);
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
	Arbitrary<String> locations() {
		return Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(20);
	}

	@Property
	public void readsBackTheCreateBucketConfigurationItWrote(@ForAll("locations") final String location)
		throws JAXBException {
		final CreateBucketConfiguration configuration = new CreateBucketConfiguration(location);

		assertThat(unmarshal(marshal(configuration), CreateBucketConfiguration.class), equalTo(configuration));
	}

	@Property
	public void readsBackTheLocationConstraintItWrote(@ForAll("locations") final String location)
		throws JAXBException {
		final LocationConstraint constraint = new LocationConstraint(location);

		assertThat(unmarshal(marshal(constraint), LocationConstraint.class), equalTo(constraint));
	}

	@Example
	public void writesTheLocationConstraintAsText() throws JAXBException {
		assertThat(marshal(new LocationConstraint("eu-west-1")), containsString(
			"<LocationConstraint xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">"
			+ "eu-west-1</LocationConstraint>"
		));
	}

	@Example
	public void writesTheCreateBucketConfigurationShape() throws JAXBException {
		assertThat(marshal(new CreateBucketConfiguration("eu-west-1")), containsString(
			"<CreateBucketConfiguration xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">"
			+ "<LocationConstraint>eu-west-1</LocationConstraint></CreateBucketConfiguration>"
		));
	}
}

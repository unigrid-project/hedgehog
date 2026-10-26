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

public class VersioningConfigurationTest {
	private final JAXBContext context;

	public VersioningConfigurationTest() throws JAXBException {
		context = JAXBContext.newInstance(VersioningConfiguration.class);
	}

	private String marshal(final VersioningConfiguration configuration) throws JAXBException {
		final StringWriter writer = new StringWriter();

		context.createMarshaller().marshal(configuration, writer);
		return writer.toString();
	}

	private VersioningConfiguration unmarshal(final String xml) throws JAXBException {
		return (VersioningConfiguration) context.createUnmarshaller().unmarshal(new StringReader(xml));
	}

	@Provide
	Arbitrary<String> statuses() {
		return Arbitraries.of("Enabled", "Suspended");
	}

	@Provide
	Arbitrary<String> mfaStates() {
		return Arbitraries.of("Enabled", "Disabled");
	}

	@Property
	public void readsBackTheVersioningConfigurationItWrote(@ForAll("statuses") final String status,
		@ForAll("mfaStates") final String mfaDelete) throws JAXBException {
		final VersioningConfiguration configuration = new VersioningConfiguration(status, mfaDelete);

		assertThat(unmarshal(marshal(configuration)), equalTo(configuration));
	}

	@Example
	public void writesTheVersioningConfigurationShape() throws JAXBException {
		assertThat(marshal(new VersioningConfiguration("Enabled", "Disabled")), containsString(
			"<VersioningConfiguration xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">"
			+ "<Status>Enabled</Status><MfaDelete>Disabled</MfaDelete></VersioningConfiguration>"
		));
	}
}

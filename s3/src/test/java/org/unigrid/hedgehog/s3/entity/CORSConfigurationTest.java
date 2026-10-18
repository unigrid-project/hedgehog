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
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

public class CORSConfigurationTest {
	private final JAXBContext context;

	public CORSConfigurationTest() throws JAXBException {
		context = JAXBContext.newInstance(CORSConfiguration.class);
	}

	private String marshal(final CORSConfiguration configuration) throws JAXBException {
		final StringWriter writer = new StringWriter();

		context.createMarshaller().marshal(configuration, writer);
		return writer.toString();
	}

	private CORSConfiguration unmarshal(final String xml) throws JAXBException {
		return (CORSConfiguration) context.createUnmarshaller().unmarshal(new StringReader(xml));
	}

	@Provide
	Arbitrary<String> origins() {
		return Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(20)
			.map(host -> "https://" + host + ".example");
	}

	@Property
	public void readsBackTheCorsConfigurationItWrote(@ForAll("origins") final String origin) throws JAXBException {
		final CORSRule rule = new CORSRule("rule-1", List.of("GET", "PUT"), List.of(origin),
			List.of("*", "X-Custom"), List.of("ETag"), 3000);
		final CORSConfiguration configuration = new CORSConfiguration(List.of(rule));

		assertThat(unmarshal(marshal(configuration)), equalTo(configuration));
	}

	@Example
	public void writesRepeatedRulesAndAllowedMethods() throws JAXBException {
		final CORSRule rule = new CORSRule("rule-1", List.of("GET", "PUT"), List.of("https://example.com"),
			List.of("*"), List.of("ETag"), 3000);
		final CORSConfiguration configuration = new CORSConfiguration(List.of(rule));

		assertThat(marshal(configuration), containsString(
			"<CORSConfiguration xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"><CORSRule><ID>rule-1</ID>"
			+ "<AllowedMethod>GET</AllowedMethod><AllowedMethod>PUT</AllowedMethod>"
			+ "<AllowedOrigin>https://example.com</AllowedOrigin><AllowedHeader>*</AllowedHeader>"
			+ "<ExposeHeader>ETag</ExposeHeader><MaxAgeSeconds>3000</MaxAgeSeconds></CORSRule>"
			+ "</CORSConfiguration>"
		));
	}
}

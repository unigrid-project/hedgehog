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
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

public class ObjectLockConfigurationTest {
	private static final long YEAR_9999 = Instant.parse("9999-12-31T23:59:59.999Z").toEpochMilli();

	private final JAXBContext context;

	public ObjectLockConfigurationTest() throws JAXBException {
		context = JAXBContext.newInstance(ObjectLockConfiguration.class, Retention.class, LegalHold.class);
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
	Arbitrary<String> modes() {
		return Arbitraries.of("GOVERNANCE", "COMPLIANCE");
	}

	@Provide
	Arbitrary<Integer> days() {
		return Arbitraries.integers().between(1, 3650);
	}

	@Provide
	Arbitrary<String> holdStatuses() {
		return Arbitraries.of("ON", "OFF");
	}

	@Provide
	Arbitrary<Instant> timestamps() {
		return Arbitraries.longs().between(0, YEAR_9999).map(Instant::ofEpochMilli);
	}

	@Property
	public void readsBackTheObjectLockConfigurationItWrote(@ForAll("modes") final String mode,
		@ForAll("days") final Integer days) throws JAXBException {
		final ObjectLockConfiguration configuration = new ObjectLockConfiguration("Enabled",
			new ObjectLockRule(new DefaultRetention(mode, days, null)));

		assertThat(unmarshal(marshal(configuration), ObjectLockConfiguration.class), equalTo(configuration));
	}

	@Property
	public void readsBackTheRetentionItWrote(@ForAll("modes") final String mode,
		@ForAll("timestamps") final Instant retainUntilDate) throws JAXBException {
		final Retention retention = new Retention(mode, retainUntilDate);

		assertThat(unmarshal(marshal(retention), Retention.class), equalTo(retention));
	}

	@Property
	public void readsBackTheLegalHoldItWrote(@ForAll("holdStatuses") final String status) throws JAXBException {
		final LegalHold legalHold = new LegalHold(status);

		assertThat(unmarshal(marshal(legalHold), LegalHold.class), equalTo(legalHold));
	}

	@Example
	public void writesTheObjectLockConfigurationShape() throws JAXBException {
		final ObjectLockConfiguration configuration = new ObjectLockConfiguration("Enabled",
			new ObjectLockRule(new DefaultRetention("GOVERNANCE", 30, null)));

		assertThat(marshal(configuration), containsString(
			"<ObjectLockConfiguration xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">"
			+ "<ObjectLockEnabled>Enabled</ObjectLockEnabled><Rule><DefaultRetention>"
			+ "<Mode>GOVERNANCE</Mode><Days>30</Days></DefaultRetention></Rule></ObjectLockConfiguration>"
		));
	}

	@Example
	public void writesTheRetentionShape() throws JAXBException {
		assertThat(marshal(new Retention("COMPLIANCE", Instant.EPOCH)), containsString(
			"<Retention xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"><Mode>COMPLIANCE</Mode>"
			+ "<RetainUntilDate>1970-01-01T00:00:00.000Z</RetainUntilDate></Retention>"
		));
	}

	@Example
	public void writesTheLegalHoldShape() throws JAXBException {
		assertThat(marshal(new LegalHold("ON")), containsString(
			"<LegalHold xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"><Status>ON</Status></LegalHold>"
		));
	}
}

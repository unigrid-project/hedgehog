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

public class LifecycleConfigurationTest {
	private static final long YEAR_9999 = Instant.parse("9999-12-31T23:59:59.999Z").toEpochMilli();

	private final JAXBContext context;

	public LifecycleConfigurationTest() throws JAXBException {
		context = JAXBContext.newInstance(LifecycleConfiguration.class);
	}

	private String marshal(final LifecycleConfiguration configuration) throws JAXBException {
		final StringWriter writer = new StringWriter();

		context.createMarshaller().marshal(configuration, writer);
		return writer.toString();
	}

	private LifecycleConfiguration unmarshal(final String xml) throws JAXBException {
		return (LifecycleConfiguration) context.createUnmarshaller().unmarshal(new StringReader(xml));
	}

	@Provide
	Arbitrary<String> prefixes() {
		return Arbitraries.strings().alpha().withChars("/-").ofMinLength(1).ofMaxLength(20);
	}

	@Provide
	Arbitrary<Instant> timestamps() {
		return Arbitraries.longs().between(0, YEAR_9999).map(Instant::ofEpochMilli);
	}

	@Property
	public void readsBackTheLifecycleConfigurationItWrote(@ForAll("prefixes") final String prefix,
		@ForAll("timestamps") final Instant date) throws JAXBException {
		final LifecycleRule expireObjects = new LifecycleRule("expire-logs",
			new LifecycleFilter(prefix, null, null, null, null), null, "Enabled",
			new LifecycleExpiration(null, 30, null), null, null);
		final LifecycleRule abortUploads = new LifecycleRule("abort-uploads", new LifecycleFilter(null, null,
			null, null, new LifecycleAnd("logs/", List.of(new Tag("env", "prod")), 1024L, 2048L)), "legacy/",
			"Enabled", new LifecycleExpiration(date, null, true), new NoncurrentVersionExpiration(7, 3),
			new AbortIncompleteMultipartUpload(5));
		final LifecycleConfiguration configuration = new LifecycleConfiguration(
			List.of(expireObjects, abortUploads));

		assertThat(unmarshal(marshal(configuration)), equalTo(configuration));
	}

	@Example
	public void writesTheNestedFilterShape() throws JAXBException {
		final LifecycleRule rule = new LifecycleRule("rule-1", new LifecycleFilter(null, null, null, null,
			new LifecycleAnd("logs/", List.of(new Tag("env", "prod")), 100L, 200L)), null, "Enabled",
			new LifecycleExpiration(Instant.parse("2024-01-01T00:00:00Z"), null, null), null, null);

		assertThat(marshal(new LifecycleConfiguration(List.of(rule))), containsString(
			"<LifecycleConfiguration xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"><Rule><ID>rule-1</ID>"
			+ "<Filter><And><Prefix>logs/</Prefix><Tag><Key>env</Key><Value>prod</Value></Tag>"
			+ "<ObjectSizeGreaterThan>100</ObjectSizeGreaterThan><ObjectSizeLessThan>200</ObjectSizeLessThan>"
			+ "</And></Filter><Status>Enabled</Status><Expiration><Date>2024-01-01T00:00:00.000Z</Date>"
			+ "</Expiration></Rule></LifecycleConfiguration>"
		));
	}

	@Example
	public void readsLifecycleDatesWithoutMilliseconds() throws JAXBException {
		final String xml = "<LifecycleConfiguration xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"><Rule>"
			+ "<Status>Enabled</Status><Expiration><Date>2024-01-01T00:00:00Z</Date></Expiration>"
			+ "</Rule></LifecycleConfiguration>";

		assertThat(unmarshal(xml).getRules().get(0).getExpiration().getDate(),
			equalTo(Instant.parse("2024-01-01T00:00:00Z")));
	}
}

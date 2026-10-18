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

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.unigrid.hedgehog.s3.jqwik.Expect.assertThrows;

public class S3XmlTest {
	private static final String BUCKETS = "<Buckets><Bucket><CreationDate>1970-01-01T00:00:00.000Z</CreationDate>"
		+ "<Name>photos</Name></Bucket></Buckets>";

	private static final ListAllMyBucketsResult PHOTOS = new ListAllMyBucketsResult(
		List.of(new Bucket(Instant.EPOCH, "photos")), null);

	private static ListAllMyBucketsResult read(final String xml) throws Exception {
		return S3Xml.read(ListAllMyBucketsResult.class,
			new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
	}

	@Example
	public void readsABodyInTheS3Namespace() throws Exception {
		assertThat(read("<ListAllMyBucketsResult xmlns=\"" + S3Xml.NAMESPACE + "\">" + BUCKETS
			+ "</ListAllMyBucketsResult>"), equalTo(PHOTOS));
	}

	@Example
	public void readsABodyWithoutANamespace() throws Exception {
		assertThat(read("<ListAllMyBucketsResult>" + BUCKETS + "</ListAllMyBucketsResult>"), equalTo(PHOTOS));
	}

	@Example
	public void readsABodyInAnyOtherNamespace() throws Exception {
		assertThat(read("<x:ListAllMyBucketsResult xmlns:x=\"urn:other\">" + BUCKETS.replace("<", "<x:")
			.replace("<x:/", "</x:") + "</x:ListAllMyBucketsResult>"), equalTo(PHOTOS));
	}

	@Example
	public void refusesExternalEntities() {
		assertThrows(MalformedXmlException.class, () -> read("<?xml version=\"1.0\"?><!DOCTYPE r ["
			+ "<!ENTITY x SYSTEM \"file:///etc/hostname\">]><ListAllMyBucketsResult><Buckets><Bucket><Name>&x;"
			+ "</Name></Bucket></Buckets></ListAllMyBucketsResult>"));
	}

	@Example
	public void refusesEntityExpansion() {
		assertThrows(MalformedXmlException.class, () -> read("<?xml version=\"1.0\"?><!DOCTYPE r ["
			+ "<!ENTITY a \"aaaaaaaaaa\"><!ENTITY b \"&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;\">]>"
			+ "<ListAllMyBucketsResult><Buckets><Bucket><Name>&b;</Name></Bucket></Buckets>"
			+ "</ListAllMyBucketsResult>"));
	}

	@Example
	public void refusesAnotherRootElement() {
		assertThrows(MalformedXmlException.class, () -> read("<Tagging>" + BUCKETS + "</Tagging>"));
	}

	@Property
	public void refusesAnythingButXml(@ForAll final String body) {
		assertThrows(MalformedXmlException.class, () -> read("<" + body));
	}
}

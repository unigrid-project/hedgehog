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
import jakarta.xml.bind.UnmarshallerHandler;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;
import lombok.SneakyThrows;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.XMLFilterImpl;

public final class S3Xml {
	public static final String NAMESPACE = "http://s3.amazonaws.com/doc/2006-03-01/";

	private static final Map<Class<?>, JAXBContext> CONTEXTS = new ConcurrentHashMap<>();
	private static final SAXParserFactory PARSERS = parsers();

	private S3Xml() {
		/* Static helpers only */
	}

	/* Clients send request bodies both with and without the S3 namespace, so every element is read as if in it */
	private static final class NamespaceFilter extends XMLFilterImpl {
		private NamespaceFilter(final XMLReader parent) {
			super(parent);
		}

		@Override
		public void startElement(final String uri, final String localName, final String qualifiedName,
			final Attributes attributes) throws SAXException {

			super.startElement(NAMESPACE, localName, localName, attributes);
		}

		@Override
		public void endElement(final String uri, final String localName, final String qualifiedName)
			throws SAXException {

			super.endElement(NAMESPACE, localName, localName);
		}
	}

	/* Request bodies come from clients, so document types and with them every entity expansion are refused */
	@SneakyThrows({ParserConfigurationException.class, SAXException.class})
	private static SAXParserFactory parsers() {
		final SAXParserFactory factory = SAXParserFactory.newInstance();

		factory.setNamespaceAware(true);
		factory.setXIncludeAware(false);
		factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
		factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
		factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
		factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
		return factory;
	}

	@SneakyThrows(JAXBException.class)
	private static JAXBContext context(final Class<?> type) {
		return JAXBContext.newInstance(type);
	}

	public static <T> T read(final Class<T> type, final InputStream input) throws IOException, MalformedXmlException {
		try {
			final UnmarshallerHandler handler = CONTEXTS.computeIfAbsent(type, S3Xml::context)
				.createUnmarshaller().getUnmarshallerHandler();
			final XMLFilterImpl filter = new NamespaceFilter(PARSERS.newSAXParser().getXMLReader());

			filter.setContentHandler(handler);
			filter.parse(new InputSource(input));
			return type.cast(handler.getResult());
		} catch (ParserConfigurationException | SAXException | JAXBException | ClassCastException
			| IllegalStateException e) {

			throw new MalformedXmlException("Request body is not a valid " + type.getSimpleName(), e);
		}
	}
}

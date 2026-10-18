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

module org.unigrid.hedgehog.s3 {
	requires static lombok;
	requires io.netty.codec.http;
	requires io.netty.handler;
	requires jakarta.annotation;
	requires io.netty.transport;
	requires jakarta.ws.rs;
	requires jakarta.xml.bind;
	requires jersey.common;
	requires jersey.container.netty.http;
	requires jersey.server;
	requires org.slf4j;
	requires org.unigrid.hedgehog.common;

	opens org.unigrid.hedgehog.s3.entity to jakarta.xml.bind;
	opens org.unigrid.hedgehog.s3.error;
	opens org.unigrid.hedgehog.s3.http;
	opens org.unigrid.hedgehog.s3.resource;

	exports org.unigrid.hedgehog.s3.auth;
	exports org.unigrid.hedgehog.s3.cache;
	exports org.unigrid.hedgehog.s3.checksum;
	exports org.unigrid.hedgehog.s3.error;
	exports org.unigrid.hedgehog.s3.http;
	exports org.unigrid.hedgehog.s3.index;
	exports org.unigrid.hedgehog.s3.server;
	exports org.unigrid.hedgehog.s3.spi;
}

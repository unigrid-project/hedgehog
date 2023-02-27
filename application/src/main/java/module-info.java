/*
    Unigrid Hedgehog
    Copyright © 2021-2023 The Unigrid Foundation, UGD Software AB

    This program is free software: you can redistribute it and/or modify it under the terms of the
    addended GNU Affero General Public License as published by the The Unigrid Foundation and
    the Free Software Foundation, version 3 of the License (see COPYING and COPYING.addendum).

    This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
    even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU Affero General Public License and the addendum for more details.

    You should have received an addended copy of the GNU Affero General Public License with this program.
    If not, see <http://www.gnu.org/licenses/> and <https://github.com/unigrid-project/hedgehog>.
 */

module org.unigrid.hedgehog {
	requires ch.qos.logback.classic;
	requires static lombok;
	requires info.picocli;
	requires jakarta.cdi;
	requires jakarta.inject;
	requires jakarta.interceptor;
	requires jakarta.ws.rs;
	requires jakarta.annotation;
	requires jakarta.el;
	requires com.fasterxml.jackson.databind;
	requires com.fasterxml.jackson.datatype.jsr310;
	requires com.fasterxml.jackson.core;
	requires free.port.finder;
	requires org.apache.commons.lang3;
	requires org.apache.commons.codec;
	requires io.netty.buffer;
	requires io.netty.transport;
	requires io.netty.codec;
	requires io.netty.incubator.codec.classes.quic;
	requires io.netty.common;
	requires io.netty.handler;
	requires jersey.server;
	requires jersey.client;
	requires jersey.container.netty.http;
	requires jersey.media.json.jackson;
	requires jersey.common;
	requires jersey.hk2;
	requires org.reflections;
	requires org.slf4j;
	requires weld.se.core;
	requires weld.core.impl;
	requires weld.environment.common;
	requires weld.spi;
	requires org.apache.commons.configuration2;
	requires org.apache.commons.collections4;
	requires java.logging;
	requires jakarta.xml.bind;
	requires j8fu;
	requires net.harawata.appdirs;
	requires org.graalvm.sdk;
	requires org.unigrid.hedgehog.common;
	requires jdk.crypto.ec;

	opens org.unigrid.hedgehog.model.s3.entity to jakarta.xml.bind;
}

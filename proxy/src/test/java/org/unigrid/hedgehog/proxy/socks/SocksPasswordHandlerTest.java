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

package org.unigrid.hedgehog.proxy.socks;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.socksx.v5.Socks5CommandRequest;
import io.netty.handler.codec.socksx.v5.Socks5CommandRequestDecoder;
import io.netty.handler.codec.socksx.v5.Socks5PasswordAuthRequestDecoder;
import io.netty.handler.codec.socksx.v5.Socks5PasswordAuthStatus;
import io.netty.handler.codec.socksx.v5.Socks5ServerEncoder;
import io.netty.util.ReferenceCountUtil;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

public class SocksPasswordHandlerTest {
	/* CONNECT to 192.0.2.1 port 80 */
	private static final byte[] CONNECT = { 5, 1, 0, 1, (byte) 192, 0, 2, 1, 0, 80 };

	private static EmbeddedChannel channel() {
		return new EmbeddedChannel(Socks5ServerEncoder.DEFAULT, new Socks5PasswordAuthRequestDecoder(),
			new SocksPasswordHandler(), new Socks5CommandRequestDecoder());
	}

	/* What a client sends for the sub-negotiation of RFC 1929 */
	private static byte[] credentials(String username, String password) {
		final byte[] user = username.getBytes(StandardCharsets.US_ASCII);
		final byte[] secret = password.getBytes(StandardCharsets.US_ASCII);
		final ByteBuf bytes = Unpooled.buffer();

		bytes.writeByte(1).writeByte(user.length).writeBytes(user).writeByte(secret.length).writeBytes(secret);
		return ByteBufUtil.getBytes(bytes);
	}

	private static byte[] answer(Socks5PasswordAuthStatus status) {
		return new byte[] { 1, status.byteValue() };
	}

	@Provide
	public Arbitrary<String> countries() {
		return Arbitraries.of(Locale.getISOCountries());
	}

	@Example
	public void unknownCountryFailsAndCloses() {
		final EmbeddedChannel channel = channel();

		channel.writeInbound(Unpooled.wrappedBuffer(credentials("XX", "secret")));
		assertThat(SocksCommandTest.readBytes(channel), equalTo(answer(Socks5PasswordAuthStatus.FAILURE)));
		assertThat(channel.isOpen(), is(false));
		assertThat(channel.config().isAutoRead(), is(false));
		assertThat(channel.attr(SocksPasswordHandler.COUNTRY).get(), is(nullValue()));
	}

	/* What curl sends for --proxy-user : */
	@Example
	public void emptyUsernameMeansNoCountry() {
		final EmbeddedChannel channel = channel();

		channel.writeInbound(Unpooled.wrappedBuffer(new byte[] { 1, 0, 0 }));
		assertThat(SocksCommandTest.readBytes(channel), equalTo(answer(Socks5PasswordAuthStatus.SUCCESS)));
		assertThat(channel.isOpen(), is(true));
		assertThat(channel.attr(SocksPasswordHandler.COUNTRY).get(), is(nullValue()));
		assertThat(channel.pipeline().get(SocksPasswordHandler.class), is(nullValue()));
		assertThat(channel.pipeline().get(Socks5PasswordAuthRequestDecoder.class), is(nullValue()));
	}

	@Property(tries = 50)
	public void knownCountryIsKeptUpperCase(@ForAll("countries") String code, @ForAll boolean lower) {
		final EmbeddedChannel channel = channel();

		channel.writeInbound(Unpooled.wrappedBuffer(credentials(lower ? code.toLowerCase(Locale.ROOT) : code, "")));
		assertThat(SocksCommandTest.readBytes(channel), equalTo(answer(Socks5PasswordAuthStatus.SUCCESS)));
		assertThat(channel.isOpen(), is(true));
		assertThat(channel.attr(SocksPasswordHandler.COUNTRY).get(), is(code));
	}

	@Example
	public void shouldIgnoreThePassword() {
		final EmbeddedChannel channel = channel();

		channel.writeInbound(Unpooled.wrappedBuffer(credentials("SE", "not a secret at all")));
		assertThat(SocksCommandTest.readBytes(channel), equalTo(answer(Socks5PasswordAuthStatus.SUCCESS)));
		assertThat(channel.attr(SocksPasswordHandler.COUNTRY).get(), is("SE"));
	}

	@Example
	public void shouldDecodeACommandSentAlongWithTheCredentials() {
		final EmbeddedChannel channel = channel();

		channel.writeInbound(Unpooled.wrappedBuffer(credentials("SE", ""), CONNECT));
		assertThat(SocksCommandTest.readBytes(channel), equalTo(answer(Socks5PasswordAuthStatus.SUCCESS)));

		final Socks5CommandRequest request = channel.readInbound();

		assertThat(request.dstAddr(), is("192.0.2.1"));
		assertThat(request.dstPort(), is(80));
	}

	@Example
	public void shouldNeverReadACommandBehindARefusedUsername() {
		final EmbeddedChannel channel = channel();

		channel.writeInbound(Unpooled.wrappedBuffer(credentials("XX", ""), CONNECT));
		assertThat(SocksCommandTest.readBytes(channel), equalTo(answer(Socks5PasswordAuthStatus.FAILURE)));

		for (Object inbound = channel.readInbound(); inbound != null; inbound = channel.readInbound()) {
			assertThat(inbound, not(instanceOf(Socks5CommandRequest.class)));
			ReferenceCountUtil.release(inbound);
		}
	}

	@Example
	public void shouldDropAClientThatSpeaksAnotherVersionOfTheSubNegotiation() {
		final EmbeddedChannel channel = channel();

		channel.writeInbound(Unpooled.wrappedBuffer(new byte[] { 2, 2, 'S', 'E', 0 }));
		assertThat(channel.readOutbound(), is(nullValue()));
		assertThat(channel.isOpen(), is(false));
	}

	@Example
	public void shouldReleaseBytesThatReachItInsteadOfCredentials() {
		final EmbeddedChannel channel = new EmbeddedChannel(new SocksPasswordHandler());
		final ByteBuf bytes = Unpooled.wrappedBuffer(CONNECT);

		channel.writeInbound(bytes);
		assertThat(channel.readInbound(), is(nullValue()));
		assertThat(bytes.refCnt(), is(0));
	}
}

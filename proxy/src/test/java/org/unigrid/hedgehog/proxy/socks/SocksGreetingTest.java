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
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.socksx.v5.Socks5AuthMethod;
import io.netty.handler.codec.socksx.v5.Socks5CommandRequest;
import io.netty.handler.codec.socksx.v5.Socks5CommandRequestDecoder;
import io.netty.handler.codec.socksx.v5.Socks5InitialRequestDecoder;
import io.netty.handler.codec.socksx.v5.Socks5InitialResponse;
import io.netty.util.ReferenceCountUtil;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

public class SocksGreetingTest {
	/* CONNECT to 192.0.2.1 port 80 */
	private static final byte[] CONNECT = { 5, 1, 0, 1, (byte) 192, 0, 2, 1, 0, 80 };

	private static EmbeddedChannel channel() {
		return new EmbeddedChannel(new Socks5InitialRequestDecoder(), new SocksGreetingHandler(),
			new Socks5CommandRequestDecoder());
	}

	private static byte[] greeting(List<Integer> methods) {
		final byte[] bytes = new byte[2 + methods.size()];

		bytes[0] = 5;
		bytes[1] = (byte) methods.size();

		for (int i = 0; i < methods.size(); i++) {
			bytes[2 + i] = methods.get(i).byteValue();
		}

		return bytes;
	}

	private static Socks5AuthMethod answer(EmbeddedChannel channel) {
		final Socks5AuthMethod method = ((Socks5InitialResponse) channel.readOutbound()).authMethod();

		assertThat("one answer only", channel.readOutbound(), is(nullValue()));
		return method;
	}

	@Provide
	public Arbitrary<List<Integer>> authenticating() {
		return Arbitraries.integers().between(1, 255).list().ofMinSize(1).ofMaxSize(20);
	}

	@Example
	public void shouldAcceptAClientThatOffersNoAuth() {
		final EmbeddedChannel channel = channel();

		channel.writeInbound(Unpooled.wrappedBuffer(greeting(List.of(0))));
		assertThat(answer(channel), is(Socks5AuthMethod.NO_AUTH));
		assertThat(channel.pipeline().get(SocksGreetingHandler.class), is(nullValue()));
		assertThat(channel.pipeline().get(Socks5InitialRequestDecoder.class), is(nullValue()));
		assertThat(channel.isOpen(), is(true));
	}

	@Property(tries = 50)
	public void shouldPickNoAuthAmongOtherMethods(@ForAll("authenticating") List<Integer> others,
		@ForAll int position) {
		final List<Integer> methods = new ArrayList<>(others);
		final EmbeddedChannel channel = channel();

		methods.add(Math.floorMod(position, methods.size() + 1), 0);
		channel.writeInbound(Unpooled.wrappedBuffer(greeting(methods)));
		assertThat(answer(channel), is(Socks5AuthMethod.NO_AUTH));
		assertThat(channel.isOpen(), is(true));
	}

	@Property(tries = 50)
	public void shouldRefuseAClientThatNeedsAuthentication(@ForAll("authenticating") List<Integer> methods) {
		final EmbeddedChannel channel = channel();

		channel.writeInbound(Unpooled.wrappedBuffer(greeting(methods)));
		assertThat(answer(channel), is(Socks5AuthMethod.UNACCEPTED));
		assertThat(channel.isOpen(), is(false));
		assertThat(channel.config().isAutoRead(), is(false));
	}

	@Example
	public void shouldHangUpOnlyOnceTheRefusalIsSent() {
		final HeldWrites held = new HeldWrites();
		final EmbeddedChannel channel = new EmbeddedChannel(held, new Socks5InitialRequestDecoder(),
			new SocksGreetingHandler(), new Socks5CommandRequestDecoder());

		channel.writeInbound(Unpooled.wrappedBuffer(greeting(List.of(2))));
		assertThat(channel.isOpen(), is(true));
		held.release();
		assertThat(answer(channel), is(Socks5AuthMethod.UNACCEPTED));
		assertThat(channel.isOpen(), is(false));
	}

	@Example
	public void shouldDropAClientThatDoesNotSpeakSocks5() {
		final EmbeddedChannel channel = channel();

		channel.writeInbound(Unpooled.wrappedBuffer(new byte[] { 4, 1, 0 }));
		assertThat(channel.readOutbound(), is(nullValue()));
		assertThat(channel.isOpen(), is(false));
	}

	@Example
	public void shouldDecodeACommandSentAlongWithTheGreeting() {
		final EmbeddedChannel channel = channel();

		channel.writeInbound(Unpooled.wrappedBuffer(greeting(List.of(0)), CONNECT));
		assertThat(answer(channel), is(Socks5AuthMethod.NO_AUTH));

		final Socks5CommandRequest request = channel.readInbound();

		assertThat(request.dstAddr(), is("192.0.2.1"));
		assertThat(request.dstPort(), is(80));
	}

	@Example
	public void shouldNeverReadACommandBehindARefusedGreeting() {
		final EmbeddedChannel channel = channel();

		channel.writeInbound(Unpooled.wrappedBuffer(greeting(List.of(2)), CONNECT));
		assertThat(answer(channel), is(Socks5AuthMethod.UNACCEPTED));

		for (Object inbound = channel.readInbound(); inbound != null; inbound = channel.readInbound()) {
			assertThat(inbound, not(instanceOf(Socks5CommandRequest.class)));
			ReferenceCountUtil.release(inbound);
		}
	}

	@Example
	public void shouldFindNoAuthLastAmongAsManyMethodsAsAGreetingHolds() {
		final List<Integer> methods = new ArrayList<>(Collections.nCopies(254, 2));
		final EmbeddedChannel channel = channel();

		methods.add(0);
		channel.writeInbound(Unpooled.wrappedBuffer(greeting(methods)));
		assertThat(answer(channel), is(Socks5AuthMethod.NO_AUTH));
		assertThat(channel.isOpen(), is(true));
	}

	@Example
	public void shouldDropAGreetingThatOffersNoMethod() {
		final EmbeddedChannel channel = channel();

		channel.writeInbound(Unpooled.wrappedBuffer(greeting(List.of())));
		assertThat(channel.readOutbound(), is(nullValue()));
		assertThat(channel.isOpen(), is(false));
	}

	@Example
	public void shouldReleaseBytesThatReachItInsteadOfAGreeting() {
		final EmbeddedChannel channel = new EmbeddedChannel(new SocksGreetingHandler(),
			new Socks5CommandRequestDecoder());
		final ByteBuf bytes = Unpooled.wrappedBuffer(CONNECT);

		channel.writeInbound(bytes);
		assertThat(channel.readInbound(), is(nullValue()));
		assertThat(bytes.refCnt(), is(0));
	}
}

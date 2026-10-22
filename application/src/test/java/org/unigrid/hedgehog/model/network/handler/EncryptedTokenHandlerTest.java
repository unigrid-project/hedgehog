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

package org.unigrid.hedgehog.model.network.handler;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import lombok.SneakyThrows;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.Size;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.lessThanOrEqualTo;

public class EncryptedTokenHandlerTest {
	private static final UUID KEY = UUID.fromString("6f1c2d3e-4a5b-6c7d-8e9f-a0b1c2d3e4f5");
	private static final InetSocketAddress IPV4 = address("192.0.2.7");
	private static final InetSocketAddress IPV6 = address("2001:db8::7");
	private static final int LONGEST_CONNECTION_ID = 18;
	private static final int INVALID = -1;

	private final EncryptedTokenHandler handler = new EncryptedTokenHandler(KEY);

	@SneakyThrows
	private static InetSocketAddress address(String literal) {
		return new InetSocketAddress(InetAddress.ofLiteral(literal), 52883);
	}

	private ByteBuf tokenFor(InetSocketAddress address, byte[] connectionId) {
		final ByteBuf token = Unpooled.buffer();

		assertThat(handler.writeToken(token, Unpooled.wrappedBuffer(connectionId), address), equalTo(true));
		return token;
	}

	/* What the handler itself would encrypt, with a plain text of the test's choosing */
	@SneakyThrows
	private static ByteBuf tokenOf(byte[] plain) {
		final Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");

		cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(KEY.toString().replace("-", "")
			.getBytes(StandardCharsets.ISO_8859_1), "AES"), new IvParameterSpec(new byte[16]));

		final byte[] encrypted = cipher.doFinal(plain);

		return Unpooled.buffer().writeByte(encrypted.length).writeBytes(encrypted);
	}

	@Provide
	public Arbitrary<InetSocketAddress> provideAddress() {
		return Arbitraries.of(IPV4, IPV6);
	}

	@Property(tries = 20)
	public void shouldFindTheConnectionIdInATokenItWroteForTheAddress(
		@ForAll("provideAddress") InetSocketAddress address,
		@ForAll @Size(max = LONGEST_CONNECTION_ID) byte[] connectionId) {

		final ByteBuf token = tokenFor(address, connectionId);
		final int offset = handler.validateToken(token, address);

		assertThat(offset, equalTo(token.readableBytes() - connectionId.length));
	}

	@Example
	public void shouldRefuseATokenForAnotherAddress() {
		assertThat(handler.validateToken(tokenFor(IPV4, new byte[8]), address("192.0.2.8")), equalTo(INVALID));
		assertThat(handler.validateToken(tokenFor(IPV6, new byte[8]), IPV4), equalTo(INVALID));
	}

	@Example
	public void shouldRefuseATokenOfAnotherNode() {
		final EncryptedTokenHandler other = new EncryptedTokenHandler(UUID.randomUUID());

		assertThat(other.validateToken(tokenFor(IPV4, new byte[8]), IPV4), equalTo(INVALID));
	}

	@Example
	public void shouldRefuseATokenThatWasChanged() {
		final ByteBuf token = tokenFor(IPV4, new byte[8]);

		token.setByte(5, token.getByte(5) ^ 1);
		assertThat(handler.validateToken(token, IPV4), equalTo(INVALID));
	}

	@Example
	public void shouldRefuseATokenOfAnotherServerNameOrTooShortForAnAddress() {
		assertThat(handler.validateToken(tokenOf("Hedgehox".getBytes(StandardCharsets.ISO_8859_1)), IPV4),
			equalTo(INVALID));
		assertThat(handler.validateToken(tokenOf(new byte[3]), IPV4), equalTo(INVALID));
	}

	@Example
	public void shouldRefuseATokenWhoseLengthIsWrong() {
		assertThat(handler.validateToken(Unpooled.EMPTY_BUFFER, IPV4), equalTo(INVALID));
		assertThat(handler.validateToken(Unpooled.wrappedBuffer(new byte[] { 0 }), IPV4), equalTo(INVALID));
		assertThat(handler.validateToken(Unpooled.wrappedBuffer(new byte[] { (byte) 0xff, 1, 2 }), IPV4),
			equalTo(INVALID));
		assertThat(handler.validateToken(Unpooled.wrappedBuffer(new byte[] { 40, 1, 2 }), IPV4), equalTo(INVALID));
	}

	/* A token comes from whoever sends a packet, so no bytes may throw */
	@Property(tries = 200)
	public void shouldRefuseAnyBytesWithoutThrowing(@ForAll @Size(max = 80) byte[] bytes) {
		assertThat(handler.validateToken(Unpooled.wrappedBuffer(bytes), IPV6), equalTo(INVALID));
	}

	@Example
	public void shouldHoldTheLongestTokenItWrites() {
		assertThat(tokenFor(IPV6, new byte[LONGEST_CONNECTION_ID]).readableBytes(),
			lessThanOrEqualTo(handler.maxTokenLength()));
	}
}

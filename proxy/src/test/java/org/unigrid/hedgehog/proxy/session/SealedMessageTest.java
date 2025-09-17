/*
    Unigrid Hedgehog
    Copyright © 2021-2025 Stiftelsen The Unigrid Foundation

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

package org.unigrid.hedgehog.proxy.session;

import io.netty.handler.codec.socksx.v5.Socks5AddressType;
import io.netty.handler.codec.socksx.v5.Socks5CommandStatus;
import io.netty.handler.codec.socksx.v5.Socks5CommandType;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.Size;
import org.unigrid.hedgehog.proxy.Expect;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;

public class SealedMessageTest {
	private static final SocksAddress TARGET = new SocksAddress(Socks5AddressType.DOMAIN, "example.org", 443);
	private static final byte[] PAYLOAD = {1, 2, 3};

	/* Each differs from PAYLOAD in length or in exactly one place, first, middle or last */
	private static final List<byte[]> OTHER_PAYLOADS = List.of(new byte[0], new byte[] {1, 2}, new byte[] {2, 3},
		new byte[] {1, 2, 3, 0}, new byte[] {0, 2, 3}, new byte[] {1, 0, 3}, new byte[] {1, 2, 0});

	/* Each differs from TARGET in the port, the host or the type */
	private static final List<SocksAddress> OTHER_ADDRESSES = List.of(SocksAddress.UNSPECIFIED,
		new SocksAddress(Socks5AddressType.DOMAIN, "example.org", 80),
		new SocksAddress(Socks5AddressType.DOMAIN, "example.com", 443),
		new SocksAddress(Socks5AddressType.IPv4, "192.0.2.1", 443));

	private static final List<Socks5CommandStatus> REPLIES = List.of(Socks5CommandStatus.SUCCESS,
		Socks5CommandStatus.FAILURE, Socks5CommandStatus.FORBIDDEN, Socks5CommandStatus.NETWORK_UNREACHABLE,
		Socks5CommandStatus.HOST_UNREACHABLE, Socks5CommandStatus.CONNECTION_REFUSED,
		Socks5CommandStatus.TTL_EXPIRED, Socks5CommandStatus.COMMAND_UNSUPPORTED,
		Socks5CommandStatus.ADDRESS_UNSUPPORTED);

	private static boolean builds(Runnable message) {
		try {
			message.run();
			return true;
		} catch (IllegalArgumentException refusal) {
			return false;
		}
	}

	private static byte[] counting(int length) {
		final byte[] bytes = new byte[length];

		for (int index = 0; index < length; index++) {
			bytes[index] = (byte) index;
		}

		return bytes;
	}

	@Example
	public void shouldCarryOnlyTheCommandsAnExitServes() {
		assertThat(new Connect(Socks5CommandType.CONNECT, TARGET).destination(), equalTo(TARGET));
		assertThat(new Connect(Socks5CommandType.UDP_ASSOCIATE, TARGET).command(),
			equalTo(Socks5CommandType.UDP_ASSOCIATE));
		Expect.assertThrows(IllegalArgumentException.class, () -> new Connect(Socks5CommandType.BIND, TARGET));
		Expect.assertThrows(IllegalArgumentException.class,
			() -> new Connect(Socks5CommandType.valueOf((byte) 9), TARGET));
	}

	@Example
	public void shouldCarryOnlyTheRepliesOfSocks5() {
		assertThat(new Connected(Socks5CommandStatus.ADDRESS_UNSUPPORTED, SocksAddress.UNSPECIFIED).status(),
			equalTo(Socks5CommandStatus.ADDRESS_UNSUPPORTED));
		Expect.assertThrows(IllegalArgumentException.class,
			() -> new Connected(Socks5CommandStatus.valueOf((byte) 9), SocksAddress.UNSPECIFIED));
		Expect.assertThrows(IllegalArgumentException.class,
			() -> new Connected(Socks5CommandStatus.valueOf((byte) -1), SocksAddress.UNSPECIFIED));
	}

	@Property(tries = 50)
	public void shouldCompareBytesByValueAndKeepThemToItself(@ForAll @Size(min = 1) byte[] bytes) {
		final byte[] copy = bytes.clone();
		final Data data = new Data(bytes);
		final Datagram datagram = new Datagram(TARGET, bytes);

		Arrays.fill(bytes, (byte) 1);
		data.bytes()[0]++;
		datagram.bytes()[0]++;
		assertThat(data.bytes(), equalTo(copy));
		assertThat(datagram.bytes(), equalTo(copy));
		assertThat(data, equalTo(new Data(copy)));
		assertThat(data.hashCode(), is(new Data(copy).hashCode()));
		assertThat(datagram, equalTo(new Datagram(TARGET, copy)));
		assertThat(datagram, not(equalTo(new Datagram(SocksAddress.UNSPECIFIED, copy))));
	}

	@Example
	public void shouldNameEveryCloseReasonByItsCode() {
		for (CloseReason reason : CloseReason.values()) {
			assertThat(CloseReason.of(reason.code()), equalTo(Optional.of(reason)));
		}

		assertThat(CloseReason.of((byte) 5), equalTo(Optional.empty()));
		assertThat(new Close(CloseReason.IDLE).reason(), is(CloseReason.IDLE));
	}

	@Example
	public void shouldPermitExactlyTheFiveMessagesAndNoOther() {
		assertThat(SealedMessage.class.isSealed(), is(true));

		final List<Class<?>> permitted = List.of(SealedMessage.class.getPermittedSubclasses());

		assertThat(permitted, containsInAnyOrder(Connect.class, Connected.class, Data.class, Datagram.class,
			Close.class));

		for (Class<?> message : permitted) {
			assertThat(message.getName(), message.isRecord(), is(true));
			assertThat(message.getName(), Modifier.isFinal(message.getModifiers()), is(true));
		}
	}

	@Example
	public void shouldServeExactlyConnectAndUdpAssociateOfEveryCommandByte() {
		for (int value = Byte.MIN_VALUE; value <= Byte.MAX_VALUE; value++) {
			final Socks5CommandType command = Socks5CommandType.valueOf((byte) value);

			assertThat("command " + value, builds(() -> new Connect(command, TARGET)), is(value == 1 || value == 3));
		}

		assertThat(Socks5CommandType.CONNECT.byteValue(), is((byte) 1));
		assertThat(Socks5CommandType.UDP_ASSOCIATE.byteValue(), is((byte) 3));
	}

	@Example
	public void shouldAcceptExactlyTheNineRepliesOfSocks5() {
		for (int value = Byte.MIN_VALUE; value <= Byte.MAX_VALUE; value++) {
			final Socks5CommandStatus status = Socks5CommandStatus.valueOf((byte) value);

			assertThat("reply " + value, builds(() -> new Connected(status, TARGET)), is(value >= 0 && value <= 8));
		}

		assertThat(REPLIES.size(), is(9));

		for (Socks5CommandStatus reply : REPLIES) {
			assertThat(new Connected(reply, TARGET).status(), equalTo(reply));
			assertThat(new Connected(reply, TARGET).bound(), equalTo(TARGET));
		}

		assertThat(REPLIES.get(0).byteValue(), is((byte) 0));
		assertThat(REPLIES.get(8).byteValue(), is((byte) 8));
	}

	@Example
	public void shouldRefuseNullFieldsInEveryMessage() {
		assertThat(Expect.assertThrows(NullPointerException.class,
			() -> new Connect(Socks5CommandType.CONNECT, null)).getMessage(), is("destination"));
		assertThat(Expect.assertThrows(NullPointerException.class,
			() -> new Connect(Socks5CommandType.UDP_ASSOCIATE, null)).getMessage(), is("destination"));
		Expect.assertThrows(IllegalArgumentException.class, () -> new Connect(null, TARGET));

		assertThat(Expect.assertThrows(NullPointerException.class,
			() -> new Connected(Socks5CommandStatus.SUCCESS, null)).getMessage(), is("bound"));
		Expect.assertThrows(NullPointerException.class, () -> new Connected(null, TARGET));

		Expect.assertThrows(NullPointerException.class, () -> new Data(null));
		assertThat(Expect.assertThrows(NullPointerException.class,
			() -> new Datagram(null, PAYLOAD)).getMessage(), is("address"));
		Expect.assertThrows(NullPointerException.class, () -> new Datagram(TARGET, null));
		assertThat(Expect.assertThrows(NullPointerException.class, () -> new Close(null)).getMessage(), is("reason"));
	}

	@Example
	public void shouldCompareConnectConnectedAndCloseByTheirFields() {
		assertThat(new Connect(Socks5CommandType.CONNECT, TARGET),
			equalTo(new Connect(Socks5CommandType.CONNECT, TARGET)));
		assertThat(new Connect(Socks5CommandType.CONNECT, TARGET).hashCode(),
			is(new Connect(Socks5CommandType.CONNECT, TARGET).hashCode()));
		assertThat(new Connect(Socks5CommandType.CONNECT, TARGET),
			not(equalTo(new Connect(Socks5CommandType.UDP_ASSOCIATE, TARGET))));
		assertThat(new Connect(Socks5CommandType.CONNECT, TARGET),
			not(equalTo(new Connect(Socks5CommandType.CONNECT, SocksAddress.UNSPECIFIED))));

		assertThat(new Connected(Socks5CommandStatus.SUCCESS, TARGET),
			equalTo(new Connected(Socks5CommandStatus.SUCCESS, TARGET)));
		assertThat(new Connected(Socks5CommandStatus.SUCCESS, TARGET),
			not(equalTo(new Connected(Socks5CommandStatus.FAILURE, TARGET))));
		assertThat(new Connected(Socks5CommandStatus.SUCCESS, TARGET),
			not(equalTo(new Connected(Socks5CommandStatus.SUCCESS, SocksAddress.UNSPECIFIED))));

		assertThat(new Close(CloseReason.DONE), equalTo(new Close(CloseReason.DONE)));
		assertThat(new Close(CloseReason.DONE).hashCode(), is(new Close(CloseReason.DONE).hashCode()));
		assertThat(new Close(CloseReason.DONE), not(equalTo(new Close(CloseReason.BROKEN))));
	}

	@Example
	public void shouldCarryAnyNumberOfBytesFromNoneUp() {
		for (int length : List.of(0, 1, 2, 255, 256, 65_535, 65_536, 1 << 20)) {
			final byte[] bytes = counting(length);

			assertThat(new Data(bytes).bytes(), is(bytes));
			assertThat(new Datagram(TARGET, bytes).bytes(), is(bytes));
			assertThat(new Data(bytes).toString(), is("Data[" + length + " bytes]"));
			assertThat(new Datagram(TARGET, bytes).toString(), is("Datagram[" + TARGET + ", " + length + " bytes]"));
		}
	}

	@Example
	public void shouldNotShareTheArrayItWasBuiltFrom() {
		final byte[] source = {1, 2, 3};
		final Data data = new Data(source);
		final Datagram datagram = new Datagram(TARGET, source);

		source[0] = 9;
		source[1] = 9;
		source[2] = 9;
		assertThat(data.bytes(), is(PAYLOAD));
		assertThat(datagram.bytes(), is(PAYLOAD));
		assertThat(data, equalTo(new Data(PAYLOAD)));
		assertThat(datagram, equalTo(new Datagram(TARGET, PAYLOAD)));
	}

	@Example
	public void shouldHandOutACopyOfItsBytesEveryTime() {
		final Data data = new Data(PAYLOAD);
		final Datagram datagram = new Datagram(TARGET, PAYLOAD);

		data.bytes()[0] = 9;
		data.bytes()[2] = 9;
		datagram.bytes()[0] = 9;
		datagram.bytes()[2] = 9;
		assertThat(data.bytes(), is(PAYLOAD));
		assertThat(datagram.bytes(), is(PAYLOAD));
		assertThat(data.bytes(), not(sameInstance(data.bytes())));
		assertThat(datagram.bytes(), not(sameInstance(datagram.bytes())));
		assertThat(data, equalTo(new Data(PAYLOAD)));
		assertThat(datagram, equalTo(new Datagram(TARGET, PAYLOAD)));
	}

	@Example
	public void shouldTellDataApartByLengthAndByEveryPosition() {
		final Data data = new Data(PAYLOAD);

		assertThat(data.equals(data), is(true));
		assertThat(data.equals(new Data(new byte[] {1, 2, 3})), is(true));
		assertThat(new Data(new byte[0]).equals(new Data(new byte[0])), is(true));

		for (byte[] other : OTHER_PAYLOADS) {
			assertThat(Arrays.toString(other), data.equals(new Data(other)), is(false));
			assertThat(Arrays.toString(other), new Data(other).equals(data), is(false));
			assertThat(Arrays.toString(other), data.hashCode(), not(is(new Data(other).hashCode())));
		}
	}

	@Example
	public void shouldTellDatagramsApartByAddressAndByEveryPosition() {
		final Datagram datagram = new Datagram(TARGET, PAYLOAD);

		assertThat(datagram.equals(datagram), is(true));
		assertThat(datagram.equals(new Datagram(TARGET, new byte[] {1, 2, 3})), is(true));
		assertThat(datagram.hashCode(), is(new Datagram(TARGET, new byte[] {1, 2, 3}).hashCode()));

		for (byte[] other : OTHER_PAYLOADS) {
			assertThat(Arrays.toString(other), datagram.equals(new Datagram(TARGET, other)), is(false));
			assertThat(Arrays.toString(other), new Datagram(TARGET, other).equals(datagram), is(false));
			assertThat(Arrays.toString(other), datagram.hashCode(), not(is(new Datagram(TARGET, other).hashCode())));
		}

		for (SocksAddress other : OTHER_ADDRESSES) {
			assertThat(other.toString(), datagram.equals(new Datagram(other, PAYLOAD)), is(false));
			assertThat(other.toString(), new Datagram(other, PAYLOAD).equals(datagram), is(false));
			assertThat(other.toString(), datagram.hashCode(), not(is(new Datagram(other, PAYLOAD).hashCode())));
		}
	}

	@Example
	public void shouldNeverEqualAnythingButItsOwnKind() {
		final Data data = new Data(PAYLOAD);
		final Datagram datagram = new Datagram(TARGET, PAYLOAD);

		assertThat(data.equals(null), is(false));
		assertThat(datagram.equals(null), is(false));
		assertThat(data.equals(datagram), is(false));
		assertThat(datagram.equals(data), is(false));
		assertThat(data.equals(PAYLOAD), is(false));
		assertThat(datagram.equals(TARGET), is(false));
		assertThat(data.equals("Data[3 bytes]"), is(false));
	}

	@Example
	public void shouldShowOnlyTheLengthOfTheBytesItCarries() {
		final byte[] secret = "secret".getBytes(StandardCharsets.US_ASCII);

		assertThat(new Data(secret).toString(), is("Data[6 bytes]"));
		assertThat(new Data(new byte[0]).toString(), is("Data[0 bytes]"));
		assertThat(new Datagram(TARGET, secret).toString(), is("Datagram[" + TARGET + ", 6 bytes]"));
		assertThat(new Datagram(TARGET, new byte[0]).toString(), is("Datagram[" + TARGET + ", 0 bytes]"));
	}

	@Example
	public void shouldNumberTheCloseReasonsFromZeroWithoutGaps() {
		assertThat(CloseReason.values().length, is(5));
		assertThat(CloseReason.DONE.code(), is((byte) 0));
		assertThat(CloseReason.IDLE.code(), is((byte) 1));
		assertThat(CloseReason.REFUSED.code(), is((byte) 2));
		assertThat(CloseReason.UNREACHABLE.code(), is((byte) 3));
		assertThat(CloseReason.BROKEN.code(), is((byte) 4));
	}

	@Example
	public void shouldNameACloseReasonForExactlyTheCodesZeroToFour() {
		for (int value = Byte.MIN_VALUE; value <= Byte.MAX_VALUE; value++) {
			final Optional<CloseReason> reason = CloseReason.of((byte) value);

			assertThat("code " + value, reason.isPresent(), is(value >= 0 && value <= 4));
			assertThat("code " + value, reason.map(CloseReason::code).orElse((byte) value), is((byte) value));
		}

		assertThat(CloseReason.of((byte) 0), equalTo(Optional.of(CloseReason.DONE)));
		assertThat(CloseReason.of((byte) 4), equalTo(Optional.of(CloseReason.BROKEN)));
		assertThat(CloseReason.of((byte) -1), equalTo(Optional.empty()));
		assertThat(CloseReason.of(Byte.MIN_VALUE), equalTo(Optional.empty()));
		assertThat(CloseReason.of(Byte.MAX_VALUE), equalTo(Optional.empty()));
	}
}

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

import io.netty.handler.codec.socksx.v5.Socks5AddressType;
import io.netty.handler.codec.socksx.v5.Socks5CommandStatus;
import java.util.ArrayList;
import java.util.List;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.unigrid.hedgehog.proxy.Expect;
import org.unigrid.hedgehog.proxy.session.CloseReason;
import org.unigrid.hedgehog.proxy.session.SocksAddress;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

public class TunnelTest {
	private static final SocksAddress TARGET = new SocksAddress(Socks5AddressType.DOMAIN, "example.org", 443);

	@Example
	public void shouldNameTheSocksReplyOfEveryFailure() {
		assertThat(TunnelFailure.NOT_ALLOWED.status(), is(Socks5CommandStatus.FORBIDDEN));
		assertThat(TunnelFailure.GENERAL_FAILURE.status(), is(Socks5CommandStatus.FAILURE));
		assertThat(TunnelFailure.TTL_EXPIRED.status(), is(Socks5CommandStatus.TTL_EXPIRED));

		for (TunnelFailure failure : TunnelFailure.values()) {
			assertThat(failure.status().isSuccess(), is(false));
			assertThat(TunnelFailure.of(failure.status()), is(failure));
		}
	}

	@Property(tries = 30)
	public void shouldTakeAnUnknownReplyAsAGeneralFailure(@ForAll @IntRange(min = 9, max = 255) int code) {
		assertThat(TunnelFailure.of(Socks5CommandStatus.valueOf((byte) code)), is(TunnelFailure.GENERAL_FAILURE));
	}

	@Example
	public void shouldTakeTheFirstAndLastUnknownRepliesAsGeneralFailures() {
		shouldTakeAnUnknownReplyAsAGeneralFailure(9);
		shouldTakeAnUnknownReplyAsAGeneralFailure(255);
	}

	@Example
	public void shouldRefuseToBuildARefusingTunnelWithoutAFailure() {
		Expect.assertThrows(NullPointerException.class, () -> Tunnel.refusing(null));
	}

	@Example
	public void shouldRefuseToCallSuccessAFailure() {
		Expect.assertThrows(IllegalArgumentException.class, () -> TunnelFailure.of(Socks5CommandStatus.SUCCESS));
	}

	@Example
	public void shouldTellWhyASessionClosedBeforeItsReply() {
		assertThat(TunnelFailure.of(CloseReason.REFUSED), is(TunnelFailure.NOT_ALLOWED));
		assertThat(TunnelFailure.of(CloseReason.UNREACHABLE), is(TunnelFailure.NETWORK_UNREACHABLE));
		assertThat(TunnelFailure.of(CloseReason.IDLE), is(TunnelFailure.TTL_EXPIRED));
		assertThat(TunnelFailure.of(CloseReason.BROKEN), is(TunnelFailure.GENERAL_FAILURE));
		assertThat(TunnelFailure.of(CloseReason.DONE), is(TunnelFailure.GENERAL_FAILURE));
	}

	@Example
	public void shouldPairEveryFailureWithItsOwnSocksReply() {
		pair(TunnelFailure.NOT_ALLOWED, Socks5CommandStatus.FORBIDDEN);
		pair(TunnelFailure.GENERAL_FAILURE, Socks5CommandStatus.FAILURE);
		pair(TunnelFailure.NETWORK_UNREACHABLE, Socks5CommandStatus.NETWORK_UNREACHABLE);
		pair(TunnelFailure.HOST_UNREACHABLE, Socks5CommandStatus.HOST_UNREACHABLE);
		pair(TunnelFailure.CONNECTION_REFUSED, Socks5CommandStatus.CONNECTION_REFUSED);
		pair(TunnelFailure.TTL_EXPIRED, Socks5CommandStatus.TTL_EXPIRED);
		pair(TunnelFailure.COMMAND_UNSUPPORTED, Socks5CommandStatus.COMMAND_UNSUPPORTED);
		pair(TunnelFailure.ADDRESS_UNSUPPORTED, Socks5CommandStatus.ADDRESS_UNSUPPORTED);
		assertThat(TunnelFailure.values().length, is(8));
	}

	@Example
	public void shouldRefuseANullReplyOrReason() {
		Expect.assertThrows(NullPointerException.class, () -> TunnelFailure.of((Socks5CommandStatus) null));
		Expect.assertThrows(NullPointerException.class, () -> TunnelFailure.of((CloseReason) null));
	}

	@Example
	public void shouldFailEveryStreamOfARefusingTunnelAtOnceWithItsOwnFailure() {
		for (TunnelFailure failure : TunnelFailure.values()) {
			final List<String> heard = new ArrayList<>();
			final TunnelStream stream = Tunnel.refusing(failure).open(TARGET, listenerOf(heard));

			assertThat(heard, equalTo(List.of("failed " + failure)));
			stream.write(new byte[] { 1 });
			stream.setReadable(true);
			stream.close();
			assertThat(heard, equalTo(List.of("failed " + failure)));
			assertThat(stream.isWritable(), is(false));
		}
	}

	private static void pair(TunnelFailure failure, Socks5CommandStatus status) {
		assertThat(failure.status(), is(status));
		assertThat(TunnelFailure.of(status), is(failure));
	}

	private static TunnelListener listenerOf(List<String> heard) {
		return new TunnelListener() {
			@Override
			public void connected(SocksAddress bound) {
				heard.add("connected");
			}

			@Override
			public void failed(TunnelFailure failure) {
				heard.add("failed " + failure);
			}

			@Override
			public void received(byte[] bytes) {
				heard.add("received");
			}

			@Override
			public void writabilityChanged(boolean writable) {
				heard.add("writable " + writable);
			}

			@Override
			public void closed() {
				heard.add("closed");
			}
		};
	}

	/* What the front end heard once associate returned; the handle it got takes no datagram and the front end hears
	   nothing more, whatever it does with it */
	private static List<String> associated(Tunnel tunnel) {
		final List<String> heard = new ArrayList<>();
		final AssociationStream association = tunnel.associate(new AssociationListener() {
			@Override
			public void opened() {
				heard.add("opened");
			}

			@Override
			public void failed(TunnelFailure failure) {
				heard.add("failed " + failure);
			}

			@Override
			public void received(SocksAddress source, byte[] bytes) {
				heard.add("received");
			}

			@Override
			public void closed() {
				heard.add("closed");
			}
		});
		final List<String> told = List.copyOf(heard);

		assertThat(association.send(TARGET, new byte[] { 1 }), is(false));
		assertThat(association.send(TARGET, new byte[0]), is(false));
		association.close();
		association.close();
		assertThat(heard, equalTo(told));
		return told;
	}

	@Example
	public void shouldFailEveryAssociationOfARefusingTunnelAtOnceWithItsOwnFailure() {
		for (TunnelFailure failure : TunnelFailure.values()) {
			assertThat(associated(Tunnel.refusing(failure)), equalTo(List.of("failed " + failure)));
		}
	}

	@Example
	public void shouldRefuseEveryAssociationOfATunnelThatRelaysNoDatagrams() {
		final Tunnel streamsOnly = (destination, listener) -> {
			throw new AssertionError("An association opened a stream");
		};

		assertThat(associated(streamsOnly), equalTo(List.of("failed COMMAND_UNSUPPORTED")));
	}
}

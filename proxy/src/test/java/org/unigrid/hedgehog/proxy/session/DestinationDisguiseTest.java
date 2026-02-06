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
import io.netty.util.NetUtil;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.Size;
import org.unigrid.hedgehog.proxy.Expect;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/* Local IPv4 addresses hidden in IPv6 transition forms, and names that are numbers or the local host */
public class DestinationDisguiseTest {
	private static final byte[] PUBLIC_IPV4 = { 8, 8, 8, 8 };
	private static final Resolver NEVER = host -> {
		throw new AssertionError("A disguised name is never resolved, asked for " + host);
	};

	/* 6to4 of 127.0.0.1, 10.0.0.1 and 192.168.1.1; Teredo with server 127.0.0.1; Teredo with public server
	   8.8.8.8 and client 10.0.0.1 (stored as f5ff:fffe); local-use NAT64; IPv4-translated 127.0.0.1; ISATAP of
	   127.0.0.1 and of 10.0.0.1 */
	private static final List<String> REFUSED = List.of("2002:7f00:1::1", "2002:a00:1::", "2002:c0a8:101::1",
		"2001:0:7f00:1::1", "2001:0:808:808::f5ff:fffe", "64:ff9b:1::808:808", "::ffff:0:7f00:1", "::5efe:7f00:1",
		"2a00:1450:4001:1:200:5efe:a00:1");
	/* 6to4 of 8.8.8.8; Teredo with server and client 8.8.8.8 (stored as f7f7:f7f7); an ordinary 2001: address;
	   IPv4-translated 8.8.8.8; ISATAP of 8.8.8.8 */
	private static final List<String> ALLOWED = List.of("2002:808:808::1", "2001:0:808:808::f7f7:f7f7",
		"2001:4860:4860::8888", "::ffff:0:808:808", "2a00:1450:4001:1:200:5efe:808:808");
	/* Each is one bit off a transition prefix or ISATAP identifier, with 10.0.0.1 where the IPv4 address would be */
	private static final List<String> OUTSIDE_TRANSITION_FORMS = List.of("2003:a00:1::1", "2001:1:a00:1::f5ff:fffe",
		"::ffff:1:a00:1", "64:ff9b:2::a00:1", "2a00:1450:4001::5eff:a00:1", "2a00:1450:4001::1:5efe:a00:1");
	private static final List<String> LOCAL_NAMES = List.of("localhost", "LocalHost.", "LOCALHOST", "db.localhost",
		"db.localhost.", "localhost..", "db.LocalHost...", ".localhost");

	private static InetAddress address(String literal) throws UnknownHostException {
		return InetAddress.getByAddress(NetUtil.createByteArrayFromIpAddressString(literal));
	}

	private static InetAddress sixToFour(byte[] ipv4) throws UnknownHostException {
		final byte[] bytes = new byte[16];

		bytes[0] = 0x20;
		bytes[1] = 0x02;
		System.arraycopy(ipv4, 0, bytes, 2, 4);
		return Inet6Address.getByAddress(null, bytes, 0);
	}

	private static InetAddress teredo(byte[] server, byte[] client) throws UnknownHostException {
		final byte[] bytes = new byte[16];

		bytes[0] = 0x20;
		bytes[1] = 0x01;
		System.arraycopy(server, 0, bytes, 4, 4);

		for (int i = 0; i < 4; i++) {
			bytes[12 + i] = (byte) ~client[i];
		}

		return Inet6Address.getByAddress(null, bytes, 0);
	}

	private static InetAddress ipv4Translated(byte[] ipv4) throws UnknownHostException {
		final byte[] bytes = new byte[16];

		bytes[8] = (byte) 0xff;
		bytes[9] = (byte) 0xff;
		System.arraycopy(ipv4, 0, bytes, 12, 4);
		return Inet6Address.getByAddress(null, bytes, 0);
	}

	/* Under a global prefix, so that only the IPv4 address decides. The identifier is 0x00 or, when the IPv4 address
	   is global, 0x02 in front of 0x00 0x5e 0xfe. */
	private static InetAddress isatap(byte[] ipv4, int identifier) throws UnknownHostException {
		final byte[] bytes = { 0x2a, 0x00, 0x14, 0x50, 0x40, 0x01, 0x00, 0x01, (byte) identifier, 0x00, 0x5e,
			(byte) 0xfe, 0, 0, 0, 0 };

		System.arraycopy(ipv4, 0, bytes, 12, 4);
		return Inet6Address.getByAddress(null, bytes, 0);
	}

	private static Socks5CommandStatus refusal(DestinationPolicy policy, String name) {
		return Expect.assertThrows(DestinationRefusedException.class, () -> policy.admit(new SocksAddress(
			Socks5AddressType.DOMAIN, name, 443), NEVER)).status();
	}

	@Example
	public void shouldHoldIpv4InTransitionFormsToTheIpv4Rules() throws UnknownHostException {
		for (String literal : REFUSED) {
			assertThat(literal, DestinationPolicy.publicOnly().allows(address(literal)), is(false));
		}

		for (String literal : ALLOWED) {
			assertThat(literal, DestinationPolicy.publicOnly().allows(address(literal)), is(true));
		}
	}

	@Example
	public void shouldJudgeAnAddressNextToATransitionFormByTheIpv6RulesOnly() throws UnknownHostException {
		for (String literal : OUTSIDE_TRANSITION_FORMS) {
			assertThat(literal, DestinationPolicy.publicOnly().allows(address(literal)), is(true));
		}
	}

	@Property(tries = 300)
	public void shouldRefuseA6to4OrTeredoAddressExactlyWhenItsIpv4IsRefused(@ForAll @Size(4) byte[] ipv4)
		throws UnknownHostException {
		final boolean allowed = DestinationPolicy.publicOnly().allows(InetAddress.getByAddress(ipv4));

		assertThat(DestinationPolicy.publicOnly().allows(sixToFour(ipv4)), is(allowed));
		assertThat(DestinationPolicy.publicOnly().allows(teredo(PUBLIC_IPV4, ipv4)), is(allowed));
		assertThat(DestinationPolicy.publicOnly().allows(teredo(ipv4, PUBLIC_IPV4)), is(allowed));
	}

	@Property(tries = 300)
	public void shouldRefuseAnIpv4TranslatedAddressExactlyWhenItsIpv4IsRefused(@ForAll @Size(4) byte[] ipv4)
		throws UnknownHostException {
		final boolean allowed = DestinationPolicy.publicOnly().allows(InetAddress.getByAddress(ipv4));

		assertThat(DestinationPolicy.publicOnly().allows(ipv4Translated(ipv4)), is(allowed));
	}

	@Property(tries = 300)
	public void shouldRefuseAnIsatapAddressExactlyWhenItsIpv4IsRefused(@ForAll @Size(4) byte[] ipv4,
		@ForAll boolean global) throws UnknownHostException {

		final boolean allowed = DestinationPolicy.publicOnly().allows(InetAddress.getByAddress(ipv4));

		assertThat(DestinationPolicy.publicOnly().allows(isatap(ipv4, global ? 2 : 0)), is(allowed));
	}

	@Example
	public void shouldRefuseTheLocalHostByNameWithoutResolvingIt() {
		for (String name : LOCAL_NAMES) {
			assertThat(name, refusal(DestinationPolicy.publicOnly(), name), is(Socks5CommandStatus.FORBIDDEN));
		}
	}

	@Example
	public void shouldResolveOrdinaryNamesWithOrWithoutTheTrailingDot() throws Exception {
		final List<InetAddress> answer = List.of(address("93.184.215.14"));

		for (String name : List.of("example.org", "example.org.", "1.example.org", "123abc.org", "0x7f.example",
			"notlocalhost", "localhost.example", "localhostx", "my-localhost.")) {
			final SocksAddress destination = new SocksAddress(Socks5AddressType.DOMAIN, name, 80);

			assertThat(name, DestinationPolicy.publicOnly().admit(destination, host -> answer), equalTo(answer));
		}
	}
}

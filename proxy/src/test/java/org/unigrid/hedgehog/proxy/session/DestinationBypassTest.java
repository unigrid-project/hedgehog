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
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;

/* Every bypass class of the exit's destination policy, each named, against the policy exits run with. A row spells
   one class in a few ways and every spelling must be refused. 127.0.0.1 is 7f00:1, 10.0.0.1 is a00:1 and the
   cloud metadata address 169.254.169.254 is a9fe:a9fe; a Teredo client is stored with every bit flipped, so
   127.0.0.1 is 80ff:fffe, 10.0.0.1 is f5ff:fffe and 169.254.169.254 is 5601:5601, and 8.8.8.8 is f7f7:f7f7. */
public class DestinationBypassTest {
	private static final int PORT = 443;
	private static final Resolver NEVER = host -> {
		throw new AssertionError("A name that spells an address or the local host is never resolved, asked for "
			+ host);
	};

	private record Bypass(String name, SocksAddress destination, Resolver resolver) {
		/* One class and one spelling of it */
	}

	private static InetAddress address(String literal) throws UnknownHostException {
		return InetAddress.getByAddress(NetUtil.createByteArrayFromIpAddressString(literal));
	}

	private static Stream<Bypass> literals(String name, Socks5AddressType type, String... hosts) {
		return Arrays.stream(hosts).map(host -> new Bypass(name, new SocksAddress(type, host, PORT), NEVER));
	}

	private static Stream<Bypass> ipv4(String name, String... hosts) {
		return literals(name, Socks5AddressType.IPv4, hosts);
	}

	private static Stream<Bypass> ipv6(String name, String... hosts) {
		return literals(name, Socks5AddressType.IPv6, hosts);
	}

	private static Stream<Bypass> names(String name, String... hosts) {
		return literals(name, Socks5AddressType.DOMAIN, hosts);
	}

	private static Stream<Bypass> resolving(String name, String host, String... answers) {
		final Resolver resolver = ignored -> {
			final List<InetAddress> addresses = new ArrayList<>();

			for (String answer : answers) {
				addresses.add(address(answer));
			}

			return addresses;
		};

		return Stream.of(new Bypass(name, new SocksAddress(Socks5AddressType.DOMAIN, host, PORT), resolver));
	}

	private static final List<Bypass> BYPASSES = Stream.of(
		ipv4("range 0.0.0.0/8", "0.0.0.0", "0.255.255.255"),
		ipv4("range 10.0.0.0/8", "10.0.0.1", "10.255.255.255"),
		ipv4("range 100.64.0.0/10", "100.64.0.1", "100.127.255.255"),
		ipv4("range 127.0.0.0/8", "127.0.0.1", "127.255.255.254"),
		ipv4("range 169.254.0.0/16 and cloud metadata", "169.254.169.254", "169.254.0.1", "169.254.255.255"),
		ipv4("range 172.16.0.0/12", "172.16.0.1", "172.31.255.255"),
		ipv4("range 192.0.0.0/24", "192.0.0.1", "192.0.0.255"),
		ipv4("range 192.0.2.0/24", "192.0.2.1"),
		ipv4("range 192.168.0.0/16", "192.168.0.1", "192.168.255.255"),
		ipv4("range 198.18.0.0/15", "198.18.0.1", "198.19.255.255"),
		ipv4("range 198.51.100.0/24", "198.51.100.1"),
		ipv4("range 203.0.113.0/24", "203.0.113.1"),
		ipv4("range 224.0.0.0/4", "224.0.0.1", "239.255.255.255"),
		ipv4("range 240.0.0.0/4", "240.0.0.1", "255.255.255.254"),
		ipv4("broadcast 255.255.255.255", "255.255.255.255"),
		ipv6("unspecified ::", "::"),
		ipv6("loopback ::1", "::1"),
		ipv6("range fc00::/7", "fc00::1", "fdff:ffff:ffff:ffff:ffff:ffff:ffff:ffff"),
		ipv6("range fe80::/10", "fe80::1", "febf::1"),
		ipv6("range fec0::/10", "fec0::1"),
		ipv6("range ff00::/8", "ff02::1", "ff0e::1"),
		ipv6("range 2001:db8::/32", "2001:db8::1"),
		ipv6("IPv4-mapped", "::ffff:127.0.0.1", "::ffff:a00:1", "::ffff:a9fe:a9fe"),
		ipv6("IPv4-compatible", "::7f00:1", "::a00:1", "::a9fe:a9fe"),
		ipv6("NAT64 64:ff9b::/96", "64:ff9b::7f00:1", "64:ff9b::a00:1", "64:ff9b::a9fe:a9fe"),
		ipv6("NAT64 local-use 64:ff9b:1::/48", "64:ff9b:1::1", "64:ff9b:1::808:808", "64:ff9b:1:ffff::7f00:1"),
		ipv6("6to4 2002::/16", "2002:7f00:1::1", "2002:a00:1::", "2002:a9fe:a9fe::1"),
		ipv6("Teredo server", "2001:0:7f00:1::f7f7:f7f7", "2001:0:a9fe:a9fe::f7f7:f7f7"),
		ipv6("Teredo client", "2001:0:808:808::80ff:fffe", "2001:0:808:808::f5ff:fffe", "2001:0:808:808::5601:5601"),
		ipv6("ISATAP 0:5efe", "::5efe:7f00:1", "2a00:1450:4001::5efe:a00:1", "2a00:1450:4001::5efe:a9fe:a9fe"),
		ipv6("ISATAP 200:5efe", "::200:5efe:7f00:1", "2a00:1450:4001::200:5efe:a00:1",
			"2a00:1450:4001::200:5efe:a9fe:a9fe"),
		ipv6("ISATAP link-local fe80::5efe", "fe80::5efe:7f00:1"),
		ipv6("SIIT IPv4-translated ::ffff:0:0:0/96", "::ffff:0:7f00:1", "::ffff:0:a00:1", "::ffff:0:a9fe:a9fe"),
		ipv6("IPv6 zone id", "fe80::1%eth0", "::1%lo", "fe80::5efe:7f00:1%1"),
		names("localhost", "localhost", "LOCALHOST", "LocalHost"),
		names("localhost with a trailing dot", "localhost.", "LocalHost."),
		names("any name below localhost", "db.localhost", "a.b.localhost", "db.localhost."),
		resolving("name resolving to loopback", "rebind.example", "127.0.0.1"),
		resolving("name with a trailing dot resolving to loopback", "rebind.example.", "127.0.0.1"),
		resolving("name resolving to cloud metadata", "metadata.example", "169.254.169.254"),
		resolving("name resolving to a private range", "internal.example", "10.0.0.1", "172.16.0.1", "192.168.1.1"),
		resolving("name resolving to an IPv4-mapped address", "mapped.example", "::ffff:127.0.0.1"),
		resolving("name resolving to a transition address", "transition.example", "64:ff9b::7f00:1",
			"2002:7f00:1::1", "2001:0:7f00:1::f7f7:f7f7", "::5efe:7f00:1", "::ffff:0:7f00:1"),
		resolving("name with a public answer and a private one", "mixed.example", "93.184.216.34", "10.0.0.1"),
		resolving("name with a private answer and a public one", "mixed.example", "10.0.0.1", "93.184.216.34"))
		.flatMap(Function.identity()).toList();

	/* Anything but a refusal as forbidden is a bypass: an unreachable host would hide a bad row */
	private static boolean notForbidden(Bypass bypass) {
		try {
			DestinationPolicy.publicOnly().admit(bypass.destination(), bypass.resolver());
			return true;
		} catch (DestinationRefusedException refusal) {
			return refusal.status() != Socks5CommandStatus.FORBIDDEN;
		}
	}

	@Example
	public void shouldRefuseEveryBypassClassWithOnlyPublicDestinationsAllowed() {
		final List<String> bypassed = BYPASSES.stream().filter(DestinationBypassTest::notForbidden)
			.map(bypass -> bypass.name() + ": " + bypass.destination().host()).toList();

		assertThat("Bypasses the public policy did not refuse as forbidden", bypassed, empty());
	}
}

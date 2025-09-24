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

import io.netty.util.NetUtil;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class DestinationPolicyTest {
	private static final List<String> REFUSED = List.of("0.0.0.0", "0.1.2.3", "10.1.2.3", "100.64.0.1",
		"100.127.255.255", "127.0.0.1", "127.255.255.254", "169.254.169.254", "172.16.0.1", "172.31.255.255",
		"192.0.0.8", "192.0.2.5", "192.168.1.1", "198.18.0.1", "198.19.255.255", "198.51.100.7", "203.0.113.9",
		"224.0.0.251", "239.255.255.250", "240.0.0.1", "255.255.255.255", "::", "::1", "fc00::1", "fd12:3456::1",
		"fe80::1", "febf::1", "fec0::1", "ff02::1", "ff0e::1", "2001:db8::1", "::ffff:10.0.0.1", "::ffff:127.0.0.1",
		"::ffff:192.168.1.1", "::10.0.0.1", "::127.0.0.1", "64:ff9b::a00:1", "64:ff9b::7f00:1",
		"10.255.255.255", "169.254.0.0", "169.254.255.255", "172.16.0.0", "192.0.0.0", "192.0.0.255", "192.0.2.0",
		"192.0.2.255", "192.168.0.0", "192.168.255.255", "198.51.100.0", "198.51.100.255", "203.0.113.0",
		"203.0.113.255", "fdff:ffff:ffff:ffff:ffff:ffff:ffff:ffff", "feff:ffff::1", "ff00::",
		"2001:db8:ffff:ffff:ffff:ffff:ffff:ffff");
	private static final List<String> ALLOWED = List.of("1.1.1.1", "8.8.8.8", "11.0.0.1", "100.63.255.255",
		"100.128.0.0", "126.255.255.255", "128.0.0.1", "169.253.255.255", "169.255.0.1", "172.15.255.255",
		"172.32.0.0", "192.0.1.1", "192.169.0.1", "198.17.255.255", "198.20.0.0", "223.255.255.255",
		"2001:4860:4860::8888", "2606:4700:4700::1111", "2001:db9::1", "fbff::1", "::ffff:8.8.8.8",
		"64:ff9b::808:808", "::8.8.8.8", "192.0.1.255", "192.0.3.0", "192.167.255.255", "198.51.99.255",
		"198.51.101.0", "203.0.112.255", "203.0.114.0", "fe7f:ffff::1", "2001:db7:ffff::1");
	private static final List<String> LOOPBACK = List.of("127.0.0.1", "127.255.255.254", "::1",
		"::ffff:127.0.0.1", "::127.0.0.1", "64:ff9b::7f00:1");

	/* The neighbour of a range that is null is a refused address too, the edge of the block next to it */
	private record Range(String first, String last, String before, String after) {
		List<String> neighbours() {
			return Stream.of(before, after).filter(Objects::nonNull).toList();
		}

		List<String> edges() {
			return List.of(first, last);
		}
	}

	private static final Range IPV4_LOOPBACK = new Range("127.0.0.0", "127.255.255.255", "126.255.255.255",
		"128.0.0.0");
	private static final Range IPV6_LOOPBACK = new Range("::1", "::1", null, null);
	private static final List<Range> LOOPBACK_RANGES = List.of(IPV4_LOOPBACK, IPV6_LOOPBACK);
	private static final List<Range> IPV4_RANGES = List.of(new Range("0.0.0.0", "0.255.255.255", null, "1.0.0.0"),
		new Range("10.0.0.0", "10.255.255.255", "9.255.255.255", "11.0.0.0"),
		new Range("100.64.0.0", "100.127.255.255", "100.63.255.255", "100.128.0.0"), IPV4_LOOPBACK,
		new Range("169.254.0.0", "169.254.255.255", "169.253.255.255", "169.255.0.0"),
		new Range("172.16.0.0", "172.31.255.255", "172.15.255.255", "172.32.0.0"),
		new Range("192.0.0.0", "192.0.0.255", "191.255.255.255", "192.0.1.0"),
		new Range("192.0.2.0", "192.0.2.255", "192.0.1.255", "192.0.3.0"),
		new Range("192.168.0.0", "192.168.255.255", "192.167.255.255", "192.169.0.0"),
		new Range("198.18.0.0", "198.19.255.255", "198.17.255.255", "198.20.0.0"),
		new Range("198.51.100.0", "198.51.100.255", "198.51.99.255", "198.51.101.0"),
		new Range("203.0.113.0", "203.0.113.255", "203.0.112.255", "203.0.114.0"),
		new Range("224.0.0.0", "239.255.255.255", "223.255.255.255", null),
		new Range("240.0.0.0", "255.255.255.255", null, null));
	private static final List<Range> IPV6_RANGES = List.of(new Range("::", "::", null, null), IPV6_LOOPBACK,
		new Range("fc00::", "fdff:ffff:ffff:ffff:ffff:ffff:ffff:ffff", "fbff:ffff:ffff:ffff:ffff:ffff:ffff:ffff",
			"fe00::"),
		new Range("fe80::", "febf:ffff:ffff:ffff:ffff:ffff:ffff:ffff", "fe7f:ffff:ffff:ffff:ffff:ffff:ffff:ffff",
			null),
		new Range("fec0::", "feff:ffff:ffff:ffff:ffff:ffff:ffff:ffff", null, null),
		new Range("ff00::", "ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff", null, null),
		new Range("2001:db8::", "2001:db8:ffff:ffff:ffff:ffff:ffff:ffff", "2001:db7:ffff:ffff:ffff:ffff:ffff:ffff",
			"2001:db9::"));
	private static final List<Range> RANGES = Stream.concat(IPV4_RANGES.stream(), IPV6_RANGES.stream()).toList();
	private static final List<String> PUBLIC_IPV4 = List.of("1.1.1.1", "8.8.8.8");

	private static final int[] IPV4_MAPPED = { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xff, 0xff };
	private static final int[] NAT64 = { 0, 0x64, 0xff, 0x9b };
	private static final int[] IPV4_COMPATIBLE = {};
	private static final int[] ZONES = { 0, 1, 3, Integer.MAX_VALUE };

	/* Each is one bit off an embedding prefix, with 10.0.0.1 in the last four bytes: only the IPv6 rules apply */
	private static final List<String> OUTSIDE_EMBEDDING_PREFIXES = List.of("::fffe:a00:1", "::1:ffff:a00:1",
		"0:0:0:1:0:ffff:a00:1", "100::ffff:a00:1", "::1:a00:1", "::1:0:a00:1", "0:0:0:1::a00:1", "100::a00:1",
		"64:ff9b::1:a00:1", "64:ff9b::1:0:a00:1", "64:ff9b:0:1::a00:1", "64:ff9a::a00:1", "65:ff9b::a00:1");

	private static InetAddress address(String literal) throws UnknownHostException {
		return InetAddress.getByAddress(NetUtil.createByteArrayFromIpAddressString(literal));
	}

	/* InetAddress turns an IPv4-mapped address into an Inet4Address, an Inet6Address keeps the IPv6 form */
	private static InetAddress ipv6(byte[] ipv4, int... prefix) throws UnknownHostException {
		final byte[] bytes = new byte[16];

		for (int i = 0; i < prefix.length; i++) {
			bytes[i] = (byte) prefix[i];
		}

		System.arraycopy(ipv4, 0, bytes, 12, 4);
		return Inet6Address.getByAddress(null, bytes, 0);
	}

	private static List<InetAddress> embeddingForms(String ipv4) throws UnknownHostException {
		final byte[] bytes = NetUtil.createByteArrayFromIpAddressString(ipv4);

		return List.of(ipv6(bytes, IPV4_MAPPED), ipv6(bytes, NAT64), ipv6(bytes, IPV4_COMPATIBLE));
	}

	private static InetAddress zoned(byte[] bytes, int zone) throws UnknownHostException {
		return Inet6Address.getByAddress(null, bytes, zone);
	}

	private static List<String> ipv6Only(List<String> literals) {
		return literals.stream().filter(literal -> literal.contains(":")).toList();
	}

	@Example
	public void shouldRefuseTheLocalAndReservedRanges() throws UnknownHostException {
		for (String literal : REFUSED) {
			assertThat(literal, DestinationPolicy.publicOnly().allows(address(literal)), is(false));
		}
	}

	@Example
	public void shouldAllowPublicAddresses() throws UnknownHostException {
		for (String literal : ALLOWED) {
			assertThat(literal, DestinationPolicy.publicOnly().allows(address(literal)), is(true));
			assertThat(literal, DestinationPolicy.allowLoopback().allows(address(literal)), is(true));
		}
	}

	@Example
	public void shouldJudgeAnIpv6AddressWithoutItsZone() throws UnknownHostException {
		final byte[] linkLocal = NetUtil.createByteArrayFromIpAddressString("fe80::1");
		final byte[] global = NetUtil.createByteArrayFromIpAddressString("2001:4860:4860::8888");

		assertThat(DestinationPolicy.publicOnly().allows(Inet6Address.getByAddress(null, linkLocal, 3)), is(false));
		assertThat(DestinationPolicy.publicOnly().allows(Inet6Address.getByAddress(null, global, 3)), is(true));
	}

	@Example
	public void shouldAllowOnlyLoopbackWhenTestsAskForIt() throws UnknownHostException {
		for (String literal : LOOPBACK) {
			assertThat(literal, DestinationPolicy.allowLoopback().allows(address(literal)), is(true));
		}

		for (String literal : REFUSED) {
			if (!LOOPBACK.contains(literal)) {
				assertThat(literal, DestinationPolicy.allowLoopback().allows(address(literal)), is(false));
			}
		}
	}

	@Property(tries = 300)
	public void shouldHoldEmbeddedIpv4ToTheIpv4Rules(@ForAll @Size(4) byte[] ipv4) throws UnknownHostException {
		final boolean allowed = DestinationPolicy.publicOnly().allows(InetAddress.getByAddress(ipv4));

		assertThat(DestinationPolicy.publicOnly().allows(ipv6(ipv4, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xff, 0xff)),
			is(allowed));
		assertThat(DestinationPolicy.publicOnly().allows(ipv6(ipv4, 0, 0x64, 0xff, 0x9b)), is(allowed));
		assertThat(DestinationPolicy.publicOnly().allows(ipv6(ipv4)), is(allowed));
	}

	@Property(tries = 100)
	public void shouldRefuseAllOfAPrivateBlock(@ForAll @IntRange(min = 0, max = 0xFFFFFF) int host)
		throws UnknownHostException {
		final byte[] ipv4 = { 10, (byte) (host >> 16), (byte) (host >> 8), (byte) host };

		assertThat(DestinationPolicy.publicOnly().allows(InetAddress.getByAddress(ipv4)), is(false));
		assertThat(DestinationPolicy.allowLoopback().allows(InetAddress.getByAddress(ipv4)), is(false));
	}

	@Example
	public void shouldRefuseTheFirstAndLastAddressOfEveryRange() throws UnknownHostException {
		for (Range range : RANGES) {
			for (String edge : range.edges()) {
				assertThat(edge, DestinationPolicy.publicOnly().allows(address(edge)), is(false));
			}
		}
	}

	@Example
	public void shouldAllowTheAddressJustOutsideEveryRange() throws UnknownHostException {
		for (Range range : RANGES) {
			for (String outside : range.neighbours()) {
				assertThat(outside, DestinationPolicy.publicOnly().allows(address(outside)), is(true));
				assertThat(outside, DestinationPolicy.allowLoopback().allows(address(outside)), is(true));
			}
		}
	}

	@Example
	public void shouldRefuseTheEdgesOfEveryRangeButLoopbackWhenTestsAskForLoopback() throws UnknownHostException {
		for (Range range : RANGES) {
			for (String edge : range.edges()) {
				assertThat(edge, DestinationPolicy.allowLoopback().allows(address(edge)),
					is(LOOPBACK_RANGES.contains(range)));
			}
		}
	}

	@Example
	public void shouldHoldTheEdgesOfEveryIpv4RangeToTheIpv4RulesInsideEachEmbeddingForm()
		throws UnknownHostException {
		for (Range range : IPV4_RANGES) {
			for (String edge : range.edges()) {
				for (InetAddress wrapped : embeddingForms(edge)) {
					assertThat(wrapped.toString(), DestinationPolicy.publicOnly().allows(wrapped),
						is(false));
					assertThat(wrapped.toString(), DestinationPolicy.allowLoopback().allows(wrapped),
						is(LOOPBACK_RANGES.contains(range)));
				}
			}
		}
	}

	@Example
	public void shouldAllowAPublicIpv4InsideEachEmbeddingForm() throws UnknownHostException {
		final List<String> outside = Stream.concat(PUBLIC_IPV4.stream(),
			IPV4_RANGES.stream().flatMap(range -> range.neighbours().stream())).toList();

		for (String ipv4 : outside) {
			for (InetAddress wrapped : embeddingForms(ipv4)) {
				assertThat(wrapped.toString(), DestinationPolicy.publicOnly().allows(wrapped), is(true));
				assertThat(wrapped.toString(), DestinationPolicy.allowLoopback().allows(wrapped), is(true));
			}
		}
	}

	@Example
	public void shouldRefuseTheCloudMetadataAddressInEveryForm() throws UnknownHostException {
		final List<String> literals = List.of("169.254.169.254", "::ffff:169.254.169.254", "::ffff:a9fe:a9fe",
			"::169.254.169.254", "::a9fe:a9fe", "64:ff9b::a9fe:a9fe");

		for (String literal : literals) {
			assertThat(literal, DestinationPolicy.publicOnly().allows(address(literal)), is(false));
			assertThat(literal, DestinationPolicy.allowLoopback().allows(address(literal)), is(false));
		}

		for (InetAddress wrapped : embeddingForms("169.254.169.254")) {
			assertThat(wrapped.toString(), DestinationPolicy.publicOnly().allows(wrapped), is(false));
			assertThat(wrapped.toString(), DestinationPolicy.allowLoopback().allows(wrapped), is(false));
		}
	}

	@Example
	public void shouldNotApplyTheIpv4RulesJustOutsideAnEmbeddingPrefix() throws UnknownHostException {
		for (String literal : OUTSIDE_EMBEDDING_PREFIXES) {
			assertThat(literal, DestinationPolicy.publicOnly().allows(address(literal)), is(true));
			assertThat(literal, DestinationPolicy.allowLoopback().allows(address(literal)), is(true));
		}
	}

	@Example
	public void shouldJudgeEveryIpv6TableAddressTheSameWhateverItsZone() throws UnknownHostException {
		for (int zone : ZONES) {
			for (String literal : ipv6Only(REFUSED)) {
				final InetAddress scoped = zoned(NetUtil.createByteArrayFromIpAddressString(literal), zone);

				assertThat(literal + "%" + zone, DestinationPolicy.publicOnly().allows(scoped), is(false));
				assertThat(literal + "%" + zone, DestinationPolicy.allowLoopback().allows(scoped),
					is(LOOPBACK.contains(literal)));
			}

			for (String literal : ipv6Only(ALLOWED)) {
				final InetAddress scoped = zoned(NetUtil.createByteArrayFromIpAddressString(literal), zone);

				assertThat(literal + "%" + zone, DestinationPolicy.publicOnly().allows(scoped), is(true));
				assertThat(literal + "%" + zone, DestinationPolicy.allowLoopback().allows(scoped), is(true));
			}
		}
	}

	@Example
	public void shouldJudgeAnEmbeddedIpv4WithoutTheZoneOfItsIpv6Address() throws UnknownHostException {
		for (int zone : ZONES) {
			for (InetAddress wrapped : embeddingForms("10.0.0.1")) {
				final InetAddress scoped = zoned(wrapped.getAddress(), zone);

				assertThat(scoped.toString(), DestinationPolicy.publicOnly().allows(scoped), is(false));
				assertThat(scoped.toString(), DestinationPolicy.allowLoopback().allows(scoped), is(false));
			}

			for (InetAddress wrapped : embeddingForms("127.0.0.1")) {
				final InetAddress scoped = zoned(wrapped.getAddress(), zone);

				assertThat(scoped.toString(), DestinationPolicy.publicOnly().allows(scoped), is(false));
				assertThat(scoped.toString(), DestinationPolicy.allowLoopback().allows(scoped), is(true));
			}

			for (InetAddress wrapped : embeddingForms("8.8.8.8")) {
				final InetAddress scoped = zoned(wrapped.getAddress(), zone);

				assertThat(scoped.toString(), DestinationPolicy.publicOnly().allows(scoped), is(true));
				assertThat(scoped.toString(), DestinationPolicy.allowLoopback().allows(scoped), is(true));
			}
		}
	}
}

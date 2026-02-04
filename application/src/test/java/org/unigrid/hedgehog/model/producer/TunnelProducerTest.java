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

package org.unigrid.hedgehog.model.producer;

import io.netty.handler.codec.socksx.v5.Socks5AddressType;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.sameInstance;
import org.unigrid.hedgehog.jqwik.BaseMockedWeldTest;
import org.unigrid.hedgehog.jqwik.WeldSetup;
import org.unigrid.hedgehog.proxy.session.SocksAddress;
import org.unigrid.hedgehog.proxy.socks.Tunnel;
import org.unigrid.hedgehog.proxy.socks.TunnelFailure;
import org.unigrid.hedgehog.proxy.socks.TunnelListener;

@WeldSetup(value = TunnelProducer.class, scan = false)
public class TunnelProducerTest extends BaseMockedWeldTest {
	@Inject
	private Tunnel tunnel;

	@Example
	public void shouldHandOutTheTunnelItselfToEveryInjectionPoint() {
		assertThat(tunnel, sameInstance(CDI.current().select(Tunnel.class).get()));

		/* A client proxy would be a class of its own, so this proves the bean is handed out unproxied */
		assertThat(tunnel.getClass(),equalTo(Tunnel.refusing(TunnelFailure.NOT_ALLOWED).getClass()));
	}

	@Example
	public void refusesEveryStreamAsNotAllowedByRuleset() {
		final List<String> heard = new ArrayList<>();

		tunnel.open(new SocksAddress(Socks5AddressType.DOMAIN, "example.org", 443),
			new TunnelListener() {
				@Override
				public void connected(SocksAddress bound) {
					heard.add("connected");
				}

				@Override
				public void failed(TunnelFailure failure) {
					heard.add(failure.name());
				}

				@Override
				public void received(byte[] bytes) {
					heard.add("received");
				}

				@Override
				public void writabilityChanged(boolean writable) {
					heard.add("writable");
				}

				@Override
				public void closed() {
					heard.add("closed");
				}
			});

		assertThat(heard, equalTo(List.of("NOT_ALLOWED")));
	}
}

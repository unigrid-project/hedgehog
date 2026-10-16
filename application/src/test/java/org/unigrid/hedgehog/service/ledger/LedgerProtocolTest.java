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

package org.unigrid.hedgehog.service.ledger;

import io.netty.channel.Channel;
import io.netty.channel.DefaultChannelId;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.incubator.codec.quic.QuicChannel;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLEngine;
import mockit.Mock;
import mockit.MockUp;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import org.unigrid.hedgehog.jqwik.BaseMockedWeldTest;
import org.unigrid.hedgehog.model.Network;

public class LedgerProtocolTest extends BaseMockedWeldTest {
	private static final String EARLIER_RELEASE = "hedgehog/0.0.4";

	/* What the handshake of the connection says it settled on: nothing yet, or a protocol */
	private final AtomicReference<String> settled = new AtomicReference<>();

	private SSLEngine engine() {
		return new MockUp<SSLEngine>() {
			@Mock public String getApplicationProtocol() {
				return settled.get();
			}
		}.getMockInstance();
	}

	/* A QUIC connection, as far as the gate asks of one: it keeps attributes and has an engine */
	private QuicChannel connection(final boolean withEngine) {
		final EmbeddedChannel kept = new EmbeddedChannel();
		final SSLEngine engine = engine();

		return (QuicChannel) Proxy.newProxyInstance(QuicChannel.class.getClassLoader(),
			new Class<?>[] { QuicChannel.class }, (proxy, method, arguments) -> {
				if ("sslEngine".equals(method.getName())) {
					return withEngine ? engine : null;
				}

				try {
					return method.invoke(kept, arguments);
				} catch (InvocationTargetException ex) {
					throw ex.getCause();
				}
			});
	}

	private static Channel streamOf(final Channel connection) {
		return new EmbeddedChannel(connection, DefaultChannelId.newInstance(), true, false);
	}

	@Example
	public void takesAConnectionThatSettledOnTheLedgerProtocol() {
		final QuicChannel connection = connection(true);

		settled.set(Network.LEDGER_PROTOCOL);
		assertThat(LedgerProtocol.negotiatedOn(streamOf(connection)), is(true));
		assertThat(connection.attr(LedgerProtocol.NEGOTIATED_KEY).get(), is(true));
	}

	@Example
	public void refusesAConnectionThatSettledOnTheProtocolOfAnEarlierRelease() {
		final QuicChannel connection = connection(true);

		settled.set(EARLIER_RELEASE);
		assertThat(LedgerProtocol.negotiatedOn(streamOf(connection)), is(false));
		assertThat(connection.attr(LedgerProtocol.NEGOTIATED_KEY).get(), is(false));
	}

	@Example
	public void readsWhatTheConnectionSettledOnOnce() {
		final QuicChannel connection = connection(true);
		final Channel stream = streamOf(connection);

		settled.set(Network.LEDGER_PROTOCOL);
		assertThat(LedgerProtocol.negotiatedOn(stream), is(true));

		settled.set(EARLIER_RELEASE);
		assertThat(LedgerProtocol.negotiatedOn(stream), is(true));
	}

	@Example
	public void doesNotRememberAConnectionThatHasNotSaidYetAsOneThatDidNotAgree() {
		final QuicChannel connection = connection(true);
		final Channel stream = streamOf(connection);

		settled.set("");
		assertThat(LedgerProtocol.negotiatedOn(stream), is(false));
		settled.set(null);
		assertThat(LedgerProtocol.negotiatedOn(stream), is(false));
		assertThat(connection.attr(LedgerProtocol.NEGOTIATED_KEY).get(), nullValue());

		settled.set(Network.LEDGER_PROTOCOL);
		assertThat(LedgerProtocol.negotiatedOn(stream), is(true));
	}

	@Example
	public void doesNotTakeAConnectionWithoutAnEngine() {
		final QuicChannel connection = connection(false);

		settled.set(Network.LEDGER_PROTOCOL);
		assertThat(LedgerProtocol.negotiatedOn(streamOf(connection)), is(false));
		assertThat(connection.attr(LedgerProtocol.NEGOTIATED_KEY).get(), nullValue());
	}

	@Example
	public void takesWhatIsKeptOnAConnectionThatIsNotAQuicOneAndNothingElse() {
		final EmbeddedChannel agreed = new EmbeddedChannel();
		final EmbeddedChannel unknown = new EmbeddedChannel();

		agreed.attr(LedgerProtocol.NEGOTIATED_KEY).set(true);

		assertThat(LedgerProtocol.negotiatedOn(streamOf(agreed)), is(true));
		assertThat(LedgerProtocol.negotiatedOn(streamOf(unknown)), is(false));
		assertThat(LedgerProtocol.negotiatedOn(new EmbeddedChannel()), is(false));
	}
}

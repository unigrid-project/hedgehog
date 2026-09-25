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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import mockit.Mock;
import mockit.MockUp;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.ShrinkingMode;
import org.hamcrest.Matcher;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.*;
import org.slf4j.LoggerFactory;
import org.unigrid.hedgehog.client.P2PClient;
import org.unigrid.hedgehog.model.Network;
import org.unigrid.hedgehog.server.BaseServerTest;
import org.unigrid.hedgehog.server.TestServer;

public class ProtocolMismatchHandlerTest extends BaseServerTest {
	private static final String[] RELEASE_0_0_7_PROTOCOLS = { "hedgehog/0.0.2", "gridspork/0.0.2" };

	@Property(tries = 1, shrinking = ShrinkingMode.OFF)
	public void shouldWarnAboutANodeSpeakingOtherProtocols(@ForAll("provideTestServers") List<TestServer> servers) {
		final TestServer server = servers.getFirst();
		final ListAppender<ILoggingEvent> appender = new ListAppender<>();

		appender.start();
		((Logger) LoggerFactory.getLogger(ProtocolMismatchHandler.class)).addAppender(appender);

		new MockUp<Network>() {
			@Mock public String[] getProtocols() {
				return RELEASE_0_0_7_PROTOCOLS;
			}
		};

		try {
			new P2PClient(server.getP2p().getHostName(), server.getP2p().getPort()).closeDirty();
		} catch (Exception ex) {
			/* The handshake fails, so the connection attempt is expected to give up */
		}

		await().until(() -> appender.list, containsInAnyOrder(
			warning(startsWith("Refused a connection from /127.0.0.1:")),
			warning(startsWith("localhost/127.0.0.1:" + server.getP2p().getPort() + " refused the connection"))
		));
	}

	private static Matcher<Object> warning(Matcher<String> message) {
		return both(hasProperty("level", is(Level.WARN))).and(hasProperty("formattedMessage",
			allOf(message, containsString(String.join(", ", Network.getProtocols()))))
		);
	}
}

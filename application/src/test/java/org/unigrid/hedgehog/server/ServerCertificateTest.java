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

package org.unigrid.hedgehog.server;

import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import lombok.SneakyThrows;
import mockit.Invocation;
import mockit.Mock;
import mockit.MockUp;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.BeforeContainer;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import org.unigrid.hedgehog.client.p2p.P2PClient;
import org.unigrid.hedgehog.model.option.NetOptions;
import org.unigrid.hedgehog.model.option.RestOptions;

/* A self-signed certificate is written into the temporary directory as a key file and a certificate file, and the file
   of the key stays there, readable, for as long as the node runs, and for good if the node is killed. The contexts of
   the servers hold the key and the certificate in memory once they are built, so a server has no use for the files
   after that and deletes them. The servers start with the container, so the certificates are watched from before it. */
public class ServerCertificateTest extends BaseDefaultNodeTest {
	/* The files of a certificate are named after it, so the names tell the certificates apart in a failure */
	private static final List<String> CREATED = new CopyOnWriteArrayList<>();
	private static final List<String> DELETED = new CopyOnWriteArrayList<>();

	private static String keyFileOf(final Object certificate) {
		return ((SelfSignedCertificate) certificate).privateKey().getName();
	}

	@BeforeContainer
	private static void watchTheCertificates() {
		new MockUp<SelfSignedCertificate>() {
			@Mock
			void $init(Invocation invocation) {
				invocation.proceed();
				CREATED.add(keyFileOf(invocation.getInvokedInstance()));
			}

			@Mock
			void delete(Invocation invocation) {
				DELETED.add(keyFileOf(invocation.getInvokedInstance()));
				invocation.proceed();
			}
		};
	}

	@Example
	public void deletesTheFilesOfEveryCertificateOnceTheContextsAreBuilt() {
		assertThat(CREATED, hasSize(2));
		assertThat(DELETED, containsInAnyOrder(CREATED.toArray()));
	}

	@Example
	@SneakyThrows
	public void stillAnswersTheHandshakeOfTheRestServerAfterTheDeletion() {
		final SSLContext context = SSLContext.getInstance("TLS");

		context.init(null, InsecureTrustManagerFactory.INSTANCE.getTrustManagers(), null);

		try (SSLSocket socket = (SSLSocket) context.getSocketFactory()
			.createSocket(RestOptions.getHost(), RestOptions.getPort())) {

			socket.startHandshake();
			assertThat(socket.getSession().isValid(), is(true));
		}
	}

	@Example
	@SneakyThrows
	public void stillAnswersTheHandshakeOfTheP2PServerAfterTheDeletion() {
		final P2PClient peer = new P2PClient(NetOptions.getHost(), NetOptions.getPort());

		try {
			assertThat(peer.getChannel().parent().isActive(), is(true));
		} finally {
			peer.closeDirty();
		}
	}
}

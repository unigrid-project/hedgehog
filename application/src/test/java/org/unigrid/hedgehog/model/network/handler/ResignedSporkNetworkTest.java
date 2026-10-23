/*
    Unigrid Hedgehog
    Copyright © 2021-2026 Stiftelsen The Unigrid Foundation

    Stiftelsen The Unigrid Foundation (org. nr: 802482-2408)

    This program is free software: you can redistribute it and/or modify it under the terms of the
    addended GNU Affero General Public License as published by the The Unigrid Foundation and
    the Free Software Foundation, version 3 of the License (see COPYING and COPYING.addendum).

    This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even
    the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU Affero General Public License and the addendum for more details.

    You should have received an addended copy of the GNU Affero General Public License with this program.
    If not, see <http://www.gnu.org/licenses/> and <https://github.com/unigrid-project/hedgehog>.
 */

package org.unigrid.hedgehog.model.network.handler;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.inject.Inject;
import java.io.InputStream;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import lombok.SneakyThrows;
import mockit.Delegate;
import mockit.Expectations;
import mockit.Mock;
import mockit.MockUp;
import mockit.Mocked;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeContainer;
import net.jqwik.api.lifecycle.BeforeTry;
import org.jboss.weld.environment.se.WeldContainer;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import org.unigrid.hedgehog.client.p2p.P2PClient;
import org.unigrid.hedgehog.command.option.NetOptions;
import org.unigrid.hedgehog.command.option.RestOptions;
import org.unigrid.hedgehog.common.model.ApplicationDirectory;
import org.unigrid.hedgehog.jqwik.BaseMockedWeldTest;
import org.unigrid.hedgehog.jqwik.Instances;
import org.unigrid.hedgehog.jqwik.NamedCDIProvider;
import org.unigrid.hedgehog.jqwik.WeldSetup;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.crypto.Signature;
import org.unigrid.hedgehog.model.network.schedule.PublishSporkSchedule;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;
import org.unigrid.hedgehog.model.spork.PendingSporks;
import org.unigrid.hedgehog.model.spork.SporkDatabase;
import org.unigrid.hedgehog.server.TestServer;

/* The step of the launch over the network. Three nodes of one process, each with a data directory of its own in
   memory: SIGNED starts with the database that two board keys signed again, FRESH with none, and EARLIER with the
   database of 0.0.7, signed once by a key that is no board key. SIGNED sends what it holds to the other two, as it
   does on its schedule, and both end up with the versions the board signed. */
@WeldSetup(TestServer.class)
public class ResignedSporkNetworkTest extends BaseMockedWeldTest {
	private static final int SIGNED = 0;
	private static final int FRESH = 1;
	private static final int EARLIER = 2;
	private static final List<Type> MINTS = List.of(Type.MINT_STORAGE, Type.MINT_SUPPLY);
	private static final Signature FIRST = keyPair();
	private static final Signature SECOND = keyPair();
	private static final FileSystem DATA = Jimfs.newFileSystem(Configuration.unix());

	@Mocked private NetOptions netOptions;
	@Mocked private RestOptions restOptions;

	@Inject @Instances(3) private List<TestServer> servers;

	private final Map<String, Integer> ports = new HashMap<>();
	private final List<P2PClient> clients = new CopyOnWriteArrayList<>();

	@SneakyThrows
	private static Signature keyPair() {
		return new Signature();
	}

	private static String container(int index) {
		return ResignedSporkNetworkTest.class.getSimpleName() + (index + 1);
	}

	private static Path directoryOf(String container) {
		return DATA.getPath("/" + container);
	}

	private static <T> T within(int index, Supplier<T> action) {
		final AtomicReference<T> result = new AtomicReference<>();

		NamedCDIProvider.within(container(index), () -> result.set(action.get()));
		return result.get();
	}

	private static <T> T bean(int index, Class<T> type) {
		return WeldContainer.instance(container(index)).select(type).get();
	}

	@SneakyThrows
	private static SporkDatabase release007() {
		try (InputStream in = ResignedSporkNetworkTest.class.getResourceAsStream("/spork/spork-0.0.7.db")) {
			final Path file = DATA.getPath("/release-0.0.7.db");

			Files.write(file, in.readAllBytes());
			return SporkDatabase.load(file);
		}
	}

	/* What two board members make of it with util spork-db sign, each of the mints a first version of its data */
	@SneakyThrows
	private static SporkDatabase signedAgain(SporkDatabase earlier) {
		final SporkDatabase signed = SporkDatabase.builder().build();

		for (Type type : MINTS) {
			final GridSpork first = GridSpork.firstVersionOf(earlier.get(type));

			first.sign(FIRST.getPrivateKey());
			first.cosign(SECOND.getPrivateKey());
			signed.set(first);
		}

		return signed;
	}

	/* The mock-ups stay for the rest of the run, and are made before the nodes, which read their databases as they
	   start: the board is the two keys of the run, and each node finds its data directory by the container it runs
	   in */
	@BeforeContainer
	@SneakyThrows
	private static void giveEachNodeItsDatabase() {
		new MockUp<NetworkKey>() {
			@Mock public String[] getPublicKeys() {
				return new String[] { FIRST.getPublicKey(), SECOND.getPublicKey() };
			}
		};

		new MockUp<ApplicationDirectory>() {
			@Mock public Path getUserDataDir() {
				return directoryOf(((WeldContainer) CDI.current()).getId());
			}

			@Mock public Path getUserConfigDir() {
				return getUserDataDir();
			}

			@Mock public Path getUserLogDir() {
				return getUserDataDir();
			}
		};

		for (int i = SIGNED; i <= EARLIER; i++) {
			Files.createDirectories(directoryOf(container(i)));
		}

		final SporkDatabase earlier = release007();

		SporkDatabase.persist(directoryOf(container(SIGNED)).resolve(SporkDatabase.SPORK_DB_FILE),
			signedAgain(earlier));
		SporkDatabase.persist(directoryOf(container(EARLIER)).resolve(SporkDatabase.SPORK_DB_FILE), earlier);
	}

	@BeforeTry
	private void startTheNodes() {
		for (int i = SIGNED; i <= EARLIER; i++) {
			final TestServer server = servers.get(i);

			within(i, () -> {
				TestServer.mockProperties(server);
				return null;
			});

			ports.put(container(i), server.getP2p().getPort());
		}

		new Expectations() {{
			NetOptions.getPort(); minTimes = 0;
			result = new Delegate<Integer>() {
				int port() {
					return ports.get(((WeldContainer) CDI.current()).getId());
				}
			};
		}};
	}

	@AfterTry
	private void shutDown() {
		clients.forEach(P2PClient::closeDirty);
		clients.clear();
	}

	@SneakyThrows
	private P2PClient signedNodeDials(int index) {
		final TestServer server = servers.get(index);

		return within(SIGNED, () -> {
			try {
				final P2PClient client = new P2PClient(server.getP2p().getHostName(), server.getP2p().getPort());

				clients.add(client);
				return client;
			} catch (Exception ex) {
				throw new IllegalStateException(ex);
			}
		});
	}

	private static GridSpork stored(int index, Type type) {
		return within(index, () -> bean(index, SporkDatabase.class).get(type));
	}

	private static boolean holdsTheSignedMints(int index) {
		return MINTS.stream().allMatch(type -> {
			final GridSpork signed = stored(SIGNED, type);
			final GridSpork held = stored(index, type);

			return Objects.nonNull(held) && held.isTakenByTheNetwork()
				&& Arrays.equals(held.getSignature(), signed.getSignature())
				&& Arrays.equals(held.getCosignature(), signed.getCosignature())
				&& Objects.equals(held.getData(), signed.getData());
		});
	}

	@Example
	public void spreadsTheMintsThatTheBoardSignedAgainToANewNodeAndToOneThatHeldThoseOfRelease007() {
		assertThat(holdsTheSignedMints(SIGNED), is(true));

		for (Type type : MINTS) {
			assertThat(type.name(), stored(FRESH, type), nullValue());
			assertThat(type.name(), stored(EARLIER, type), nullValue());
		}

		for (int index : List.of(FRESH, EARLIER)) {
			final P2PClient client = signedNodeDials(index);

			within(SIGNED, () -> {
				PublishSporkSchedule.writeAndFlush(client.getChannel(), bean(SIGNED, SporkDatabase.class),
					bean(SIGNED, PendingSporks.class));
				return null;
			});
		}

		await().until(() -> holdsTheSignedMints(FRESH) && holdsTheSignedMints(EARLIER));
	}
}

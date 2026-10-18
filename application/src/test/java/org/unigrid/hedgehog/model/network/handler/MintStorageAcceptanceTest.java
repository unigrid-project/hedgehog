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

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import io.netty.channel.ChannelFuture;
import java.math.BigDecimal;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import lombok.SneakyThrows;
import mockit.Mock;
import mockit.MockUp;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.ShrinkingMode;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import net.jqwik.api.lifecycle.AfterProperty;
import net.jqwik.api.lifecycle.BeforeProperty;
import net.jqwik.api.lifecycle.BeforeTry;
import org.apache.commons.lang3.SerializationUtils;
import org.awaitility.core.ConditionTimeoutException;
import org.slf4j.LoggerFactory;
import org.unigrid.hedgehog.client.p2p.P2PClient;
import org.unigrid.hedgehog.model.Address;
import org.unigrid.hedgehog.model.cdi.CDIUtil;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.crypto.Signature;
import org.unigrid.hedgehog.model.network.initializer.RegisterQuicChannelInitializer;
import org.unigrid.hedgehog.model.network.packet.PublishSpork;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.MintStorage;
import org.unigrid.hedgehog.model.spork.MintStorage.SporkData.Location;
import org.unigrid.hedgehog.model.spork.SporkDatabase;
import org.unigrid.hedgehog.server.TestServer;

/* Sends minting sporks to a running node over QUIC; only one signed by two different network keys may be stored */
public class MintStorageAcceptanceTest extends BaseHandlerTest<PublishSpork, PublishSporkChannelHandler> {
	private static final int MAX_MINTS = 8;
	private static final int MAX_ADDRESSES = 3;

	private static Signature proposer;
	private static Signature cosigner;
	private static long versions;

	private final Set<String> received = ConcurrentHashMap.newKeySet();

	/* A node closes the stream on any pipeline failure and the test logs nowhere, so this is where one shows up */
	private final ListAppender<ILoggingEvent> pipelineLog = new ListAppender<>();
	private final Logger pipelineLogger = (Logger) LoggerFactory.getLogger(AbstractInboundHandler.class);

	/* MintStorage compares equal to any other MintStorage, so a version is told apart by its time and signatures */
	private static String identity(GridSpork spork) {
		return Objects.isNull(spork) ? "none" : spork.getTimeStamp() + "/" + HexFormat.of().formatHex(spork.getSignature())
			+ "/" + HexFormat.of().formatHex(Objects.requireNonNullElse(spork.getCosignature(), new byte[0]));
	}

	/* The database the receiving node's handler resolves, which is not necessarily the one injected here */
	private static GridSpork storedMint() {
		final AtomicReference<GridSpork> stored = new AtomicReference<>();

		CDIUtil.resolveAndRun(SporkDatabase.class, db -> stored.set(db.get(GridSpork.Type.MINT_STORAGE)));
		return stored.get();
	}

	public MintStorageAcceptanceTest() {
		super(PublishSporkChannelHandler.class);
	}

	@SneakyThrows
	@BeforeProperty
	private void createKeys() {
		proposer = new Signature();
		cosigner = new Signature();

		pipelineLog.start();
		pipelineLogger.addAppender(pipelineLog);

		setChannelCallback(Optional.of((ctx, publishSpork) -> {
			if (RegisterQuicChannelInitializer.Type.SERVER.is(ctx.channel())) {
				received.add(identity(publishSpork.getGridSpork()));
			}
		}));
	}

	@AfterProperty
	private void detachPipelineLog() {
		pipelineLogger.detachAppender(pipelineLog);
		pipelineLog.stop();
	}

	@BeforeTry
	public void mockNetworkKeys() {
		new MockUp<NetworkKey>() {
			@Mock public /* static */ String[] getPublicKeys() {
				return new String[] { proposer.getPublicKey(), cosigner.getPublicKey() };
			}

			@Mock public /* static */ String[] getRetiredPublicKeys() {
				return new String[0];
			}
		};
	}

	@SneakyThrows
	private static MintStorage proposal(GridSpork stored, Map<Location, BigDecimal> mints) {
		final MintStorage spork = Objects.isNull(stored) ? new MintStorage() : (MintStorage) SerializationUtils.clone(stored);
		final MintStorage.SporkData data = new MintStorage.SporkData();

		data.setMints(mints);
		spork.archive();

		/* Versions made within one millisecond would otherwise not be newer than the version they replace */
		spork.setTimeStamp(spork.getTimeStamp().plusMillis(++versions));
		spork.setData(data);
		spork.sign(proposer.getPrivateKey());
		return spork;
	}

	private static MintStorage withCosignature(MintStorage proposal, byte[] cosignature) {
		final MintStorage spork = SerializationUtils.clone(proposal);

		spork.setCosignature(cosignature);
		return spork;
	}

	@SneakyThrows
	private static List<MintStorage> forgeries(MintStorage proposal, byte[] noise) {
		return List.of(proposal,
			withCosignature(proposal, proposal.getSignature()),
			withCosignature(proposal, new Signature().sign(proposal.getSignable())),
			withCosignature(proposal, noise)
		);
	}

	/* Returns once a node has handled this very spork, so what it did with it can be checked */
	@SneakyThrows
	private void send(TestServer server, GridSpork spork) {
		final P2PClient client = new P2PClient(server.getP2p().getHostName(), server.getP2p().getPort());
		final ChannelFuture write = client.send(PublishSpork.builder().gridSpork(spork).build());

		try {
			await().until(() -> received.contains(identity(spork)));
		} catch (ConditionTimeoutException ex) {
			throw new AssertionError("No node handled the spork. Write: " + write + ", cause: " + write.cause()
				+ ", stream active: " + client.getChannel().isActive() + ", pipeline log: "
				+ pipelineLog.list.stream().map(ILoggingEvent::getFormattedMessage).toList(), ex
			);
		} finally {
			client.closeDirty();
		}
	}

	/* Few addresses, so mints share Address objects, which once changed the signed bytes on the way */
	@Provide
	public Arbitrary<Map<Location, BigDecimal>> provideMints() {
		final Arbitrary<Location> locations = Arbitraries.strings().alpha().numeric().ofLength(34).list()
			.ofMinSize(1).ofMaxSize(MAX_ADDRESSES)
			.flatMap(wifs -> Arbitraries.of(wifs.stream().map(Address::new).toList()))
			.flatMap(address -> Arbitraries.integers().between(0, 5_000_000)
				.map(height -> new Location(address, height))
			);

		return Arbitraries.maps(locations, Arbitraries.longs().between(1, 1_000_000_000_000L)
			.map(amount -> BigDecimal.valueOf(amount, 8))).ofMinSize(1).ofMaxSize(MAX_MINTS);
	}

	@SneakyThrows
	@Property(tries = 15, shrinking = ShrinkingMode.OFF)
	public void shouldStoreAMintOnlyOnceTwoKeysSignedIt(@ForAll("provideTestServers") List<TestServer> servers,
		@ForAll("provideMints") Map<Location, BigDecimal> mints, @ForAll @Size(min = 1, max = 256) byte[] noise,
		@ForAll @IntRange(min = 0, max = 1000) int pick) {

		Assume.that(!servers.isEmpty());

		final TestServer server = servers.get(pick % servers.size());
		final GridSpork before = storedMint();
		final MintStorage proposal = proposal(before, mints);

		for (MintStorage forgery : forgeries(proposal, noise)) {
			send(server, forgery);
			assertThat(identity(storedMint()), equalTo(identity(before)));
		}

		final MintStorage cosigned = SerializationUtils.clone(proposal);

		cosigned.cosign(cosigner.getPrivateKey());
		send(server, cosigned);

		assertThat(identity(storedMint()), equalTo(identity(cosigned)));
		assertThat(((MintStorage.SporkData) storedMint().getData()).getMints(), equalTo(mints));
	}
}

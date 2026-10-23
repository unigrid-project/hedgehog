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

package org.unigrid.hedgehog.server.rest;

import io.netty.channel.embedded.EmbeddedChannel;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.BigRange;
import org.apache.commons.lang3.SerializationUtils;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;
import static org.hamcrest.Matchers.startsWith;
import org.unigrid.hedgehog.client.rest.ResponseOddityException;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import org.unigrid.hedgehog.model.Address;
import org.unigrid.hedgehog.model.crypto.Signature;
import org.unigrid.hedgehog.model.network.Node;
import org.unigrid.hedgehog.model.network.Topology;
import org.unigrid.hedgehog.model.network.codec.SporkWire;
import org.unigrid.hedgehog.model.spork.ChunkData;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;
import org.unigrid.hedgehog.model.spork.LedgerBlacklist;
import org.unigrid.hedgehog.model.spork.LedgerFee;
import org.unigrid.hedgehog.model.spork.MintStorage;
import org.unigrid.hedgehog.model.spork.MintStorage.SporkData.Location;
import org.unigrid.hedgehog.model.spork.MintSupply;
import org.unigrid.hedgehog.model.spork.PendingSporks;
import org.unigrid.hedgehog.model.spork.SporkDatabase;
import org.unigrid.hedgehog.model.spork.StorageSpork;
import org.unigrid.hedgehog.model.spork.VestingStorage;
import org.unigrid.hedgehog.model.spork.VestingStorage.SporkData.Vesting;
import static org.unigrid.hedgehog.service.ledger.LedgerStreams.connection;
import static org.unigrid.hedgehog.service.ledger.LedgerStreams.sporkTypesWrittenTo;
import static org.unigrid.hedgehog.service.ledger.LedgerStreams.stream;

/* The sporks as the command line reads them and hands them back signed: in the form peers exchange. A signed spork
   is taken as a node takes one from a peer. */
public class GridSporkWireResourceTest extends BaseRestClientTest {
	private static final AtomicInteger PEERS = new AtomicInteger();

	/* Where the data of a spork begins: after its type, its flags, its two time stamps and the reserved bytes */
	private static final int CONTENT = 32;

	@Inject private SporkDatabase sporkDatabase;
	@Inject private PendingSporks pendingSporks;
	@Inject private Topology topology;

	@SneakyThrows
	private static MintSupply proposal(Signature signature, BigDecimal maxSupply) {
		final MintSupply spork = new MintSupply();

		spork.<MintSupply.SporkData>getData().setMaxSupply(maxSupply);
		spork.archive();
		spork.sign(signature.getPrivateKey());
		return spork;
	}

	@SneakyThrows
	private Status statusOf(String location) {
		return Status.fromStatusCode(client.get(location).getStatus());
	}

	@SneakyThrows
	@Property(tries = 5)
	public void shouldHandOverTheStoredSporkInTheFormPeersExchange(@ForAll("provideSignature") Signature signature,
		@ForAll @BigRange(min = "0", max = "1000000") BigDecimal maxSupply) {

		final MintSupply stored = proposal(signature, maxSupply);

		stored.cosign(COSIGNER.getPrivateKey());
		sporkDatabase.setMintSupply(stored);

		final Response response = client.get("/gridspork/wire/" + Type.MINT_SUPPLY);
		final byte[] wire = response.readEntity(byte[].class);

		assertThat(response.getMediaType(), equalTo(MediaType.APPLICATION_OCTET_STREAM_TYPE));
		assertThat(wire, equalTo(SporkWire.encode(stored)));
		assertThat(SporkWire.decode(wire).isDoublySigned(), is(true));
	}

	@Example
	public void shouldHaveNoContentForATypeThatHoldsNoSpork() {
		sporkDatabase.setLedgerFee(null);
		assertThat(statusOf("/gridspork/wire/" + Type.LEDGER_FEE), equalTo(Status.NO_CONTENT));
	}

	@Example
	public void shouldFindNoTypeThatNoSporkHas() {
		for (String type : List.of(Type.UNDEFINED.name(), "mint-supply", "NONE")) {
			assertThat(type, statusOf("/gridspork/wire/" + type), equalTo(Status.NOT_FOUND));
		}
	}

	@SneakyThrows
	@Property(tries = 5)
	public void shouldHandOverAProposalByItsDigest(@ForAll("provideSignature") Signature signature,
		@ForAll @BigRange(min = "0", max = "1000000") BigDecimal maxSupply) {

		final MintSupply proposal = proposal(signature, maxSupply);

		sporkDatabase.setMintSupply(null);
		pendingSporks.remove(Type.MINT_SUPPLY);
		assertThat(pendingSporks.offer(proposal, null), is(true));

		final byte[] wire = client.get("/gridspork/pending/" + PendingSporks.digestOf(proposal))
			.readEntity(byte[].class);
		final GridSpork decoded = SporkWire.decode(wire);

		assertThat(wire, equalTo(SporkWire.encode(proposal)));
		assertThat(PendingSporks.digestOf(decoded), equalTo(PendingSporks.digestOf(proposal)));
		assertThat(decoded.isPending(), is(true));
	}

	@Example
	public void shouldFindNoProposalUnderADigestNoneHas() {
		assertThat(statusOf("/gridspork/pending/" + "00".repeat(64)), equalTo(Status.NOT_FOUND));
	}

	/* A peer on the ledger protocol, to which a node sends a spork of every type */
	@SneakyThrows
	private EmbeddedChannel peer() {
		final EmbeddedChannel stream = stream(true);
		final Node node = Node.builder().address(new InetSocketAddress("10.9.8." + PEERS.incrementAndGet(), 1000))
			.build();

		topology.addNode(node);
		topology.modifyNode(node, member -> member.setConnection(Optional.of(connection(stream))));
		return stream;
	}

	@SneakyThrows
	private Response submit(byte[] wire) {
		return client.post("/gridspork/wire", Entity.entity(wire, MediaType.APPLICATION_OCTET_STREAM));
	}

	private static Status statusOf(Response response) {
		return Status.fromStatusCode(response.getStatus());
	}

	/* The client takes a 400 for an oddity of the node */
	private ResponseOddityException refusalOf(byte[] wire) {
		return assertThrows(ResponseOddityException.class, () -> submit(wire));
	}

	@SneakyThrows
	private GridSpork cosignedAndStored(GridSpork proposal) {
		final GridSpork cosigned = SerializationUtils.clone(proposal);

		assertThat(statusOf(submit(proposal)), equalTo(Status.ACCEPTED));
		cosigned.cosign(COSIGNER.getPrivateKey());
		assertThat(statusOf(submit(cosigned)), equalTo(Status.OK));
		return cosigned;
	}

	@SneakyThrows
	@Property(tries = 3)
	public void shouldHoldAProposalAndStoreItOnceCosignedAsANodeTakesASporkFromAPeer(
		@ForAll("provideSignature") Signature signature,
		@ForAll @BigRange(min = "0", max = "1000000") BigDecimal maxSupply) {

		final EmbeddedChannel peer = peer();
		final GridSpork stored = sporkDatabase.getMintSupply();
		final GridSpork proposal = next(Type.MINT_SUPPLY, (MintSupply.SporkData data) -> data.setMaxSupply(maxSupply),
			signature);
		final Response held = submit(proposal);

		assertThat(statusOf(held), equalTo(Status.ACCEPTED));
		assertThat(digestIn(held), equalTo(PendingSporks.digestOf(proposal)));
		assertThat(sporkDatabase.getMintSupply(), sameInstance(stored));
		assertThat(sporkTypesWrittenTo(peer), contains(Type.MINT_SUPPLY));

		final GridSpork fetched = SporkWire.decode(client.get("/gridspork/pending/" + PendingSporks.digestOf(proposal))
			.readEntity(byte[].class));

		fetched.cosign(COSIGNER.getPrivateKey());

		assertThat(statusOf(submit(fetched)), equalTo(Status.OK));
		assertThat(sporkDatabase.getMintSupply().isDoublySigned(), is(true));
		assertThat(sporkDatabase.getMintSupply().getSignable(), equalTo(proposal.getSignable()));
		assertThat(sporkDatabase.getMintSupply().<MintSupply.SporkData>getData().getMaxSupply(), equalTo(maxSupply));
		assertThat(pendingSporks.proposalOf(Type.MINT_SUPPLY), equalTo(Optional.empty()));
		assertThat(sporkTypesWrittenTo(peer), contains(Type.MINT_SUPPLY));
	}

	@SneakyThrows
	@Property(tries = 1)
	public void shouldRefuseASporkThatNoSecondCurrentBoardKeySigned(@ForAll("provideSignature") Signature signature) {
		final EmbeddedChannel peer = peer();
		final GridSpork stored = sporkDatabase.getLedgerFee();
		final Consumer<LedgerFee.SporkData> change = data -> data.setMinimumTransferFee(BigDecimal.ONE);
		final GridSpork proposal = next(Type.LEDGER_FEE, change, signature);
		final GridSpork strangerCosigned = SerializationUtils.clone(proposal);
		final GridSpork selfCosigned = SerializationUtils.clone(proposal);

		pendingSporks.remove(Type.LEDGER_FEE);
		strangerCosigned.setCosignature(STRANGER.sign(proposal.getSignable()));
		selfCosigned.setCosignature(proposal.getSignature());

		for (GridSpork forged : List.of(next(Type.LEDGER_FEE, change, STRANGER), strangerCosigned, selfCosigned)) {
			assertThat(statusOf(submit(forged)), equalTo(Status.UNAUTHORIZED));
		}

		assertThat(pendingSporks.proposalOf(Type.LEDGER_FEE), equalTo(Optional.empty()));
		assertThat(sporkDatabase.getLedgerFee(), sameInstance(stored));
		assertThat(sporkTypesWrittenTo(peer), empty());
	}

	/* The fee and the blacklist cannot hold what their spork refuses in their wire form: a value past the cap of
	   the wire stands for it */
	@SneakyThrows
	@Property(tries = 1)
	public void shouldRefuseContentThatNoNodeCanActOnBeforeHoldingOrSendingIt(
		@ForAll("provideSignature") Signature signature) {

		final EmbeddedChannel peer = peer();
		final Vesting unbounded = Vesting.builder().start(Instant.EPOCH).duration(Duration.ofDays(30)).parts(1).build();
		final byte[] fee = SporkWire.encode(next(Type.LEDGER_FEE, data -> { }, signature));
		final byte[] blacklist = SporkWire.encode(next(Type.LEDGER_BLACKLIST, data -> { }, signature));
		final Map<Type, Invalid> invalid = Map.of(
			Type.MINT_STORAGE, new Invalid(SporkWire.encode(next(Type.MINT_STORAGE, (MintStorage.SporkData data) ->
				data.getMints().put(new Location(new Address("garbage"), 1), BigDecimal.ONE), signature)), "neither"),
			Type.MINT_SUPPLY, new Invalid(SporkWire.encode(next(Type.MINT_SUPPLY, (MintSupply.SporkData data) ->
				data.setMaxSupply(BigDecimal.ONE.negate()), signature)), "maximum supply"),
			Type.VESTING_STORAGE, new Invalid(SporkWire.encode(next(Type.VESTING_STORAGE,
				(VestingStorage.SporkData data) -> data.getVestingAddresses().put(new Address("garbage"), unbounded),
				signature)), "garbage has an amount"),
			Type.STORAGE, new Invalid(SporkWire.encode(next(Type.STORAGE, (StorageSpork.SporkData data) ->
				data.setManifestCopies(0), signature)), "manifestCopies"),
			Type.LEDGER_FEE, new Invalid(ByteBuffer.wrap(fee).putLong(CONTENT, LedgerFee.SporkData.MAX_UNITS + 1)
				.array(), "exceeds the cap"),
			Type.LEDGER_BLACKLIST, new Invalid(ByteBuffer.wrap(blacklist).putShort(CONTENT,
				(short) (LedgerBlacklist.SporkData.MAX_ENTRIES + 1)).array(), "exceeds the cap")
		);

		invalid.forEach((type, spork) -> {
			final GridSpork stored = sporkDatabase.get(type);
			final ResponseOddityException refusal = refusalOf(spork.wire());

			pendingSporks.remove(type);
			assertThat(type.name(), refusal.getMessage(), startsWith("400 "));
			assertThat(type.name(), refusal.getBody(), containsString(spork.reason()));
			assertThat(type.name(), pendingSporks.proposalOf(type), equalTo(Optional.empty()));
			assertThat(type.name(), sporkDatabase.get(type), sameInstance(stored));
		});

		assertThat(refusalOf(new byte[] { 1, 2, 3 }).getMessage(), startsWith("400 "));
		assertThat(sporkTypesWrittenTo(peer), empty());
	}

	private record Invalid(byte[] wire, String reason) { }

	/* A proposal is answered with JSON, and a refusal says why in plain text, which is what it is */
	@SneakyThrows
	@Property(tries = 1)
	public void shouldSayWhyASporkIsRefusedInPlainText(@ForAll("provideSignature") Signature signature) {
		final GridSpork proposal = next(Type.LEDGER_FEE, (LedgerFee.SporkData data) -> data.setMinimumTransferFee(
			new BigDecimal("0.5")), signature);

		pendingSporks.remove(Type.LEDGER_FEE);

		final Response held = submit(proposal);
		final Response again = submit(proposal);
		final Response unsigned = submit(next(Type.LEDGER_FEE, data -> { }, STRANGER));
		final Response undecodable = new GridSporkWireResource().submit(new byte[] { 1, 2, 3 });

		assertThat(held.getMediaType().isCompatible(MediaType.APPLICATION_JSON_TYPE), is(true));

		for (Response refusal : List.of(again, unsigned, undecodable)) {
			assertThat(String.valueOf(refusal.getMediaType()), startsWith(MediaType.TEXT_PLAIN));
		}

		assertThat(statusOf(again), equalTo(Status.CONFLICT));
		assertThat(statusOf(unsigned), equalTo(Status.UNAUTHORIZED));
		assertThat(statusOf(undecodable), equalTo(Status.BAD_REQUEST));
	}

	/* A peer keeps a proposal held over the version it stores as long as it may still be co-signed over it, so the
	   node does the same with a version it is handed */
	@SneakyThrows
	@Property(tries = 1)
	public void shouldKeepAProposalThatGoesOnFromTheSporkItStores(@ForAll("provideSignature") Signature signature) {
		pendingSporks.remove(Type.LEDGER_FEE);

		final GridSpork stored = sporkDatabase.getLedgerFee();
		final GridSpork version = next(Type.LEDGER_FEE, (LedgerFee.SporkData data) -> data.setMinimumTransferFee(
			new BigDecimal("0.25")), signature);

		version.cosign(COSIGNER.getPrivateKey());

		final GridSpork over = GridSpork.nextVersion(Type.LEDGER_FEE, version, Optional.empty());

		over.<LedgerFee.SporkData>getData().setMinimumTransferFee(new BigDecimal("0.125"));
		over.sign(signature.getPrivateKey());

		assertThat(pendingSporks.offer(over, stored), is(true));
		assertThat(statusOf(submit(version)), equalTo(Status.OK));
		assertThat(pendingSporks.proposalOf(Type.LEDGER_FEE).map(PendingSporks::digestOf),
			equalTo(Optional.of(PendingSporks.digestOf(over))));
	}

	@SneakyThrows
	@Property(tries = 1)
	public void shouldRefuseAnOlderSporkReplayedOverTheStoredOne(@ForAll("provideSignature") Signature signature) {
		pendingSporks.remove(Type.STORAGE);

		final GridSpork olderProposal = next(Type.STORAGE, (StorageSpork.SporkData data) -> data.setManifestCopies(3),
			signature);
		final GridSpork older = cosignedAndStored(olderProposal);
		final GridSpork newer = cosignedAndStored(next(Type.STORAGE, (StorageSpork.SporkData data) ->
			data.setManifestCopies(4), signature));

		assertThat(statusOf(submit(older)), equalTo(Status.CONFLICT));
		assertThat(statusOf(submit(olderProposal)), equalTo(Status.CONFLICT));
		assertThat(statusOf(submit(newer)), equalTo(Status.CONFLICT));
		assertThat(sporkDatabase.getStorageSpork().getSignable(), equalTo(newer.getSignable()));
		assertThat(sporkDatabase.getStorageSpork().getCosignature(), equalTo(newer.getCosignature()));
	}
}

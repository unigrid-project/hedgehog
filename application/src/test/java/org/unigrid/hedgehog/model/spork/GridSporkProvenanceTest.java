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

package org.unigrid.hedgehog.model.spork;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.SneakyThrows;
import mockit.Mock;
import mockit.MockUp;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.BeforeTry;
import net.jqwik.api.lifecycle.PropagationMode;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.SerializationUtils;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import org.unigrid.hedgehog.jqwik.MockitHook;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.crypto.Signature;

/* What a board member's command line takes from a node before it signs anything on top of it */
@AddLifecycleHook(value = MockitHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class GridSporkProvenanceTest {
	private static final Signature FIRST = keyPair();
	private static final Signature SECOND = keyPair();
	private static final Signature STRANGER = keyPair();

	@SneakyThrows
	private static Signature keyPair() {
		return new Signature();
	}

	private static void board(List<Signature> current, List<Signature> retired) {
		new MockUp<NetworkKey>() {
			@Mock public String[] getPublicKeys() {
				return current.stream().map(Signature::getPublicKey).toArray(String[]::new);
			}

			@Mock public String[] getRetiredPublicKeys() {
				return retired.stream().map(Signature::getPublicKey).toArray(String[]::new);
			}
		};
	}

	@BeforeTry
	public void fakeTheBoard() {
		board(List.of(FIRST, SECOND), List.of());
	}

	private static MintSupply.SporkData supplyOf(long maxSupply) {
		final MintSupply.SporkData data = new MintSupply.SporkData();

		data.setMaxSupply(BigDecimal.valueOf(maxSupply));
		return data;
	}

	@SneakyThrows
	private static GridSpork proposal(GridSpork stored, long maxSupply, Signature signer) {
		final GridSpork spork = GridSpork.nextVersion(GridSpork.Type.MINT_SUPPLY, stored, Optional.empty());

		spork.setData(supplyOf(maxSupply));
		spork.sign(signer.getPrivateKey());
		return spork;
	}

	@SneakyThrows
	private static GridSpork stored(GridSpork over, long maxSupply) {
		final GridSpork spork = proposal(over, maxSupply, FIRST);

		spork.cosign(SECOND.getPrivateKey());
		return spork;
	}

	@SneakyThrows
	private static byte[] signatureOf(Signature signer, GridSpork spork) {
		return signer.sign(spork.getSignable());
	}

	@Example
	public void shouldTakeASporkThatTwoBoardKeysSigned() {
		final GridSpork first = stored(null, 10);

		assertThat(first.isTakenByTheNetwork(), is(true));
		assertThat(stored(first, 20).isTakenByTheNetwork(), is(true));
	}

	@Example
	public void shouldTakeASporkOfABoardThatHasSinceBeenReplaced() {
		final GridSpork spork = stored(null, 10);

		board(List.of(STRANGER), List.of(FIRST, SECOND));
		assertThat(spork.isTakenByTheNetwork(), is(true));
	}

	/* What an earlier build stored was signed once, over its Java serialization */
	@Example
	public void shouldTakeASporkThatAnEarlierBuildSignedOnce() {
		final GridSpork spork = GridSpork.create(GridSpork.Type.MINT_SUPPLY);

		spork.setData(supplyOf(10));
		spork.setTimeStamp(Instant.parse("2025-01-01T00:00:00Z"));
		spork.setSignature(signatureOf(FIRST, spork));

		assertThat(spork.isTakenByTheNetwork(), is(true));
	}

	@Example
	public void shouldRefuseASporkThatNoSecondBoardKeySigned() {
		final GridSpork once = proposal(null, 10, FIRST);
		final GridSpork strangerCosigned = SerializationUtils.clone(once);
		final GridSpork selfCosigned = SerializationUtils.clone(once);
		final GridSpork strangerSigned = proposal(null, 10, STRANGER);

		strangerCosigned.setCosignature(signatureOf(STRANGER, once));
		selfCosigned.setCosignature(once.getSignature());
		strangerSigned.setCosignature(signatureOf(FIRST, strangerSigned));

		for (GridSpork forged : List.of(once, strangerCosigned, selfCosigned, strangerSigned)) {
			assertThat(forged.isTakenByTheNetwork(), is(false));
		}
	}

	@Example
	public void shouldRefuseASporkWhoseDataChangedAfterItWasSigned() {
		final GridSpork spork = stored(null, 10);

		spork.setData(supplyOf(11));
		assertThat(spork.isTakenByTheNetwork(), is(false));
	}

	@SneakyThrows
	@Example
	public void shouldRefuseASporkWithAVersionInItsLogThatNoBoardKeySigned() {
		final byte[] signable = "a version no board made".getBytes(StandardCharsets.UTF_8);
		final GridSpork spork = GridSpork.nextVersion(GridSpork.Type.MINT_SUPPLY, null, Optional.empty());

		spork.setSignatureLog(new SignatureLog(List.of(SignatureLogEntry.builder()
			.timeStamp(Instant.parse("2025-01-01T00:00:00Z")).signer(STRANGER.getPublicKey())
			.digest(DigestUtils.sha512(signable)).signature(STRANGER.sign(signable)).build())));
		spork.sign(FIRST.getPrivateKey());
		spork.cosign(SECOND.getPrivateKey());

		assertThat(spork.isTakenByTheNetwork(), is(false));
	}

	@Example
	public void shouldTakeAProposalMadeFromTheStoredSporkAsItsNextVersionOrItsRenewal() {
		final GridSpork stored = stored(null, 10);

		assertThat(proposal(stored, 20, FIRST).isProposalOver(stored), is(true));
		assertThat(proposal(null, 20, FIRST).isProposalOver(null), is(true));
		assertThat(renewalOf(stored).isProposalOver(stored), is(true));
	}

	@SneakyThrows
	private static GridSpork renewalOf(GridSpork stored) {
		final GridSpork renewal = SerializationUtils.clone(stored);

		renewal.renew();
		renewal.sign(SECOND.getPrivateKey());
		return renewal;
	}

	@Example
	public void shouldRefuseAProposalThatNoBoardKeyMadeOrThatChangedSince() {
		final GridSpork stored = stored(null, 10);
		final GridSpork tampered = proposal(stored, 20, FIRST);

		tampered.setData(supplyOf(30));
		assertThat(proposal(stored, 20, STRANGER).isProposalOver(stored), is(false));
		assertThat(tampered.isProposalOver(stored), is(false));
	}

	/* Made from a sibling of the stored spork, a proposal goes on with another head than the stored one, which no node
	   takes either. Made over nothing, it is a sibling of the stored one, which a node takes for a while after it as
	   two co-signers can race, so only what it was made from tells that it undoes what the stored one holds. */
	@Example
	public void shouldRefuseAProposalMadeFromAnotherVersionThanTheStoredOne() {
		final GridSpork stored = stored(null, 10);
		final GridSpork sibling = stored(null, 99);
		final GridSpork overSibling = proposal(sibling, 20, FIRST);
		final GridSpork overNothing = proposal(null, 20, FIRST);

		assertThat(overSibling.canBeProposedOver(stored), is(false));
		assertThat(overSibling.isProposalOver(stored), is(false));
		assertThat(overNothing.isProposalOver(stored), is(false));
		assertThat(proposal(stored, 20, FIRST).isProposalOver(null), is(false));
	}
}

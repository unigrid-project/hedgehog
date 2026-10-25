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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.SneakyThrows;
import mockit.Mock;
import mockit.MockUp;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.BeforeProperty;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.SerializationUtils;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import org.unigrid.hedgehog.jqwik.BaseMockedWeldTest;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.crypto.Signature;
import org.unigrid.hedgehog.model.crypto.SigningException;

public class GridSporkSignatureLogTest extends BaseMockedWeldTest {
	private static final Instant SIGNED_AT = Instant.parse("2023-06-23T10:38:54.983Z");

	private static Signature trusted;
	private static Signature cosigner;
	/* A key of an earlier board, which no longer counts */
	private static Signature earlier;
	private static String[] currentKeys;

	@SneakyThrows
	@BeforeProperty
	public void before() {
		trusted = new Signature();
		cosigner = new Signature();
		earlier = new Signature();
		currentKeys = new String[] { trusted.getPublicKey(), cosigner.getPublicKey() };

		new MockUp<NetworkKey>() {
			@Mock public static String[] getPublicKeys() {
				return currentKeys;
			}
		};
	}

	@SneakyThrows
	private MintSupply signedSupply(Signature signer) {
		final MintSupply spork = new MintSupply();
		final MintSupply.SporkData data = new MintSupply.SporkData();

		data.setMaxSupply(BigDecimal.TEN);
		spork.setData(data);
		spork.setPreviousData(data.empty());
		spork.setTimeStamp(SIGNED_AT);
		spork.setPreviousTimeStamp(Instant.EPOCH);
		spork.sign(signer.getPrivateKey());
		return spork;
	}

	@SneakyThrows
	private static MintSupply cosigned(MintSupply spork) {
		spork.cosign(cosigner.getPrivateKey());
		return spork;
	}

	@SneakyThrows
	private MintSupply renewedTwice(Signature firstSigner) {
		final MintSupply spork = signedSupply(firstSigner);

		spork.renew();
		spork.sign(trusted.getPrivateKey());
		spork.archive();
		spork.sign(trusted.getPrivateKey());
		return spork;
	}

	@SneakyThrows
	@Example
	public void shouldLogTheReplacedVersionWhenSignedAgain() {
		final MintSupply spork = signedSupply(trusted);
		final byte[] replacedSignature = spork.getSignature();

		spork.archive();
		assertThat(spork.getSignatureLog().isEmpty(), is(true));

		spork.sign(trusted.getPrivateKey());

		final SignatureLogEntry entry = spork.getSignatureLog().getEntries().getFirst();

		assertThat(spork.getSignatureLog().size(), is(1));
		assertThat(entry.getTimeStamp(), equalTo(SIGNED_AT));
		assertThat(entry.getSigner(), equalTo(trusted.getPublicKey()));
		assertThat(entry.getSignature(), equalTo(replacedSignature));
		assertThat(entry.isValid(), is(true));
		assertThat(spork.isValidSignature(), is(true));
	}

	@SneakyThrows
	@Example
	public void shouldRefuseToSignOverAnUnknownSigner() {
		for (Signature unknown : List.of(new Signature(), earlier)) {
			final MintSupply spork = signedSupply(unknown);

			spork.renew();

			try {
				spork.sign(trusted.getPrivateKey());
				assertThat("Signing should have been refused", false);
			} catch (SigningException ex) {
				assertThat(spork.getSignatureLog().isEmpty(), is(true));
			}
		}
	}

	/* A key the board no longer holds could sign any history, so only the current keys vouch for a log */
	@SneakyThrows
	@Example
	public void shouldNotTakeASporkWhoseLogAKeyOfAnEarlierBoardSigned() {
		final MintSupply version = signedSupply(earlier);
		final SignatureLogEntry entry = SignatureLogEntry.builder().timeStamp(SIGNED_AT)
			.signer(earlier.getPublicKey()).digest(DigestUtils.sha512(version.getSignable()))
			.signature(version.getSignature()).build();
		final MintSupply spork = signedSupply(trusted);

		spork.setTimeStamp(SIGNED_AT.plusSeconds(1));

		final MintSupply resigned = resigned(spork, new SignatureLog(List.of(entry)));

		assertThat(resigned.isTakenByTheNetwork(), is(false));
		assertThat(resigned.canReplace(null), is(false));
	}

	@SneakyThrows
	@Example
	public void shouldLogOnlyOnceWhenRenewedTwiceBeforeSigning() {
		final MintSupply spork = signedSupply(trusted);

		spork.renew();
		spork.renew();
		spork.sign(trusted.getPrivateKey());

		assertThat(spork.getSignatureLog().size(), is(1));
		assertThat(spork.getSignatureLog().getEntries().getFirst().isValid(), is(true));
	}

	@Example
	public void shouldNotLogAnUnsignedSpork() {
		final MintSupply spork = new MintSupply();

		spork.setTimeStamp(SIGNED_AT);
		spork.renew();

		assertThat(spork.getSignatureLog().isEmpty(), is(true));
	}

	@Example
	public void shouldBreakTheSignatureWhenAnEntryIsDropped() {
		final MintSupply spork = renewedTwice(trusted);

		spork.setSignatureLog(new SignatureLog(spork.getSignatureLog().getEntries().subList(1, 2)));
		assertThat(spork.isValidSignature(), is(false));
	}

	@Example
	public void shouldBreakTheSignatureWhenEntriesAreReordered() {
		final MintSupply spork = renewedTwice(trusted);
		final List<SignatureLogEntry> entries = new ArrayList<>(spork.getSignatureLog().getEntries());

		spork.setSignatureLog(new SignatureLog(entries.reversed()));
		assertThat(spork.isValidSignature(), is(false));
	}

	@SneakyThrows
	@Example
	public void shouldBreakTheSignatureWhenASignerIsRewritten() {
		final MintSupply spork = renewedTwice(trusted);
		final List<SignatureLogEntry> entries = new ArrayList<>(spork.getSignatureLog().getEntries());

		entries.set(0, entries.getFirst().toBuilder().signer(cosigner.getPublicKey()).build());
		spork.setSignatureLog(new SignatureLog(entries));
		assertThat(spork.isValidSignature(), is(false));
	}

	@SneakyThrows
	private MintSupply next(MintSupply base, int second) {
		final MintSupply spork = SerializationUtils.clone(base);

		spork.archive();
		spork.setTimeStamp(SIGNED_AT.plusSeconds(second));
		spork.sign(trusted.getPrivateKey());
		return cosigned(spork);
	}

	@SneakyThrows
	private MintSupply resigned(MintSupply spork, SignatureLog log) {
		final MintSupply forged = SerializationUtils.clone(spork);

		forged.setSignatureLog(log);
		forged.sign(trusted.getPrivateKey());
		return cosigned(forged);
	}

	@Example
	public void shouldReplaceNothingWithATrustedFirstVersion() {
		assertThat(cosigned(signedSupply(trusted)).canReplace(null), is(true));
	}

	@SneakyThrows
	@Example
	public void shouldNotAcceptAHeadSignedByAKeyOfAnEarlierBoard() {
		final MintSupply spork = signedSupply(earlier);

		spork.setCosignature(cosigner.sign(spork.getSignable()));
		assertThat(spork.canReplace(null), is(false));
	}

	@Example
	public void shouldReplaceTheVersionItExtends() {
		final MintSupply stored = signedSupply(trusted);

		assertThat(next(stored, 1).canReplace(stored), is(true));
	}

	@Example
	public void shouldNotReplaceWithATruncatedLog() {
		final MintSupply first = next(signedSupply(trusted), 1);
		final MintSupply stored = next(first, 2);
		final MintSupply truncated = resigned(stored, first.getSignatureLog());

		assertThat(truncated.canReplace(stored), is(false));
	}

	@Example
	public void shouldNotReplaceWithRewrittenHistory() {
		final MintSupply stored = next(signedSupply(trusted), 1);
		final MintSupply extended = next(stored, 2);
		final List<SignatureLogEntry> entries = new ArrayList<>(extended.getSignatureLog().getEntries());

		entries.set(0, entries.getFirst().toBuilder().timeStamp(SIGNED_AT.minusSeconds(1)).build());
		assertThat(resigned(extended, new SignatureLog(entries)).canReplace(stored), is(false));
	}

	@SneakyThrows
	@Example
	public void shouldNotAcceptEntriesSignedByUnknownKeys() {
		final Signature foreignKey = new Signature();
		final MintSupply foreign = signedSupply(foreignKey);
		final SignatureLogEntry entry = SignatureLogEntry.builder().timeStamp(SIGNED_AT)
			.signer(foreignKey.getPublicKey()).digest(DigestUtils.sha512(foreign.getSignable()))
			.signature(foreign.getSignature()).build();
		final MintSupply spork = signedSupply(trusted);

		spork.setTimeStamp(SIGNED_AT.plusSeconds(1));
		assertThat(resigned(spork, new SignatureLog(List.of(entry))).canReplace(null), is(false));
	}

	@Example
	public void shouldNotAcceptAHeadOlderThanItsLog() {
		final MintSupply spork = next(signedSupply(trusted), 1);

		spork.setTimeStamp(SIGNED_AT);
		assertThat(resigned(spork, spork.getSignatureLog()).canReplace(null), is(false));
	}

	@Example
	public void shouldResolveSiblingsByTimeStamp() {
		final MintSupply base = signedSupply(trusted);
		final MintSupply older = next(base, 1);
		final MintSupply newer = next(base, 2);

		assertThat(newer.canReplace(older), is(true));
		assertThat(older.canReplace(newer), is(false));
	}

	/* A longer log wins only when it goes on with the stored version, as a branch over a sibling of it could carry a
	   version that no board made in its place */
	@Example
	public void shouldLetOnlyTheLongerBranchOverTheStoredVersionWin() {
		final MintSupply base = signedSupply(trusted);
		final MintSupply first = next(base, 1);
		final MintSupply sibling = next(base, 3);
		final MintSupply branch = next(first, 2);

		assertThat(branch.canReplace(first), is(true));
		assertThat(branch.canReplace(sibling), is(false));
		assertThat(sibling.canReplace(branch), is(false));
	}

	@Example
	public void shouldNotTakeALogThatGoesOnWithAnotherHeadThanTheStoredOne() {
		final MintSupply stored = next(signedSupply(trusted), 1);
		final MintSupply extended = next(stored, 2);
		final List<SignatureLogEntry> entries = new ArrayList<>(extended.getSignatureLog().getEntries());
		final SignatureLogEntry head = entries.getLast();

		entries.set(entries.size() - 1, head.toBuilder().cosigner(null).cosignature(null).build());
		assertThat(resigned(extended, new SignatureLog(entries)).canReplace(stored), is(false));
		assertThat(resigned(extended, extended.getSignatureLog()).canReplace(stored), is(true));
	}

	@SneakyThrows
	@Example
	public void shouldNotAcceptASingleSignature() {
		final MintSupply stored = cosigned(signedSupply(trusted));
		final MintSupply proposed = SerializationUtils.clone(stored);

		proposed.renew();
		proposed.sign(trusted.getPrivateKey());

		assertThat(signedSupply(trusted).canReplace(null), is(false));
		assertThat(proposed.canReplace(stored), is(false));
	}

	@Example
	public void shouldBePendingUntilCosigned() {
		final MintSupply spork = signedSupply(trusted);

		assertThat(spork.isPending(), is(true));
		assertThat(cosigned(spork).isPending(), is(false));
	}

	@Example
	public void shouldKeepTheSignableWhenCosigned() {
		final MintSupply spork = signedSupply(trusted);
		final byte[] signable = spork.getSignable();

		assertThat(cosigned(spork).getSignable(), equalTo(signable));
	}

	@SneakyThrows
	@Example
	public void shouldRefuseToCosignWithTheFirstSignersKey() {
		final MintSupply spork = signedSupply(trusted);

		try {
			spork.cosign(trusted.getPrivateKey());
			assertThat("Co-signing should have been refused", false);
		} catch (SigningException ex) {
			assertThat(spork.isPending(), is(true));
		}
	}

	@Example
	public void shouldRefuseToCosignAnUnsignedSpork() {
		try {
			new MintSupply().cosign(cosigner.getPrivateKey());
			assertThat("Co-signing should have been refused", false);
		} catch (SigningException ex) {
			assertThat(ex.getMessage(), not(emptyString()));
		}
	}

	@SneakyThrows
	@Example
	public void shouldNotAcceptTheSameKeyTwice() {
		final MintSupply spork = signedSupply(trusted);

		spork.setCosignature(trusted.sign(spork.getSignable()));
		assertThat(spork.canReplace(null), is(false));
	}

	@SneakyThrows
	@Example
	public void shouldNotAcceptACosignerOfAnEarlierBoard() {
		final MintSupply spork = signedSupply(trusted);

		spork.setCosignature(earlier.sign(spork.getSignable()));
		assertThat(spork.canReplace(null), is(false));
	}

	@SneakyThrows
	@Example
	public void shouldLogBothSignersOfARenewedVersion() {
		final MintSupply stored = cosigned(signedSupply(trusted));
		final MintSupply renewed = SerializationUtils.clone(stored);

		renewed.renew();
		renewed.sign(cosigner.getPrivateKey());

		final SignatureLogEntry entry = renewed.getSignatureLog().last();

		assertThat(renewed.isPending(), is(true));
		assertThat(entry.getSigners(), contains(trusted.getPublicKey(), cosigner.getPublicKey()));
		assertThat(entry.isValid(), is(true));
	}

	@SneakyThrows
	@Example
	public void shouldAcceptARenewalOfASingleSignedVersion() {
		final MintSupply stored = signedSupply(trusted);
		final MintSupply renewed = SerializationUtils.clone(stored);

		renewed.renew();
		renewed.sign(trusted.getPrivateKey());
		renewed.cosign(cosigner.getPrivateKey());

		assertThat(renewed.getSignatureLog().last().isCosigned(), is(false));
		assertThat(renewed.canReplace(stored), is(true));
	}

	@SneakyThrows
	@Example
	public void shouldAcceptAPendingSuccessorAsAProposal() {
		final MintSupply stored = cosigned(signedSupply(trusted));
		final MintSupply proposed = SerializationUtils.clone(stored);

		proposed.renew();
		proposed.sign(trusted.getPrivateKey());

		assertThat(proposed.canBeProposedOver(stored), is(true));
		assertThat(stored.canBeProposedOver(null), is(false));
		assertThat(signedSupply(earlier).canBeProposedOver(null), is(false));
	}
}

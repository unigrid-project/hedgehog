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
	private static Signature retired;

	@SneakyThrows
	@BeforeProperty
	public void before() {
		trusted = new Signature();
		retired = new Signature();

		new MockUp<NetworkKey>() {
			@Mock public static String[] getPublicKeys() {
				return new String[] { trusted.getPublicKey() };
			}

			@Mock public static String[] getRetiredPublicKeys() {
				return new String[] { retired.getPublicKey() };
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
	public void shouldLogARetiredSigner() {
		final MintSupply spork = signedSupply(retired);

		spork.renew();
		spork.sign(trusted.getPrivateKey());

		assertThat(spork.getSignatureLog().getEntries().getFirst().getSigner(), equalTo(retired.getPublicKey()));
		assertThat(spork.isValidSignature(), is(true));
	}

	@SneakyThrows
	@Example
	public void shouldRefuseToSignOverAnUnknownSigner() {
		final MintSupply spork = signedSupply(new Signature());

		spork.renew();

		try {
			spork.sign(trusted.getPrivateKey());
			assertThat("Signing should have been refused", false);
		} catch (SigningException ex) {
			assertThat(spork.getSignatureLog().isEmpty(), is(true));
		}
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
		final MintSupply spork = renewedTwice(retired);

		spork.setSignatureLog(new SignatureLog(spork.getSignatureLog().getEntries().subList(1, 2)));
		assertThat(spork.isValidSignature(), is(false));
	}

	@Example
	public void shouldBreakTheSignatureWhenEntriesAreReordered() {
		final MintSupply spork = renewedTwice(retired);
		final List<SignatureLogEntry> entries = new ArrayList<>(spork.getSignatureLog().getEntries());

		spork.setSignatureLog(new SignatureLog(entries.reversed()));
		assertThat(spork.isValidSignature(), is(false));
	}

	@SneakyThrows
	@Example
	public void shouldBreakTheSignatureWhenASignerIsRewritten() {
		final MintSupply spork = renewedTwice(retired);
		final List<SignatureLogEntry> entries = new ArrayList<>(spork.getSignatureLog().getEntries());

		entries.set(0, entries.getFirst().toBuilder().signer(trusted.getPublicKey()).build());
		spork.setSignatureLog(new SignatureLog(entries));
		assertThat(spork.isValidSignature(), is(false));
	}

	@SneakyThrows
	private MintSupply next(MintSupply base, int second) {
		final MintSupply spork = SerializationUtils.clone(base);

		spork.archive();
		spork.setTimeStamp(SIGNED_AT.plusSeconds(second));
		spork.sign(trusted.getPrivateKey());
		return spork;
	}

	@SneakyThrows
	private MintSupply resigned(MintSupply spork, SignatureLog log) {
		final MintSupply forged = SerializationUtils.clone(spork);

		forged.setSignatureLog(log);
		forged.sign(trusted.getPrivateKey());
		return forged;
	}

	@Example
	public void shouldReplaceNothingWithATrustedFirstVersion() {
		assertThat(signedSupply(trusted).canReplace(null), is(true));
	}

	@Example
	public void shouldNotAcceptAHeadSignedByARetiredKey() {
		assertThat(signedSupply(retired).canReplace(null), is(false));
	}

	@Example
	public void shouldReplaceTheVersionItExtends() {
		final MintSupply stored = signedSupply(trusted);

		assertThat(next(stored, 1).canReplace(stored), is(true));
	}

	@Example
	public void shouldReplaceAVersionSignedBeforeTheKeyChange() {
		final MintSupply stored = signedSupply(retired);

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

	@Example
	public void shouldLetTheLongerBranchWin() {
		final MintSupply base = signedSupply(trusted);
		final MintSupply sibling = next(base, 3);
		final MintSupply branch = next(next(base, 1), 2);

		assertThat(branch.canReplace(sibling), is(true));
		assertThat(sibling.canReplace(branch), is(false));
	}
}

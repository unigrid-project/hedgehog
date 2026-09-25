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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.BeforeProperty;
import org.apache.commons.codec.digest.DigestUtils;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import org.unigrid.hedgehog.jqwik.BaseMockedWeldTest;
import org.unigrid.hedgehog.model.crypto.Signature;

public class SignatureLogTest extends BaseMockedWeldTest {
	private static final Instant START = Instant.parse("2024-04-30T15:08:29.272Z");

	private Signature key;
	private SignatureLog log;

	@SneakyThrows
	@BeforeProperty
	public void before() {
		key = new Signature();
		log = new SignatureLog(List.of(entry(key, 0), entry(key, 1), entry(key, 2)));
	}

	@SneakyThrows
	private static SignatureLogEntry entry(Signature signer, int second) {
		final byte[] data = ("version " + second).getBytes();

		return SignatureLogEntry.builder().timeStamp(START.plusSeconds(second)).signer(signer.getPublicKey())
			.digest(DigestUtils.sha512(data)).signature(signer.sign(data)).build();
	}

	private SignatureLog replaced(int index, SignatureLogEntry entry) {
		final List<SignatureLogEntry> entries = new ArrayList<>(log.getEntries());

		entries.set(index, entry);
		return new SignatureLog(entries);
	}

	@Example
	public void shouldHashNothingForAnEmptyLog() {
		assertThat(new SignatureLog().headHash().length, is(0));
	}

	@Example
	public void shouldBePrefixOfItsExtension() {
		final SignatureLog extended = new SignatureLog(log.getEntries());

		extended.append(entry(key, 3));
		assertThat(log.isPrefixOf(extended), is(true));
		assertThat(new SignatureLog().isPrefixOf(log), is(true));
		assertThat(extended.isPrefixOf(log), is(false));
	}

	@Example
	public void shouldNotBePrefixOfARewrittenLog() {
		final SignatureLog rewritten = replaced(1, entry(key, 1).toBuilder().timeStamp(START.plusMillis(1500)).build());

		assertThat(log.isPrefixOf(rewritten), is(false));
		assertThat(rewritten.headHash(1), equalTo(log.headHash(1)));
	}

	@Example
	public void shouldNotBePrefixOfAReorderedLog() {
		final SignatureLog reordered = new SignatureLog(List.of(log.getEntries().get(1), log.getEntries().get(0),
			log.getEntries().get(2))
		);

		assertThat(log.isPrefixOf(reordered), is(false));
	}

	@Example
	public void shouldAcceptEntriesSignedByKnownKeys() {
		assertThat(log.isValidFrom(0, Set.of(key.getPublicKey())), is(true));
	}

	@Example
	public void shouldRejectEntriesSignedByUnknownKeys() {
		assertThat(log.isValidFrom(0, Set.of()), is(false));
	}

	@Example
	public void shouldRejectAnEntryWhoseSignatureDoesNotMatchItsDigest() {
		final SignatureLogEntry forged = entry(key, 1).toBuilder().digest(DigestUtils.sha512("forged")).build();

		assertThat(replaced(1, forged).isValidFrom(0, Set.of(key.getPublicKey())), is(false));
	}

	@SneakyThrows
	@Example
	public void shouldRejectAnEntryClaimingAnotherSigner() {
		final Signature other = new Signature();
		final SignatureLogEntry forged = entry(key, 1).toBuilder().signer(other.getPublicKey()).build();

		assertThat(replaced(1, forged).isValidFrom(0, Set.of(key.getPublicKey(), other.getPublicKey())), is(false));
	}

	@Example
	public void shouldRejectTimeStampsThatDoNotIncrease() {
		final SignatureLogEntry late = entry(key, 1).toBuilder().timeStamp(START.plusSeconds(2)).build();

		assertThat(replaced(1, late).isValidFrom(0, Set.of(key.getPublicKey())), is(false));
	}

	@Example
	public void shouldOnlyValidateEntriesFromTheGivenIndex() {
		final SignatureLog withForgedFirst = replaced(0, entry(key, 0).toBuilder()
			.digest(DigestUtils.sha512("forged")).build()
		);

		assertThat(withForgedFirst.isValidFrom(1, Set.of(key.getPublicKey())), is(true));
	}
}

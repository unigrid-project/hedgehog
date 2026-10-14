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

package org.unigrid.hedgehog.ledger;

import java.util.Arrays;
import java.util.Random;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.unigrid.hedgehog.ledger.Expect.assertThrows;
import static org.unigrid.hedgehog.ledger.LedgerFixtures.*;

public class TransferTest {
	private static final byte[] CHAIN = new byte[Digests.HASH_SIZE];
	private static final byte[] SIGNATURE = new byte[Ed25519.SIGNATURE_SIZE];

	private static Transfer signed() {
		return Transfer.signed(accountSeed(1), account(2), 300, 10, 4, CHAIN);
	}

	@Property(tries = 100)
	public void shouldBeEqualByContent(@ForAll long seed) {
		final Transfer transfer = CodecFixtures.transfer(new Random(seed));
		final Transfer same = new Transfer(transfer.sender(), transfer.recipient(), transfer.amount(),
			transfer.fee(), transfer.nonce(), transfer.signature());

		assertThat(transfer, equalTo(same));
		assertThat(transfer.hashCode(), equalTo(same.hashCode()));
		assertThat(transfer.id(), equalTo(same.id()));
	}

	@Example
	public void shouldEncodeKindThenFieldsInTheWidthOfTheRecipient() {
		final Transfer toKey = signed();
		final Transfer toLegacy = Transfer.signed(accountSeed(1), new LegacyAddress(new byte[LegacyAddress.SIZE]),
			300, 10, 4, CHAIN);

		assertThat(toKey.encode().length, equalTo(Transfer.MAX_ENCODED_SIZE));
		assertThat(toLegacy.encode().length, equalTo(Transfer.MAX_ENCODED_SIZE - (AccountKey.SIZE - LegacyAddress.SIZE)));
		assertThat(toKey.encode()[0], equalTo(Transfer.KIND));
		assertThat(toKey.kind(), equalTo(Transfer.KIND));
	}

	@Example
	public void shouldRefuseWhatIsNotATransfer() {
		final AccountKey sender = account(1);

		assertThrows(IllegalArgumentException.class, () -> new Transfer(sender, account(2), 0, 1, 0, SIGNATURE));
		assertThrows(IllegalArgumentException.class, () -> new Transfer(sender, account(2), -5, 1, 0, SIGNATURE));
		assertThrows(IllegalArgumentException.class, () -> new Transfer(sender, account(2), 5, -1, 0, SIGNATURE));
		assertThrows(IllegalArgumentException.class, () -> new Transfer(sender, account(2), 5, 1, -1, SIGNATURE));
		assertThrows(IllegalArgumentException.class, () -> new Transfer(sender, account(2), 5, 1, 0, new byte[63]));
	}

	@Example
	public void shouldVerifyOnlyForTheChainAndTheFieldsItWasSignedOver() {
		final Transfer transfer = signed();
		final byte[] otherChain = new byte[Digests.HASH_SIZE];

		otherChain[0] = 1;

		assertThat(transfer.hasValidSignature(CHAIN), is(true));
		assertThat(transfer.hasValidSignature(otherChain), is(false));
		assertThat(new Transfer(transfer.sender(), transfer.recipient(), 301, 10, 4, transfer.signature())
			.hasValidSignature(CHAIN), is(false));
		assertThat(new Transfer(transfer.sender(), transfer.recipient(), 300, 11, 4, transfer.signature())
			.hasValidSignature(CHAIN), is(false));
		assertThat(new Transfer(transfer.sender(), transfer.recipient(), 300, 10, 5, transfer.signature())
			.hasValidSignature(CHAIN), is(false));
		assertThat(new Transfer(transfer.sender(), account(3), 300, 10, 4, transfer.signature())
			.hasValidSignature(CHAIN), is(false));
	}

	@Example
	public void shouldNotLetAKeyAndALegacyAddressOfTheSameBytesShareASignature() {
		final byte[] bytes = account(2).bytes();
		final LegacyAddress legacy = new LegacyAddress(Arrays.copyOf(bytes, LegacyAddress.SIZE));
		final Transfer toLegacy = Transfer.signed(accountSeed(1), legacy, 300, 10, 4, CHAIN);

		assertThat(toLegacy.id(), not(equalTo(signed().id())));
		assertThat(new Transfer(toLegacy.sender(), account(2), 300, 10, 4, toLegacy.signature())
			.hasValidSignature(CHAIN), is(false));
	}

	@Example
	public void shouldPrintItsPartiesAndAmountsAndNoArrayIdentity() {
		final String text = signed().toString();

		assertThat(text, containsString(account(1).toString()));
		assertThat(text, containsString("amount=300"));
		assertThat(text, containsString("nonce=4"));
		assertThat(text, not(containsString("[B@")));
	}
}

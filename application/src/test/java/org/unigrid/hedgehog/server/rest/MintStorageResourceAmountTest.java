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

import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response.Status;
import java.math.BigDecimal;
import java.util.List;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.ShrinkingMode;
import net.jqwik.api.lifecycle.BeforeTry;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import org.unigrid.hedgehog.client.rest.ResponseOddityException;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import org.unigrid.hedgehog.model.Address;
import org.unigrid.hedgehog.model.bootstrap.LegacyAddress;
import org.unigrid.hedgehog.model.crypto.Signature;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;
import org.unigrid.hedgehog.model.spork.MintStorage;
import org.unigrid.hedgehog.model.spork.MintStorage.SporkData.Location;
import org.unigrid.hedgehog.model.spork.PendingSporks;

/* A mint that no node can make is refused when it is handed to a node, not found out by every node when it is due: an
   amount of nothing, below one unit of 1e-8, beyond what a long counts or with more decimals than the wire carries,
   and an address that is neither of the two forms. An amount that would write out as gigabytes cannot be signed at
   all, and the command line refuses it first (SporkContentTest). */
public class MintStorageResourceAmountTest extends BaseRestClientTest {
	private static final String ACCOUNT = "ab".repeat(32);
	private static final String OLD = LegacyAddress.encode(new byte[20]);
	private static final int NUMBER = 4_200_000;

	@Inject private PendingSporks pendingSporks;

	@BeforeTry
	public void leaveNoProposalOfTheTriesBefore() {
		pendingSporks.remove(Type.MINT_STORAGE);
	}

	private Status grow(final String address, final String amount) {
		return Status.fromStatusCode(propose(Type.MINT_STORAGE, (MintStorage.SporkData data) -> data.getMints()
			.put(new Location(new Address(address), NUMBER), new BigDecimal(amount))).getStatus());
	}

	private void assertRefused(final String address, final String amount) {
		final String entry = address + " " + amount;

		assertThat(entry, assertThrows(ResponseOddityException.class, () -> grow(address, amount)).getMessage(),
			startsWith("400 "));
		assertThat(entry, pendingSporks.proposalOf(Type.MINT_STORAGE).isPresent(), is(false));
	}

	@Property(tries = 1, shrinking = ShrinkingMode.OFF)
	public void refusesAnAmountThatNoNodeCanMint(@ForAll("provideSignature") final Signature signature) {
		for (final String amount : List.of("0", "0.00000000", "-1", "-0.00000001", "0.000000009", "0.000000001",
			"0.123456789", "92233720368.54775808", "92233720369", "1E+13", "100000000000000")) {

			assertRefused(ACCOUNT, amount);
			assertRefused(OLD, amount);
		}
	}

	@Property(tries = 1, shrinking = ShrinkingMode.OFF)
	public void takesTheSmallestAndTheLargestAmountThatCanBeMinted(
		@ForAll("provideSignature") final Signature signature) {

		for (final String amount : List.of("0.00000001", "92233720368.54775807", "1", "1E+3", "12.5", "1.500000000",
			"1E+9", "0.10000000000")) {

			assertThat(amount, grow(ACCOUNT, amount), equalTo(Status.ACCEPTED));
		}
	}

	@Property(tries = 1, shrinking = ShrinkingMode.OFF)
	public void refusesAnAddressThatIsNeitherAnOldAddressNorAnAccountInLowerCase(
		@ForAll("provideSignature") final Signature signature) {

		for (final String address : List.of(ACCOUNT.toUpperCase(), "garbage", "abc" + ACCOUNT, ACCOUNT.substring(1),
			"0123456789")) {

			assertRefused(address, "5");
		}
	}
}

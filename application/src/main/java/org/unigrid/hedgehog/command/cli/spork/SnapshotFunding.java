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

package org.unigrid.hedgehog.command.cli.spork;

import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.GenericType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.apache.commons.lang3.Validate;
import org.unigrid.hedgehog.client.rest.ResponseOddityException;
import org.unigrid.hedgehog.client.rest.RestClient;
import org.unigrid.hedgehog.model.bootstrap.AddressBalance;
import org.unigrid.hedgehog.model.bootstrap.LegacyAddressHex;
import org.unigrid.hedgehog.model.spork.LegacyWhiteList.SporkData;

/**
 * The board's check that every address of a whitelist holds funds in the legacy snapshot, asked of the node before
 * anything is signed. Kept apart from the command, which only reads the file and proposes.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class SnapshotFunding {
	private static final String BALANCES = "/bootstrap/addresses";
	private static final String NOT_FUNDED = "These addresses hold no funds in the legacy snapshot: %s";
	private static final String NO_SNAPSHOT = "The node holds no legacy snapshot to check the addresses against;"
		+ " fetch it there with the bootstrap command first";
	private static final GenericType<List<AddressBalance>> BALANCE_LIST = new GenericType<>() { };

	/**
	 * Refuses the list unless every address holds funds, naming each one that does not. The whole list is asked about
	 * in one request, over the connection of the proposal, and the node counts the mints it still owes an address.
	 */
	static void require(final RestClient rest, final SporkData data) throws ResponseOddityException {
		final List<String> addresses = data.getApprovedLegacyAddresses().stream().map(LegacyAddressHex::addressOf)
			.toList();
		final Set<String> funded = fundedAmong(rest, addresses);
		final List<String> refused = addresses.stream().filter(address -> !funded.contains(address)).toList();

		Validate.isTrue(refused.isEmpty(), NOT_FUNDED, String.join(", ", refused));
	}

	/**
	 * The addresses the node holds funds for: a balance above zero and at least one transaction. A 503 means the node
	 * holds no snapshot to check against.
	 */
	private static Set<String> fundedAmong(final RestClient rest, final List<String> addresses)
		throws ResponseOddityException {

		try (Response response = rest.post(BALANCES, Entity.json(addresses))) {
			return response.readEntity(BALANCE_LIST).stream()
				.filter(balance -> balance.getTransactionCount() > 0 && balance.getBalance().signum() > 0)
				.map(AddressBalance::getAddress).collect(Collectors.toSet());

		} catch (ResponseOddityException ex) {
			if (ex.getStatusCode() == Status.SERVICE_UNAVAILABLE.getStatusCode()) {
				throw new IllegalArgumentException(NO_SNAPSHOT, ex);
			}

			throw ex;
		}
	}
}

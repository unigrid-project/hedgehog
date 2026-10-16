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

import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.SneakyThrows;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;
import org.unigrid.hedgehog.client.rest.ResponseOddityException;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import org.unigrid.hedgehog.model.crypto.Signature;
import org.unigrid.hedgehog.model.spork.LedgerBlacklist;
import org.unigrid.hedgehog.model.spork.LedgerBlacklistTest;

public class LedgerBlacklistResourceTest extends BaseRestClientTest {
	private static final String URL = "/gridspork/ledger-blacklist";

	@Provide
	public Arbitrary<LedgerBlacklist.SporkData> validData() {
		return LedgerBlacklistTest.cappedSets().map(LedgerBlacklistTest::with);
	}

	@Provide
	public Arbitrary<LedgerBlacklist.SporkData> invalidData() {
		return LedgerBlacklistTest.cappedSets().map(addresses -> LedgerBlacklistTest.with(
			LedgerBlacklistTest.plus(addresses, "not a hash")
		));
	}

	private Response put(final Entity<?> entity, final String privateKey) throws ResponseOddityException {
		return client.putWithHeaders(URL, entity, new MultivaluedHashMap<>(Map.of("privateKey", privateKey)));
	}

	private Optional<Set<String>> stored() throws ResponseOddityException {
		final Response response = client.get(URL);

		if (Status.fromStatusCode(response.getStatus()) == Status.NO_CONTENT) {
			return Optional.empty();
		}

		return Optional.of(response.readEntity(LedgerBlacklist.class).<LedgerBlacklist.SporkData>getData()
			.getLegacyAddresses());
	}

	@SneakyThrows
	private void assertStores(final LedgerBlacklist.SporkData data, final Signature signature) {
		final Response proposal = put(Entity.json(data), signature.getPrivateKey());

		assertThat(Status.fromStatusCode(proposal.getStatus()), equalTo(Status.ACCEPTED));
		assertThat(Status.fromStatusCode(cosign(proposal).getStatus()), equalTo(Status.OK));
		assertThat(stored(), equalTo(Optional.of(data.getLegacyAddresses())));
	}

	@Property(tries = 10)
	public void storesAnySetOfHashes(@ForAll("provideSignature") final Signature signature,
		@ForAll("validData") final LedgerBlacklist.SporkData data) {

		assertStores(data, signature);
	}

	@Property(tries = 1)
	public void storesTheLargestList(@ForAll("provideSignature") final Signature signature) {
		final Set<String> largest = LedgerBlacklistTest.distinctHashes(LedgerBlacklist.SporkData.MAX_ENTRIES);

		assertStores(LedgerBlacklistTest.with(largest), signature);
	}

	@SneakyThrows
	@Property(tries = 5)
	public void rejectsAnEntryThatIsNoHash(@ForAll("provideSignature") final Signature signature,
		@ForAll("invalidData") final LedgerBlacklist.SporkData data) {

		final Optional<Set<String>> before = stored();

		/* The client refuses any status it does not expect, a 400 among them */
		final ResponseOddityException refusal = assertThrows(ResponseOddityException.class,
			() -> put(Entity.json(data), signature.getPrivateKey()));

		assertThat(refusal.getMessage(), startsWith("400 "));
		assertThat(stored(), equalTo(before));
	}

	@SneakyThrows
	@Property(tries = 1)
	public void rejectsTooLongAList(@ForAll("provideSignature") final Signature signature) {
		final Optional<Set<String>> before = stored();
		final LedgerBlacklist.SporkData data = LedgerBlacklistTest.with(
			LedgerBlacklistTest.distinctHashes(LedgerBlacklist.SporkData.MAX_ENTRIES + 1));

		final ResponseOddityException refusal = assertThrows(ResponseOddityException.class,
			() -> put(Entity.json(data), signature.getPrivateKey()));

		assertThat(refusal.getMessage(), startsWith("400 "));
		assertThat(stored(), equalTo(before));
	}

	@SneakyThrows
	@Property(tries = 1)
	public void rejectsAMissingList(@ForAll("provideSignature") final Signature signature) {
		final Optional<Set<String>> before = stored();

		final ResponseOddityException refusal = assertThrows(ResponseOddityException.class,
			() -> put(Entity.json("{\"legacyAddresses\":null}"), signature.getPrivateKey()));

		assertThat(refusal.getMessage(), startsWith("400 "));
		assertThat(stored(), equalTo(before));
	}

	/* The trusted signature is only drawn to install a network key that the fresh one then fails to match */
	@SneakyThrows
	@Property(tries = 3)
	public void rejectsUntrustedKeys(@ForAll("provideSignature") final Signature trusted,
		@ForAll("validData") final LedgerBlacklist.SporkData data) {

		final Optional<Set<String>> before = stored();

		assertThat(Status.fromStatusCode(put(Entity.json(data), new Signature().getPrivateKey()).getStatus()),
			equalTo(Status.UNAUTHORIZED));
		assertThat(stored(), equalTo(before));
	}
}

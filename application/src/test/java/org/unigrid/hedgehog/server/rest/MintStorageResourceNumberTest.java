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

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import java.math.BigDecimal;
import lombok.SneakyThrows;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.ShrinkingMode;
import net.jqwik.api.constraints.BigRange;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.comparesEqualTo;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;
import org.unigrid.hedgehog.client.rest.ResponseOddityException;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import org.unigrid.hedgehog.model.Address;
import org.unigrid.hedgehog.model.bootstrap.LegacyAddress;
import org.unigrid.hedgehog.model.crypto.Signature;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;
import org.unigrid.hedgehog.model.spork.MintStorage;
import org.unigrid.hedgehog.model.spork.MintStorage.SporkData.Location;

/* The number of a mint in the path of the resource that shows it: a height of the old chain, or a time in seconds of a
   ledger account above the range of an int */
public class MintStorageResourceNumberTest extends BaseRestClientTest {
	private static final int ACCOUNT_LENGTH = 64;
	private static final int HASH_LENGTH = 20;
	private static final long UNSIGNED_MAXIMUM = 4_294_967_295L;
	private static final String URL = "/gridspork/mint-storage/";

	@Provide
	public Arbitrary<String> accounts() {
		return Arbitraries.strings().withChars("0123456789abcdef").ofLength(ACCOUNT_LENGTH);
	}

	@Provide
	public Arbitrary<String> oldAddresses() {
		return Arbitraries.bytes().array(byte[].class).ofSize(HASH_LENGTH).map(LegacyAddress::encode);
	}

	@SneakyThrows
	private void growAndCosign(String address, int height, BigDecimal amount) {
		final Location location = Location.builder().address(new Address(address)).height(height).build();
		final Response proposal = propose(Type.MINT_STORAGE, (MintStorage.SporkData data) -> data.getMints()
			.put(location, amount));

		assertThat(Status.fromStatusCode(proposal.getStatus()), equalTo(Status.ACCEPTED));
		assertThat(Status.fromStatusCode(cosign(proposal).getStatus()), equalTo(Status.OK));
	}

	@SneakyThrows
	private void assertStored(String address, String number, int height, BigDecimal amount) {
		final Location location = Location.builder().address(new Address(address)).height(height).build();
		final MintStorage.SporkData data = client.getEntity(URL, MintStorage.class).getData();

		assertThat(data.getMints().get(location), comparesEqualTo(amount));
		assertThat(client.getEntity(URL, String.class), containsString("\"" + address + "/" + number + "\""));
		assertThat(new BigDecimal(client.getEntity(URL + address + "/" + number, String.class)), comparesEqualTo(amount));
	}

	private void assertRefused(String address, String number) {
		final String entry = address + "/" + number;

		assertThat(entry, assertThrows(ResponseOddityException.class, () -> {
			throw new AssertionError("not refused: " + client.get(URL + entry).getStatusInfo());
		}).getMessage(), startsWith("400 "));
	}

	@Property(tries = 5, shrinking = ShrinkingMode.OFF)
	public void showsTheMintOfAnAccountByItsTimeAbove2To31(@ForAll("provideSignature") Signature signature,
		@ForAll("accounts") String account, @ForAll @LongRange(min = 2_147_483_648L, max = UNSIGNED_MAXIMUM)
		long seconds, @ForAll @BigRange(min = "1", max = "1000000") BigDecimal amount) {

		growAndCosign(account, (int) seconds, amount);
		assertStored(account, Long.toString(seconds), (int) seconds, amount);
	}

	@Property(tries = 5, shrinking = ShrinkingMode.OFF)
	public void showsTheMintOfAnAddressOfTheOldChainByItsHeight(@ForAll("provideSignature") Signature signature,
		@ForAll("oldAddresses") String address, @ForAll @IntRange(min = 0) int height,
		@ForAll @BigRange(min = "1", max = "1000000") BigDecimal amount) {

		growAndCosign(address, height, amount);
		assertStored(address, Integer.toString(height), height, amount);
	}

	@Property(tries = 1, shrinking = ShrinkingMode.OFF)
	public void refusesATimeOfAnAccountThatIsNotOfThirtyTwoBits(@ForAll("accounts") String account) {
		for (String number : new String[] { "4294967296", "-1", "99999999999", "9223372036854775807" }) {
			assertRefused(account, number);
		}
	}

	@Property(tries = 1, shrinking = ShrinkingMode.OFF)
	public void refusesAHeightOfTheOldChainAboveTheRangeOfAnInteger(@ForAll("oldAddresses") String address) {
		for (String number : new String[] { "2147483648", "4294967295", "99999999999" }) {
			assertRefused(address, number);
		}
	}

	/* The instant form is a convenience of the command line, which signs the seconds */
	@SneakyThrows
	@Property(tries = 2, shrinking = ShrinkingMode.OFF)
	public void takesNoIsoInstantInThePath(@ForAll("accounts") String account) {
		for (String instant : new String[] { "2026-06-01T00:00:00Z", "2026-06-01T02:00:00+02:00" }) {
			assertThat(instant, Status.fromStatusCode(client.get(URL + account + "/" + instant).getStatus()),
				equalTo(Status.NOT_FOUND));
		}
	}
}

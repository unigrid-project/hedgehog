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
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.BeforeTry;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;
import org.apache.commons.lang3.SerializationUtils;
import org.unigrid.hedgehog.client.rest.ResponseOddityException;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import org.unigrid.hedgehog.model.bootstrap.LegacyAddress;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;
import org.unigrid.hedgehog.model.spork.LegacyWhiteList;
import org.unigrid.hedgehog.model.spork.LegacyWhiteListTest;
import org.unigrid.hedgehog.model.spork.SporkDatabase;
import org.unigrid.hedgehog.server.rest.entity.LegacyApproval;

/** The legacy whitelist as a client reads it: whole, or one address at a time, and only once two board keys signed it. */
public class LegacyWhiteListResourceTest extends BaseRestClientTest {
	private static final String URL = "/gridspork/legacy-whitelist";
	private static final String APPROVED_HASH = "1".repeat(40);
	private static final String OTHER_HASH = "2".repeat(40);

	@Inject
	private SporkDatabase sporkDatabase;

	/** Starts each try with no whitelist held and the test board keys trusted. */
	@BeforeTry
	public void holdNoWhitelist() {
		mockNetworkKeys();
		sporkDatabase.clear(Type.LEGACY_WHITELIST);
	}

	/** The legacy address of a hash160. */
	private static String address(String hash) {
		return LegacyAddress.encode(HexFormat.of().parseHex(hash));
	}

	/** A whitelist of the hashes, signed by one board key and co-signed by a second when asked. */
	@SneakyThrows
	private static LegacyWhiteList approving(Set<String> hashes, boolean cosigned) {
		final LegacyWhiteList whitelist = new LegacyWhiteList();

		whitelist.setData(LegacyWhiteListTest.with(hashes));
		whitelist.archive();
		whitelist.sign(PROPOSER.getPrivateKey());

		if (cosigned) {
			whitelist.cosign(COSIGNER.getPrivateKey());
		}

		return whitelist;
	}

	/** The reason the node gives for not approving an address, which it answers with 404. */
	@SneakyThrows
	private String refusalOf(String address) {
		final Response response = client.get(URL + "/" + address);

		assertThat(response.getStatus(), equalTo(Status.NOT_FOUND.getStatusCode()));
		return (String) response.readEntity(Map.class).get(RestError.FIELD);
	}

	/** Before any list is held there is nothing to show and nothing is approved. */
	@SneakyThrows
	@Example
	public void shouldHoldNothingToShowOrApproveBeforeTheBoardSignsAList() {
		assertThat(client.get(URL).getStatus(), equalTo(Status.NO_CONTENT.getStatusCode()));
		assertThat(refusalOf(address(APPROVED_HASH)),
			equalTo("No legacy whitelist that two board keys signed is held yet"));
	}

	/** An address on a co-signed list is approved, with both signers and when the list was signed. */
	@SneakyThrows
	@Example
	public void shouldApproveAnAddressOfAListTwoBoardKeysSigned() {
		final LegacyWhiteList whitelist = approving(Set.of(APPROVED_HASH), true);

		sporkDatabase.set(whitelist);

		final LegacyApproval approval = client.getEntity(URL + "/" + address(APPROVED_HASH), LegacyApproval.class);

		assertThat(client.get(URL).getStatus(), equalTo(Status.OK.getStatusCode()));
		assertThat(approval.address(), equalTo(address(APPROVED_HASH)));
		assertThat(approval.approved(), equalTo(true));
		assertThat(approval.signedBy(), equalTo(List.of(PROPOSER.getPublicKey(), COSIGNER.getPublicKey())));
		assertThat(approval.publishTimeStamp(), equalTo(whitelist.getTimeStamp()));
	}

	/** An address that is not on the list is refused by name. */
	@Example
	public void shouldNotApproveAnAddressThatIsNotOnTheList() {
		sporkDatabase.set(approving(Set.of(APPROVED_HASH), true));

		assertThat(refusalOf(address(OTHER_HASH)),
			equalTo(address(OTHER_HASH) + " is not on the legacy whitelist"));
	}

	/** A list that one board key signed is a proposal, which approves nothing until a second key co-signs it. */
	@Example
	public void shouldNotApproveFromAListThatOnlyOneKeySigned() {
		sporkDatabase.set(approving(Set.of(APPROVED_HASH), false));

		assertThat(refusalOf(address(APPROVED_HASH)),
			equalTo("No legacy whitelist that two board keys signed is held yet"));
	}

	/** Something that is no legacy address is refused with 400. */
	@Example
	public void shouldRejectSomethingThatIsNoLegacyAddress() {
		final ResponseOddityException refusal = assertThrows(ResponseOddityException.class,
			() -> client.get(URL + "/NotAnAddress"));

		assertThat(refusal.getMessage(), startsWith(String.valueOf(Status.BAD_REQUEST.getStatusCode())));
	}

	/**
	 * Submitted to the node as the board's command line does: signed by one key the list is held as a proposal and
	 * approves nothing, and once a second key co-signs it the node stores it and the address is approved.
	 */
	@SneakyThrows
	@Example
	public void shouldHoldAOneKeyListAndApproveOnceASecondKeyCosignsIt() {
		final GridSpork proposal = replacing(Type.LEGACY_WHITELIST, LegacyWhiteListTest.with(Set.of(APPROVED_HASH)),
			PROPOSER);
		final GridSpork cosigned = SerializationUtils.clone(proposal);

		assertThat(submit(proposal).getStatus(), equalTo(Status.ACCEPTED.getStatusCode()));
		assertThat(refusalOf(address(APPROVED_HASH)),
			equalTo("No legacy whitelist that two board keys signed is held yet"));

		cosigned.cosign(COSIGNER.getPrivateKey());
		assertThat(submit(cosigned).getStatus(), equalTo(Status.OK.getStatusCode()));

		final LegacyApproval approval = client.getEntity(URL + "/" + address(APPROVED_HASH), LegacyApproval.class);

		assertThat(approval.signedBy(), equalTo(List.of(PROPOSER.getPublicKey(), COSIGNER.getPublicKey())));
		assertThat(approval.publishTimeStamp(), equalTo(cosigned.getTimeStamp()));
	}
}

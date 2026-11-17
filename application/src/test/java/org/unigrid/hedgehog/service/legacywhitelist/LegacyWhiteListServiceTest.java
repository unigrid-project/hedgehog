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

package org.unigrid.hedgehog.service.legacywhitelist;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import mockit.Mock;
import mockit.MockUp;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.BeforeTry;
import net.jqwik.api.lifecycle.PropagationMode;
import org.unigrid.hedgehog.jqwik.MockitHook;
import org.unigrid.hedgehog.model.bootstrap.LegacyAddress;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.LegacyWhiteList;
import org.unigrid.hedgehog.model.spork.LegacyWhiteListTest;
import org.unigrid.hedgehog.model.spork.SporkDatabase;

/**
 * The four answers of the whitelist, without HTTP. Signatures are stood in for here; real signing is covered end to
 * end by LegacyWhiteListResourceTest.
 */
@AddLifecycleHook(value = MockitHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class LegacyWhiteListServiceTest {
	private static final String APPROVED_HASH = "1".repeat(40);
	private static final String OTHER_HASH = "2".repeat(40);
	private static final byte[] SIGNATURE = { 1 };
	private static final byte[] COSIGNATURE = { 2 };
	private static final Instant SIGNED = Instant.ofEpochSecond(1_700_000_000L);

	/** Every list here counts, and names a signer by the first byte of its signature. */
	@BeforeTry
	public void standInForSignatures() {
		new MockUp<GridSpork>() {
			@Mock public boolean isCounting() {
				return true;
			}

			@Mock public Optional<String> signerOfHead(byte[] headSignature) {
				return Optional.of("key" + headSignature[0]);
			}
		};
	}

	/** A service over a database that holds the given whitelist, or none. */
	private static LegacyWhiteListService holding(Optional<Set<String>> hashes) {
		final SporkDatabase database = SporkDatabase.builder().build();

		hashes.ifPresent(approved -> {
			final LegacyWhiteList whitelist = new LegacyWhiteList();

			whitelist.setData(LegacyWhiteListTest.with(approved));
			whitelist.setSignature(SIGNATURE);
			whitelist.setCosignature(COSIGNATURE);
			whitelist.setTimeStamp(SIGNED);
			database.set(whitelist);
		});

		return new LegacyWhiteListService(database);
	}

	/** The legacy address of a hash160. */
	private static String address(String hash) {
		return LegacyAddress.encode(HexFormat.of().parseHex(hash));
	}

	/** An address on the list is approved, with the signer first and when the list was signed. */
	@Example
	public void shouldApproveAnAddressOnTheList() {
		final WhiteListAnswer answer = holding(Optional.of(Set.of(APPROVED_HASH)))
			.approvalOf(address(APPROVED_HASH));

		assertThat(answer, equalTo(new WhiteListAnswer.Approved(List.of("key1", "key2"), SIGNED)));
	}

	/** An address that is not on the list is not approved. */
	@Example
	public void shouldNotApproveAnAddressOffTheList() {
		final WhiteListAnswer answer = holding(Optional.of(Set.of(APPROVED_HASH))).approvalOf(address(OTHER_HASH));

		assertThat(answer, instanceOf(WhiteListAnswer.NotListed.class));
	}

	/** With no signed list held, nothing is approved yet. */
	@Example
	public void shouldSayNoListIsSignedYet() {
		assertThat(holding(Optional.empty()).approvalOf(address(APPROVED_HASH)),
			instanceOf(WhiteListAnswer.NoSignedList.class));
		assertThat(holding(Optional.empty()).held(), equalTo(Optional.empty()));
	}

	/** Something that is no legacy address is told apart before the list is read. */
	@Example
	public void shouldRefuseSomethingThatIsNoLegacyAddress() {
		assertThat(holding(Optional.of(Set.of(APPROVED_HASH))).approvalOf("NotAnAddress"),
			instanceOf(WhiteListAnswer.NotAnAddress.class));
	}
}

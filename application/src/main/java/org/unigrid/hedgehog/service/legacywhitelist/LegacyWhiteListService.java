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

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;
import org.unigrid.hedgehog.model.bootstrap.LegacyAddressHex;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;
import org.unigrid.hedgehog.model.spork.LegacyWhiteList;
import org.unigrid.hedgehog.model.spork.SporkDatabase;
import org.unigrid.hedgehog.model.spork.SporkRelease;

/** Answers what the legacy whitelist says, so the REST resource only turns answers into HTTP. */
@ApplicationScoped
public class LegacyWhiteListService {
	@Inject
	private SporkDatabase sporkDatabase;

	/** For CDI. */
	public LegacyWhiteListService() {
		/* Injected */
	}

	/** For tests, which need no container. */
	LegacyWhiteListService(SporkDatabase sporkDatabase) {
		this.sporkDatabase = sporkDatabase;
	}

	/** Whether this release takes the spork at all; one that does not holds none either. */
	public boolean isTaken() {
		return SporkRelease.isTaken(Type.LEGACY_WHITELIST);
	}

	/** The version this node holds, signed by one key or two, so anyone can check who signed it. */
	public Optional<LegacyWhiteList> held() {
		return Optional.ofNullable(sporkDatabase.getLegacyWhiteList());
	}

	/** Reads only a list two board keys signed, as a one-key proposal is not yet the board's decision. */
	public WhiteListAnswer approvalOf(String address) {
		final String hash;

		try {
			hash = LegacyAddressHex.hashOf(address);
		} catch (IllegalArgumentException ex) {
			return new WhiteListAnswer.NotAnAddress(ex.getMessage());
		}

		final GridSpork whitelist = sporkDatabase.counting(Type.LEGACY_WHITELIST);

		if (Objects.isNull(whitelist)) {
			return new WhiteListAnswer.NoSignedList();
		}

		if (!whitelist.<LegacyWhiteList.SporkData>getData().getApprovedLegacyAddresses().contains(hash)) {
			return new WhiteListAnswer.NotListed();
		}

		return new WhiteListAnswer.Approved(signersOf(whitelist), whitelist.getTimeStamp());
	}

	/** The public keys of the signer and the co-signer, in that order. */
	private static List<String> signersOf(GridSpork whitelist) {
		return Stream.of(whitelist.getSignature(), whitelist.getCosignature()).map(whitelist::signerOfHead)
			.flatMap(Optional::stream).toList();
	}
}

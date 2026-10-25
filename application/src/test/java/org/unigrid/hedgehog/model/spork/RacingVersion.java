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

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/* A copy of a version that has another version of its type stored meanwhile the first time it is judged, as when two
   versions come in at once and one is stored while the signatures of the other are checked */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class RacingVersion {
	public static MintSupply of(MintSupply version, Runnable meanwhile) {
		final AtomicBoolean isFirst = new AtomicBoolean(true);
		final MintSupply racing = new MintSupply() {
			@Override
			public boolean canReplace(GridSpork stored, BooleanSupplier mayVerify) {
				final boolean mayReplace = super.canReplace(stored, mayVerify);

				if (isFirst.getAndSet(false)) {
					meanwhile.run();
				}

				return mayReplace;
			}
		};

		racing.setTimeStamp(version.getTimeStamp());
		racing.setPreviousTimeStamp(version.getPreviousTimeStamp());
		racing.setFlags(version.getFlags());
		racing.setData(version.getData());
		racing.setPreviousData(version.getPreviousData());
		racing.setSignatureLog(version.getSignatureLog());
		racing.setSignature(version.getSignature());
		racing.setCosignature(version.getCosignature());
		return racing;
	}
}

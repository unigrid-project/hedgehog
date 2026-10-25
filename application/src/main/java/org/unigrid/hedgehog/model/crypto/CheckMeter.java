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

package org.unigrid.hedgehog.model.crypto;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
* Who pays for the signatures that a thread checks. What a peer sends is checked on the account of that peer, one
* check at a time as each is made, so what is charged is what was done, however the work is split between the
* decisions that a spork goes through. A check that the account cannot pay for is not made: it ends the work with a
* {@link ChecksSpentException}, which no decision takes for an answer about the spork, so nothing remembers it as
* forged. Work that runs on no account, as what the command line and the util commands check, is not limited.
*/
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class CheckMeter {
	private static final ThreadLocal<BooleanSupplier> ACCOUNT = new ThreadLocal<>();

	/* The work, with every check it makes paid for from the account, which answers whether it paid for one more */
	public static <T> T charging(BooleanSupplier account, Supplier<T> work) {
		final BooleanSupplier before = ACCOUNT.get();

		ACCOUNT.set(account);

		try {
			return work.get();
		} finally {
			if (Objects.isNull(before)) {
				ACCOUNT.remove();
			} else {
				ACCOUNT.set(before);
			}
		}
	}

	/* Right before a signature is checked */
	static void charge() {
		final BooleanSupplier account = ACCOUNT.get();

		if (Objects.nonNull(account) && !account.getAsBoolean()) {
			throw new ChecksSpentException();
		}
	}
}

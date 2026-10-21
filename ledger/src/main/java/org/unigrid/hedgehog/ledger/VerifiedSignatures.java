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

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;

/* A transaction is checked several times on its way into a block, and each check of a signature is a
   public-key operation. A transaction is equal by content and is valid or not for the one chain a state
   belongs to, so a success can be kept and a failure never is: a refused transaction is checked again each
   time it is offered. The oldest entry makes room when the cache is full. */
final class VerifiedSignatures {
	static final int CAPACITY = 10_000;

	private final Set<Transaction> verified = new LinkedHashSet<>();

	synchronized boolean check(Transaction transaction, BooleanSupplier verification) {
		if (verified.contains(transaction)) {
			return true;
		}

		if (!verification.getAsBoolean()) {
			return false;
		}

		if (verified.size() >= CAPACITY) {
			final Iterator<Transaction> oldest = verified.iterator();

			oldest.next();
			oldest.remove();
		}

		verified.add(transaction);
		return true;
	}

	synchronized int size() {
		return verified.size();
	}
}

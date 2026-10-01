/*
    Unigrid Hedgehog
    Copyright © 2021-2023 Stiftelsen The Unigrid Foundation

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

package org.unigrid.hedgehog.model.storage;

public enum StorageStatus {
	OK, QUOTA, INVALID, TOMBSTONE, DISABLED, NOT_FOUND, DUPLICATE, ERROR;

	/* A status byte from a peer is untrusted, and one this node does not know is no better than a failure */
	public static StorageStatus of(final int ordinal) {
		final StorageStatus[] statuses = values();
		return ordinal >= 0 && ordinal < statuses.length ? statuses[ordinal] : ERROR;
	}
}

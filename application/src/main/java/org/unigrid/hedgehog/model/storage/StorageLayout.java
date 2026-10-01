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

package org.unigrid.hedgehog.model.storage;

import lombok.Value;

@Value
public class StorageLayout {
	private final LayoutParameters parameters;
	private final long fileSize;

	public static StorageLayout of(LayoutParameters parameters, long fileSize) {
		parameters.validate();
		LayoutParameters.require(fileSize >= 0, "fileSize cannot be negative");

		final StorageLayout layout = new StorageLayout(parameters, fileSize);
		LayoutParameters.require(layout.stripeCount() <= Integer.MAX_VALUE, "fileSize needs too many stripes");
		return layout;
	}

	public long dataChunks() {
		return Math.max(1, ceilDiv(fileSize, parameters.payloadSize()));
	}

	public int stripes() {
		return Math.toIntExact(stripeCount());
	}

	private long stripeCount() {
		return ceilDiv(dataChunks(), parameters.getMaxOuterDataChunks());
	}

	public int dataChunksIn(int stripe) {
		return (int) Math.min(parameters.getMaxOuterDataChunks(), dataChunks() - firstSequenceOf(stripe));
	}

	public int parityChunksIn(int stripe) {
		return parameters.outerParityChunks(dataChunksIn(stripe));
	}

	public long firstSequenceOf(int stripe) {
		return (long) stripe * parameters.getMaxOuterDataChunks();
	}

	private static long ceilDiv(long dividend, long divisor) {
		return dividend / divisor + (dividend % divisor == 0 ? 0 : 1);
	}
}

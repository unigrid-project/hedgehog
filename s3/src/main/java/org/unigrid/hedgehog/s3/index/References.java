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

package org.unigrid.hedgehog.s3.index;

import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.unigrid.hedgehog.s3.spi.BlobId;
import org.unigrid.hedgehog.s3.spi.BlobStoreException;

/* Counts the versions and parts that refer to each object blob. A copy shares the blobs of its source and a
   completed upload those of its parts, so no single record can tell whether a blob is still in use, and scanning
   the namespace for it on every delete would not scale. Each count lives in the tree next to the records and
   changes in the same transaction as they do. A blob whose count drops to zero is queued for deletion once the
   grace period is over, which leaves reads already under way time to finish, and leaves the queue again when
   something refers to it before that. */
final class References {
	private final Map<BlobId, Integer> changes = new HashMap<>();

	void retain(final Set<BlobId> blobs) {
		blobs.forEach(blob -> changes.merge(blob, 1, Integer::sum));
	}

	void release(final Set<BlobId> blobs) {
		blobs.forEach(blob -> changes.merge(blob, -1, Integer::sum));
	}

	/* Writes the new counts, where blobs being deleted right now must not be referred to again */
	void settle(final Transaction transaction, final Instant due, final Set<BlobId> deleting) throws IOException,
		BlobStoreException {

		for (Map.Entry<BlobId, Integer> change : changes.entrySet()) {
			final BlobId blob = change.getKey();
			final long count = count(transaction, blob);
			final long next = count + change.getValue();

			if (next < 0) {
				throw new CorruptIndexException("More references to a blob were dropped than made");
			} else if (count > 0 && next == 0) {
				transaction.delete(Keys.reference(blob));
				transaction.put(Keys.garbage(blob), Codec.encode(output -> Codec.writeInstant(output, due)));
			} else if (next > 0) {
				revive(transaction, blob, count, deleting);
				transaction.put(Keys.reference(blob),
					Codec.encode(output -> Codec.writeNumber(output, next)));
			}
		}
	}

	private static void revive(final Transaction transaction, final BlobId blob, final long count,
		final Set<BlobId> deleting) throws IOException, BlobStoreException {

		if (count == 0 && deleting.contains(blob)) {
			throw new IllegalStateException("A blob being deleted after its release is referred to again");
		}

		if (count == 0 && transaction.get(Keys.garbage(blob)).isPresent()) {
			transaction.delete(Keys.garbage(blob));
		}
	}

	static long count(final View view, final BlobId blob) throws IOException, BlobStoreException {
		final Optional<byte[]> count = view.get(Keys.reference(blob));
		return count.isPresent() ? Codec.decode(count.get(), Codec::readNumber) : 0;
	}
}

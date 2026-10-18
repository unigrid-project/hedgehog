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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import org.unigrid.hedgehog.s3.spi.BlobStoreException;

/* Lists the versions or uploads of a bucket the way S3 lists them. It walks the slots under the prefix in key
   order, lets the decoder say what each slot lists as, and rolls keys up at the delimiter: a common prefix is
   listed once, unless the marker already passed it, and the walk then seeks past every key under it. Entries and
   common prefixes count against the limit together, and a page ends truncated once one more would not fit. */
final class Lister<T> {
	@FunctionalInterface
	interface Decoder<T> {
		/* What the slot lists as, or null for nothing, where first tells whether it is the first slot of its key */
		T decode(Keys.Parsed parsed, byte[] value, boolean first) throws IOException;
	}

	private final View view;
	private final byte kind;
	private final String bucket;
	private final ListQuery query;
	private final Decoder<T> decoder;
	private final Function<T, String> ids;
	private final byte[] end;
	private final List<T> entries = new ArrayList<>();
	private final List<String> prefixes = new ArrayList<>();
	private Cursor cursor;
	private String previous;
	private Marker last;
	private Marker next;

	Lister(final View view, final byte kind, final String bucket, final ListQuery query, final Decoder<T> decoder,
		final Function<T, String> ids) {

		this.view = view;
		this.kind = kind;
		this.bucket = bucket;
		this.query = query;
		this.decoder = decoder;
		this.ids = ids;
		end = Keys.end(Keys.objects(kind, bucket, query.prefix()));
	}

	/* Lists from the first slot after the position, or from the start of the prefix without one. A listing that
	   resumes inside the versions or uploads of a key names that key, so its next slot does not count as first. */
	Listing<T> list(final byte[] after, final String resumed) throws IOException, BlobStoreException {
		if (query.limit() > 0) {
			cursor = view.scan(later(Keys.objects(kind, bucket, query.prefix()), after), end);
			previous = resumed;

			Slot slot = cursor.next();

			while (Objects.nonNull(slot) && Objects.isNull(next)) {
				accept(slot);
				slot = cursor.next();
			}
		}

		return new Listing<>(entries, prefixes, Optional.ofNullable(next));
	}

	private static byte[] later(final byte[] start, final byte[] after) {
		return Objects.isNull(after) || Arrays.compareUnsigned(after, start) < 0 ? start : after;
	}

	private void accept(final Slot slot) throws IOException, BlobStoreException {
		final Keys.Parsed parsed = Keys.parse(slot.key());
		final T entry = decoder.decode(parsed, slot.value(), !parsed.key().equals(previous));
		final String common = commonPrefix(parsed.key());

		previous = parsed.key();

		if (Objects.nonNull(entry) && Objects.nonNull(common)) {
			rollUp(common);
		} else if (Objects.nonNull(entry) && !full()) {
			entries.add(entry);
			last = new Marker(parsed.key(), ids.apply(entry));
		}
	}

	private void rollUp(final String common) throws IOException, BlobStoreException {
		if (Keys.compare(common, query.keyMarker()) > 0 && !full()) {
			prefixes.add(common);
			last = new Marker(common, null);
		}

		cursor = view.scan(Keys.end(Keys.objects(kind, bucket, common)), end);
	}

	private String commonPrefix(final String key) {
		final int at = query.delimiter().isEmpty() ? -1 : key.indexOf(query.delimiter(), query.prefix().length());
		return at < 0 ? null : key.substring(0, at + query.delimiter().length());
	}

	private boolean full() {
		if (entries.size() + prefixes.size() < query.limit()) {
			return false;
		}

		next = last;
		return true;
	}
}

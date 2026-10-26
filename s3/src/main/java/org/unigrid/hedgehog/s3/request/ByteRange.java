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

package org.unigrid.hedgehog.s3.request;

import java.util.Optional;

public record ByteRange(long first, long last) {
	private static final String PREFIX = "bytes=";

	public static Optional<ByteRange> parse(final String header, final long size)
		throws UnsatisfiableRangeException {

		if (!singleRange(header)) {
			return Optional.empty();
		}

		final String spec = header.substring(PREFIX.length());
		final int dash = spec.indexOf('-');
		final String first = spec.substring(0, dash);
		final String last = spec.substring(dash + 1);

		if (first.isEmpty()) {
			return suffix(last, size);
		}

		return last.isEmpty() ? from(first, size) : between(first, last, size);
	}

	public long length() {
		return last - first + 1;
	}

	private static boolean singleRange(final String header) {
		if (header == null || !header.startsWith(PREFIX) || header.contains(",")) {
			return false;
		}

		final String spec = header.substring(PREFIX.length());
		return spec.indexOf('-') >= 0 && spec.indexOf('-') == spec.lastIndexOf('-');
	}

	private static Optional<ByteRange> from(final String firstText, final long size)
		throws UnsatisfiableRangeException {

		final Optional<Long> first = number(firstText);

		if (first.isEmpty()) {
			return Optional.empty();
		}

		return range(first.get(), size - 1, size);
	}

	private static Optional<ByteRange> between(final String firstText, final String lastText, final long size)
		throws UnsatisfiableRangeException {

		final Optional<Long> first = number(firstText);
		final Optional<Long> last = number(lastText);

		if (first.isEmpty() || last.isEmpty() || first.get() > last.get()) {
			return Optional.empty();
		}

		return range(first.get(), last.get(), size);
	}

	private static Optional<ByteRange> suffix(final String lengthText, final long size)
		throws UnsatisfiableRangeException {

		final Optional<Long> length = number(lengthText);

		if (length.isEmpty()) {
			return Optional.empty();
		}

		final long count = length.get();

		if (count == 0 || size == 0) {
			throw new UnsatisfiableRangeException(size);
		}

		return Optional.of(new ByteRange(Math.max(0, size - count), size - 1));
	}

	private static Optional<ByteRange> range(final long first, final long last, final long size)
		throws UnsatisfiableRangeException {

		if (size == 0 || first >= size) {
			throw new UnsatisfiableRangeException(size);
		}

		return Optional.of(new ByteRange(first, Math.min(last, size - 1)));
	}

	private static Optional<Long> number(final String text) {
		if (text.isEmpty() || !text.chars().allMatch(ByteRange::isDigit)) {
			return Optional.empty();
		}

		try {
			return Optional.of(Long.parseLong(text));
		} catch (NumberFormatException e) {
			return Optional.empty();
		}
	}

	private static boolean isDigit(final int character) {
		return character >= '0' && character <= '9';
	}
}

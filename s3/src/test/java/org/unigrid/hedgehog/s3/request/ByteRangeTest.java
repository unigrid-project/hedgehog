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

import java.util.Arrays;
import java.util.Optional;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.LongRange;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.unigrid.hedgehog.s3.jqwik.Expect.assertThrows;

public class ByteRangeTest {
	private static final String PREFIX = "bytes=";
	private static final long MAX_SIZE = 1_000_000;

	@Property
	public void parsesClosedRangesAgainstTheReference(@ForAll @LongRange(max = MAX_SIZE) final long size,
		@ForAll @LongRange(max = MAX_SIZE) final long first, @ForAll @LongRange(max = MAX_SIZE) final long last)
		throws Exception {

		final String header = PREFIX + first + "-" + last;

		if (first > last) {
			assertThat(ByteRange.parse(header, size), equalTo(Optional.empty()));
		} else if (size == 0 || first >= size) {
			assertThat(assertThrows(UnsatisfiableRangeException.class,
				() -> ByteRange.parse(header, size)).getSize(), equalTo(size));
		} else {
			assertThat(ByteRange.parse(header, size),
				equalTo(Optional.of(new ByteRange(first, Math.min(last, size - 1)))));
		}
	}

	@Property
	public void parsesOpenEndedRangesAgainstTheReference(@ForAll @LongRange(max = MAX_SIZE) final long size,
		@ForAll @LongRange(max = MAX_SIZE) final long first) throws Exception {

		final String header = PREFIX + first + "-";

		if (size == 0 || first >= size) {
			assertThat(assertThrows(UnsatisfiableRangeException.class,
				() -> ByteRange.parse(header, size)).getSize(), equalTo(size));
		} else {
			assertThat(ByteRange.parse(header, size), equalTo(Optional.of(new ByteRange(first, size - 1))));
		}
	}

	@Property
	public void parsesSuffixRangesAgainstTheReference(@ForAll @LongRange(max = MAX_SIZE) final long size,
		@ForAll @LongRange(max = MAX_SIZE) final long count) throws Exception {

		final String header = PREFIX + "-" + count;

		if (count == 0 || size == 0) {
			assertThat(assertThrows(UnsatisfiableRangeException.class,
				() -> ByteRange.parse(header, size)).getSize(), equalTo(size));
		} else {
			assertThat(ByteRange.parse(header, size),
				equalTo(Optional.of(new ByteRange(Math.max(0, size - count), size - 1))));
		}
	}

	@Property
	public void lengthCountsBothEnds(@ForAll @LongRange(max = MAX_SIZE) final long first,
		@ForAll @LongRange(max = MAX_SIZE) final long extra) {

		assertThat(new ByteRange(first, first + extra).length(), equalTo(extra + 1));
	}

	@Example
	public void rejectsAnyRangeOnAnEmptyObject() {
		for (final String header : Arrays.asList("bytes=0-0", "bytes=0-", "bytes=-1", "bytes=-0")) {
			assertThat(assertThrows(UnsatisfiableRangeException.class,
				() -> ByteRange.parse(header, 0)).getSize(), equalTo(0L));
		}
	}

	@Property
	public void rejectsAZeroSuffix(@ForAll @LongRange(min = 1, max = MAX_SIZE) final long size) {
		assertThat(assertThrows(UnsatisfiableRangeException.class,
			() -> ByteRange.parse("bytes=-0", size)).getSize(), equalTo(size));
	}

	@Example
	public void ignoresMalformedAndMultipleRanges() throws Exception {
		for (final String header : Arrays.asList(null, "", "   ", "BYTES=0-1", PREFIX, PREFIX + "-",
			"bytes=1", "bytes=1-2-3", "bytes=a-b", "bytes=+1-2", "bytes=1.5-2", "bytes=0-1,3-4",
			"bytes=0-1,", "bytes=0-1 ", "bytes= 0-1")) {

			assertThat(ByteRange.parse(header, 100), equalTo(Optional.empty()));
		}
	}

	@Example
	public void ignoresNumbersBeyondLong() throws Exception {
		for (final String header : Arrays.asList("bytes=9223372036854775808-", "bytes=0-9223372036854775808",
			"bytes=-9223372036854775808", "bytes=9223372036854775808-9223372036854775808")) {

			assertThat(ByteRange.parse(header, 100), equalTo(Optional.empty()));
		}
	}
}

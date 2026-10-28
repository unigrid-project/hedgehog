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

package org.unigrid.hedgehog.proxy.chain;

import net.jqwik.api.Assume;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.Size;
import org.unigrid.hedgehog.proxy.Expect;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.not;

public class IdentifiersTest {
	@Property(tries = 100)
	public void shouldEqualAnIdOfTheSameBytes(@ForAll @Size(ChainId.SIZE) byte[] bytes) {
		assertThat(new ChainId(bytes), equalTo(new ChainId(bytes.clone())));
		assertThat(new ChainId(bytes).hashCode(), equalTo(new ChainId(bytes.clone()).hashCode()));
	}

	@Property(tries = 100)
	public void shouldNotEqualAnotherKindOfId(@ForAll @Size(ChainId.SIZE) byte[] bytes) {
		assertThat(new WriterId(bytes), not(equalTo((Object) new ChainId(bytes))));
	}

	@Property(tries = 100)
	public void shouldKeepItsOwnCopy(@ForAll @Size(WriterId.SIZE) byte[] bytes) {
		final byte[] original = bytes.clone();
		final WriterId writer = new WriterId(bytes);

		bytes[0]++;
		writer.bytes()[1]++;
		assertThat(writer.bytes(), equalTo(original));
	}

	@Property(tries = 100)
	public void shouldRefuseAnotherSize(@ForAll @Size(max = 40) byte[] bytes) {
		Assume.that(bytes.length != Tag.SIZE);

		final IllegalArgumentException thrown = Expect.assertThrows(IllegalArgumentException.class,
			() -> new Tag(bytes));

		assertThat(thrown.getMessage(), is("Tag is exactly 16 bytes, found " + bytes.length));
	}

	@Example
	public void shouldOrderWritersByUnsignedBytes() {
		final byte[] low = new byte[WriterId.SIZE];
		final byte[] high = new byte[WriterId.SIZE];

		low[0] = 0x7f;
		high[0] = (byte) 0x80;
		assertThat(new WriterId(low).compareTo(new WriterId(high)), lessThan(0));
	}

	@Example
	public void shouldPrintAsHex() {
		final byte[] bytes = new byte[ChainId.SIZE];

		bytes[0] = (byte) 0xab;
		assertThat(new ChainId(bytes).toString(), is("ab" + "0".repeat(2 * ChainId.SIZE - 2)));
		assertThat(new Tag(new byte[Tag.SIZE]).toString(), is("0".repeat(2 * Tag.SIZE)));
	}
}

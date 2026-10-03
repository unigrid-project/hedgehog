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

import net.jqwik.api.Assume;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;
import static org.unigrid.hedgehog.ledger.Expect.assertThrows;

public class ReferenceTest {
	@Property
	public void shouldBeEqualByContent(@ForAll @Size(32) byte[] bytes) {
		assertThat(new Reference(bytes), equalTo(new Reference(bytes.clone())));
		assertThat(new Reference(bytes).hashCode(), equalTo(new Reference(bytes.clone()).hashCode()));
	}

	@Property
	public void shouldRefuseEverySizeButItsOwn(@ForAll @IntRange(min = 0, max = 64) int size) {
		Assume.that(size != Reference.SIZE);
		assertThrows(IllegalArgumentException.class, () -> new Reference(new byte[size]));
	}

	@Property
	public void shouldNotExposeItsBytes(@ForAll @Size(32) byte[] bytes) {
		final Reference reference = new Reference(bytes);

		bytes[0]++;
		reference.bytes()[1]++;
		assertThat(reference.bytes()[0], not(equalTo(bytes[0])));
		assertThat(reference.bytes(), not(sameInstance(reference.bytes())));
		assertThat(new Reference(reference.bytes()), equalTo(reference));
	}

	@Example
	public void shouldOrderByUnsignedBytes() {
		final byte[] low = new byte[Reference.SIZE];
		final byte[] high = new byte[Reference.SIZE];

		high[0] = (byte) 0x80;
		assertThat(new Reference(low).compareTo(new Reference(high)), lessThan(0));
	}
}

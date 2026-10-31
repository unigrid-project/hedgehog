/*
    Unigrid Hedgehog
    Copyright © 2021-2026 Stiftelsen The Unigrid Foundation

    This program is free software: you can redistribute it and/or modify it under the terms of the
    addended GNU Affero General Public License as published by the The Unigrid Foundation and
    the Free Software Foundation, version 3 of the License (see COPYING and COPYING.addendum).

    This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
    even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU Affero General Public License and the addendum for more details.

    You should have received an addended copy of the GNU Affero General Public License with this program.
    If not, see <http://www.gnu.org/licenses/> and <https://github.com/unigrid-project/hedgehog>.
 */

package org.unigrid.hedgehog.common.crypto;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

public class DigestsTest {
	private static final String ABC = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
	private static final String EMPTY = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

	private static byte[] bytes(String text) {
		return text.getBytes(StandardCharsets.UTF_8);
	}

	@Example
	public void shouldMatchTheKnownVectorForAbc() {
		assertThat(HexFormat.of().formatHex(Digests.sha256(bytes("abc"))), equalTo(ABC));
	}

	@Example
	public void shouldHashNothingAsTheEmptyInput() {
		assertThat(HexFormat.of().formatHex(Digests.sha256()), equalTo(EMPTY));
		assertThat(HexFormat.of().formatHex(Digests.sha256(new byte[0])), equalTo(EMPTY));
	}

	@Example
	public void shouldTreatPartsAsOneConcatenatedInput() {
		assertThat(HexFormat.of().formatHex(Digests.sha256(bytes("a"), bytes("bc"))), equalTo(ABC));
	}

	@Example
	public void shouldHandOutIndependentDigests() {
		final var first = Digests.newSha256();
		final var second = Digests.newSha256();

		first.update(bytes("abc"));
		assertThat(HexFormat.of().formatHex(second.digest()), equalTo(EMPTY));
		assertThat(HexFormat.of().formatHex(first.digest()), equalTo(ABC));
	}
}

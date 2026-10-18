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

package org.unigrid.hedgehog.model.crypto;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import java.nio.charset.StandardCharsets;
import net.jqwik.api.Example;

public class ReleaseKeyTest {
	private static final byte[] DATA = "0123abcd  bootstrap.dat.gz\n".getBytes(StandardCharsets.US_ASCII);

	@Example
	public void shouldAcceptASignatureByTheReleaseKey() {
		assertThat(ReleaseKey.verify(DATA, ReleaseKeyFixture.trusted().sign(DATA)), equalTo(true));
	}

	@Example
	public void shouldRefuseASignatureByAnotherKey() {
		ReleaseKeyFixture.trusted();
		assertThat(ReleaseKey.verify(DATA, ReleaseKeyFixture.foreign().sign(DATA)), equalTo(false));
	}

	@Example
	public void shouldRefuseASignatureOverOtherData() {
		final byte[] signature = ReleaseKeyFixture.trusted().sign(DATA);

		assertThat(ReleaseKey.verify("tampered".getBytes(StandardCharsets.US_ASCII), signature), equalTo(false));
	}

	@Example
	public void shouldRefuseSomethingThatIsNoSignature() {
		ReleaseKeyFixture.trusted();
		assertThat(ReleaseKey.verify(DATA, DATA), equalTo(false));
	}
}

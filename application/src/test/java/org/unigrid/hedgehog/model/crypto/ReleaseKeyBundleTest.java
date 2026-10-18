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
import java.util.HexFormat;
import net.jqwik.api.Example;

/* Apart from the tests that swap the release key, so it always sees the one the build bundles. */
public class ReleaseKeyBundleTest {
	private static final String FINGERPRINT = "A1CB0037B3B92D595FA1536C95A98E888B0BA5D9";

	@Example
	public void shouldBundleTheFoundationReleaseKey() {
		final byte[] fingerprint = ReleaseKey.getPublicKeyRing().iterator().next().getPublicKey().getFingerprint();

		assertThat(HexFormat.of().withUpperCase().formatHex(fingerprint), equalTo(FINGERPRINT));
	}
}

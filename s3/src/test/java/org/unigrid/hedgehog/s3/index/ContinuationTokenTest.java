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

import java.util.Base64;
import java.util.Optional;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;

public class ContinuationTokenTest {
	@Provide
	Arbitrary<String> keys() {
		return IndexArbitraries.texts();
	}

	@Property
	public void readsBackTheKeyItEncodes(@ForAll("keys") final String key) {
		assertThat(ContinuationToken.decode(ContinuationToken.encode(key)), equalTo(Optional.of(key)));
		assertThat(ContinuationToken.encode(key), matchesPattern("[A-Za-z0-9_-]*"));
	}

	@Example
	public void refusesTokensItNeverHandedOut() {
		final String invalidUtf8 = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[] {(byte) 0xC0});

		for (String token : new String[] {"*", "a b", invalidUtf8}) {
			assertThat(ContinuationToken.decode(token), equalTo(Optional.empty()));
		}
	}
}

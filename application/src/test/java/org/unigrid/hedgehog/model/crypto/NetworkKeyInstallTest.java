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

import java.util.List;
import java.util.Set;
import lombok.SneakyThrows;
import mockit.Mock;
import mockit.MockUp;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeTry;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.arrayContainingInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

public class NetworkKeyInstallTest {
	private static final List<String> BUILT_IN = List.of(publicKey(), publicKey());
	private static final String ADDED = publicKey();

	@SneakyThrows
	private static String publicKey() {
		return new Signature().getPublicKey();
	}

	@BeforeTry
	public void before() {
		new MockUp<NetworkKey>() {
			@Mock public String[] getBuiltInKeys() {
				return BUILT_IN.toArray(String[]::new);
			}
		};
	}

	@AfterTry
	public void after() {
		NetworkKey.reset();
	}

	private static ActiveKeys afterOneChange() {
		return ActiveKeys.of(BUILT_IN).after(List.of(BUILT_IN.get(1), ADDED));
	}

	@Example
	public void shouldGoByTheBuiltInKeysWhileNothingIsInstalled() {
		assertThat(NetworkKey.getPublicKeys(), arrayContainingInAnyOrder(BUILT_IN.toArray()));
		assertThat(NetworkKey.getLogKeys(), equalTo(Set.copyOf(BUILT_IN)));
		assertThat(NetworkKey.getActiveKeys(), equalTo(ActiveKeys.of(BUILT_IN)));
	}

	@Example
	public void shouldSignWithTheActiveKeysAndReadLogsOfEveryKeyThatWasActive() {
		assertThat(NetworkKey.install(afterOneChange()), is(true));
		assertThat(NetworkKey.getPublicKeys(), arrayContainingInAnyOrder(BUILT_IN.get(1), ADDED));
		assertThat(NetworkKey.getLogKeys(), equalTo(Set.of(BUILT_IN.get(0), BUILT_IN.get(1), ADDED)));
	}

	@Example
	public void shouldInstallOnlyALongerChain() {
		final ActiveKeys other = ActiveKeys.of(BUILT_IN).after(List.of(BUILT_IN.get(0), ADDED));

		assertThat(NetworkKey.install(ActiveKeys.of(BUILT_IN)), is(false));
		assertThat(NetworkKey.install(afterOneChange()), is(true));
		assertThat(NetworkKey.install(other), is(false));
		assertThat(NetworkKey.getActiveKeys(), equalTo(afterOneChange()));
		assertThat(NetworkKey.install(afterOneChange().after(List.of(ADDED, BUILT_IN.get(0)))), is(true));
		assertThat(NetworkKey.getActiveKeys().length(), is(2));
	}

	@Example
	@SneakyThrows
	public void shouldTrustOnlyAnActiveKey() {
		final Signature added = new Signature();

		assertThat(NetworkKey.isTrusted(added.getPrivateKey()), is(false));
		NetworkKey.install(ActiveKeys.of(BUILT_IN).after(List.of(BUILT_IN.get(1), added.getPublicKey())));
		assertThat(NetworkKey.isTrusted(added.getPrivateKey()), is(true));
	}
}

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

package org.unigrid.hedgehog.model.spork;

import java.security.GeneralSecurityException;
import java.util.List;
import java.util.Optional;
import mockit.Mock;
import mockit.MockUp;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.BeforeTry;
import net.jqwik.api.lifecycle.PropagationMode;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import org.unigrid.hedgehog.jqwik.MockitHook;
import org.unigrid.hedgehog.model.crypto.Signature;
import org.unigrid.hedgehog.model.crypto.VerifySignatureException;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.buildIn;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.change;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.keys;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.rootOf;

/* What a node remembers of the changes it verified is bounded, and stays of use: a peer that sends copies of a change
   signed again, each one of other bytes, pushes out the oldest of them and no more. The release builds in keys 0 and
   1, and every change adds key 2. */
@AddLifecycleHook(value = MockitHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class BoardKeyChainMemoryTest {
	private static final byte[] ROOT = rootOf(keys(0, 1));

	private static int checks;

	@BeforeTry
	public void before() {
		buildIn(keys(0, 1));
		checks = 0;

		/* Each check is made here, by a key of its own, as a fake that handed it on would run into itself */
		new MockUp<Signature>() {
			@Mock boolean isSignedBy(String key, byte[] data, byte[] signature) {
				checks++;

				try {
					return new Signature(Optional.empty(), Optional.of(key)).verify(data, signature);
				} catch (GeneralSecurityException | VerifySignatureException | RuntimeException ex) {
					return false;
				}
			}
		};
	}

	private static KeyChange adding2() {
		return change(ROOT, List.of(), keys(0, 1, 2), keys(0, 1, 2));
	}

	private static int checksToVerify(KeyChange change) {
		final int before = checks;

		assertThat(BoardKeyChain.verified(List.of(change)).isPresent(), is(true));
		return checks - before;
	}

	@Example
	public void shouldRememberAChangeThatVerifiedAfterItWasFilledWithOthers() {
		for (int i = 0; i < BoardKeyChain.MAX_REMEMBERED; i++) {
			checksToVerify(adding2());
		}

		final KeyChange change = adding2();

		assertThat(checksToVerify(change), is(change.getApprovals().size()));
		assertThat(checksToVerify(change), is(0));
	}
}

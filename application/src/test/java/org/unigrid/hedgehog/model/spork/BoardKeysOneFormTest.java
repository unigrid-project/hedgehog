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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeTry;
import net.jqwik.api.lifecycle.PropagationMode;
import org.apache.commons.lang3.SerializationUtils;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import org.unigrid.hedgehog.jqwik.MockitHook;
import org.unigrid.hedgehog.model.crypto.CheckMeter;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.crypto.Signature;
import org.unigrid.hedgehog.model.crypto.SignatureForms;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.buildIn;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.chainOf;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.change;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.key;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.keys;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.rootOf;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.signed;

/* The approvals of a change of the board keys and the heads of the board keys spork are signed in the one form of a
   signature, and taken in no other, so that whoever holds one makes no copy of other bytes that decides the same. The
   heads of the other types are taken in either form, as earlier releases signed them in both. The release builds in
   keys 0 and 1, and the change adds key 2. */
@AddLifecycleHook(value = MockitHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class BoardKeysOneFormTest {
	private KeyChange adding;

	@BeforeTry
	public void before() {
		buildIn(keys(0, 1));
		adding = change(rootOf(keys(0, 1)), List.of(), keys(0, 1, 2), keys(0, 1, 2));
	}

	@AfterTry
	public void after() {
		NetworkKey.reset();
	}

	private static int checksOf(BooleanSupplier work) {
		final AtomicInteger checks = new AtomicInteger();

		CheckMeter.charging(() -> checks.incrementAndGet() > 0, work::getAsBoolean);
		return checks.get();
	}

	@Example
	public void shouldApproveAChangeInTheOneFormOfASignature() {
		assertThat(adding.getApprovals().stream().allMatch(approval -> Signature.isInOneForm(
			approval.getSignature())), is(true));
	}

	@Example
	public void shouldRefuseAnApprovalInAnotherFormBeforeAnyIsChecked() {
		final List<KeyChange.Approval> approvals = new ArrayList<>(adding.getApprovals());
		final KeyChange.Approval approval = approvals.get(1);

		approvals.set(1, new KeyChange.Approval(approval.getKey(), SignatureForms.withTheOtherS(
			approval.getSignature())));

		final KeyChange other = new KeyChange(adding.getSequence(), adding.getMadeAt(), adding.getPrevious(),
			new ArrayList<>(adding.getActive()), approvals);
		final List<String> builtIn = List.of(NetworkKey.getBuiltInKeys());
		final byte[] root = rootOf(keys(0, 1));

		assertThat(checksOf(() -> BoardKeyChain.verified(root, builtIn, List.of(other)).isPresent()), is(0));
		assertThat(assertThrows(IllegalArgumentException.class, () -> BoardKeyChain.verify(root, builtIn,
			List.of(other))).getMessage(), containsString("one form"));
		assertThat(BoardKeyChain.verified(root, builtIn, List.of(adding)).isPresent(), is(true));
	}

	@Example
	public void shouldTakeTheHeadOfTheBoardKeysInItsOneFormOnly() {
		final BoardKeys spork = signed(chainOf(adding), key(0), key(1));
		final BoardKeys other = SerializationUtils.clone(spork);

		other.setCosignature(SignatureForms.withTheOtherS(spork.getCosignature()));

		assertThat(Signature.isInOneForm(spork.getSignature()) && Signature.isInOneForm(spork.getCosignature()),
			is(true));
		assertThat(SerializationUtils.clone(spork).isDoublySigned(), is(true));
		assertThat(other.isDoublySigned(), is(false));
	}

	@Example
	@SneakyThrows
	public void shouldTakeTheHeadOfAnotherTypeInEitherForm() {
		final GridSpork spork = GridSpork.nextVersion(Type.MINT_SUPPLY, null, Optional.empty());

		spork.<MintSupply.SporkData>getData().setMaxSupply(BigDecimal.TEN);
		spork.sign(key(0).getPrivateKey());
		spork.cosign(key(1).getPrivateKey());
		spork.setCosignature(SignatureForms.withTheOtherS(spork.getCosignature()));

		assertThat(spork.isDoublySigned(), is(true));
	}
}

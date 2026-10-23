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

package org.unigrid.hedgehog.command.cli;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.stream.Stream;
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeTry;
import net.jqwik.api.lifecycle.PropagationMode;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import org.unigrid.hedgehog.command.util.FakeSporkNode;
import org.unigrid.hedgehog.command.util.RestCommandFixture;
import org.unigrid.hedgehog.command.util.RestCommandFixture.Result;
import org.unigrid.hedgehog.jqwik.MockitHook;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.spork.BoardKeys;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;
import org.unigrid.hedgehog.model.spork.MintSupply;
import org.unigrid.hedgehog.model.spork.PendingSporks;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.key;
import static org.unigrid.hedgehog.model.spork.ForgedVersionTest.forgedOver;
import static org.unigrid.hedgehog.model.spork.ForgedVersionTest.genuine;
import static org.unigrid.hedgehog.model.spork.ForgedVersionTest.installTheBoardKeys;
import static org.unigrid.hedgehog.model.spork.ForgedVersionTest.renewalOf;
import picocli.CommandLine;

/* What a co-signer's command line takes over a version that two keys signed which the board switched off since: keys 0
   and 1 are switched off, keys 2 and 3 are active, and key 3 co-signs */
@AddLifecycleHook(value = MockitHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class ForgedVersionCommandTest {
	private BoardKeys boardKeys;

	@BeforeTry
	public void before() {
		boardKeys = installTheBoardKeys();
		SporkCommands.environmentKey(null);
	}

	@AfterTry
	public void after() {
		NetworkKey.reset();
	}

	private static GridSporkCosign cosign(GridSpork proposal, String... switchedOff) {
		final GridSporkCosign command = new GridSporkCosign();

		new CommandLine(command).parseArgs(Stream.concat(Stream.of("-k", key(3).getPrivateKey(),
			PendingSporks.digestOf(proposal)), Stream.of(switchedOff).flatMap(type -> Stream.of("--switched-off",
			type))).toArray(String[]::new));
		return command;
	}

	@SneakyThrows
	private static GridSpork nextVersionOf(GridSpork stored) {
		final GridSpork next = GridSpork.nextVersion(Type.MINT_SUPPLY, stored, Optional.empty());

		next.<MintSupply.SporkData>getData().setMaxSupply(BigDecimal.TWO);
		next.sign(key(2).getPrivateKey());
		return next;
	}

	@Example
	public void coSignsNoRenewalOfAForgedVersionOverTheStoredSpork() {
		final GridSpork stored = genuine();
		final GridSpork laundered = renewalOf(forgedOver(stored), key(2));
		final FakeSporkNode node = new FakeSporkNode().holding(boardKeys, stored).handingOut(laundered);
		final Result result = RestCommandFixture.run(cosign(laundered), node);

		assertThat(result.out(), allOf(containsString("Co-signing " + PendingSporks.digestOf(laundered)
			+ " refused"), containsString("not one that a current board key made from the spork it holds")));
		assertThat(node.submitted(), empty());
		assertThat(node.stored(Type.MINT_SUPPLY), equalTo(stored));
	}

	/* Over a version that switched-off keys signed, which may be forged, only its renewal is signed again */
	@Example
	public void coSignsNothingButARenewalOverASporkThatSwitchedOffKeysSigned() {
		final GridSpork forged = forgedOver(genuine());
		final GridSpork next = nextVersionOf(forged);
		final FakeSporkNode node = new FakeSporkNode().holding(boardKeys, forged).waitingOn(next);
		final Result refused = RestCommandFixture.run(cosign(next, Type.MINT_SUPPLY.name()), node);

		assertThat(refused.out(), allOf(containsString("Co-signing " + PendingSporks.digestOf(next) + " refused"),
			containsString("no renewal")));
		assertThat(node.submitted(), empty());

		final GridSpork renewal = renewalOf(forged, key(2));
		final FakeSporkNode renewing = new FakeSporkNode().holding(boardKeys, forged).waitingOn(renewal);
		final Result renewed = RestCommandFixture.run(cosign(renewal, Type.MINT_SUPPLY.name()), renewing);

		assertThat(renewed.out(), containsString("Co-signed " + PendingSporks.digestOf(renewal)));
		assertThat(renewing.stored(Type.MINT_SUPPLY).isCounting(), is(true));
	}
}

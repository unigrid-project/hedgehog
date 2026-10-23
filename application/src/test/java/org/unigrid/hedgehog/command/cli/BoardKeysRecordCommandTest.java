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

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.nio.file.Path;
import java.util.List;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeTry;
import net.jqwik.api.lifecycle.PropagationMode;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import org.unigrid.hedgehog.command.util.FakeSporkNode;
import org.unigrid.hedgehog.command.util.RestCommandFixture;
import org.unigrid.hedgehog.command.util.RestCommandFixture.Result;
import org.unigrid.hedgehog.jqwik.MockitHook;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.crypto.Signature;
import org.unigrid.hedgehog.model.spork.KeyChange;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.buildIn;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.chainOf;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.change;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.key;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.keys;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.rootOf;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.signed;
import picocli.CommandLine;

/* The command line keeps the longest chain of board keys that it verified, so a node that hides a change of the board
   cannot take it back to keys that the board switched off. The release builds in keys 0 and 1; the first change adds
   key 2 and the second switches key 0 off, while another first change adds key 3. */
@AddLifecycleHook(value = MockitHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class BoardKeysRecordCommandTest {
	private static final byte[] ROOT = rootOf(keys(0, 1));
	private static final KeyChange ADDING = change(ROOT, List.of(), keys(0, 1, 2), keys(0, 1, 2));
	private static final KeyChange SWITCHING_OFF = change(ROOT, List.of(ADDING), keys(1, 2), keys(1, 2));
	private static final KeyChange ELSEWHERE = change(ROOT, List.of(), keys(0, 1, 3), keys(0, 1, 3));
	private static final String NOTHING = "ab".repeat(64);
	private static final String NOTHING_AWAITS = "No spork awaits a co-signature under " + NOTHING;

	private Path directory;

	@BeforeTry
	public void before() {
		buildIn(keys(0, 1));
		SporkCommands.environmentKey(null);
		directory = Jimfs.newFileSystem(Configuration.unix()).getPath("/data");
	}

	@AfterTry
	public void after() {
		NetworkKey.reset();
	}

	private static FakeSporkNode nodeOf(Signature signer, Signature cosigner, KeyChange... changes) {
		return new FakeSporkNode().holding(signed(chainOf(changes), signer, cosigner));
	}

	/* A command that goes by the board keys of the node and that finds nothing to co-sign */
	private Result cosignOn(FakeSporkNode node) {
		final GridSporkCosign command = new GridSporkCosign();

		new CommandLine(command).parseArgs("-k", key(1).getPrivateKey(), NOTHING);
		return RestCommandFixture.run(command, node, directory);
	}

	@Example
	public void recordsTheFirstChainThatItGoesByAndSaysSoOnce() {
		final Result first = cosignOn(nodeOf(key(0), key(1), ADDING));
		final Result again = cosignOn(nodeOf(key(0), key(1), ADDING));

		assertThat(first.out(), allOf(containsString("Recorded the chain of 1 change"), containsString(NOTHING_AWAITS)));
		assertThat(again.out(), equalTo(NOTHING_AWAITS + "\n"));
		assertThat(again.err(), equalTo(""));
	}

	@Example
	public void refusesANodeOfNoBoardKeysOfFewerChangesOrOfAnotherChainOnceOneIsRecorded() {
		cosignOn(nodeOf(key(1), key(2), ADDING, SWITCHING_OFF));

		for (FakeSporkNode node : List.of(new FakeSporkNode(), nodeOf(key(0), key(1), ADDING),
			nodeOf(key(0), key(1), ELSEWHERE))) {

			final Result refused = cosignOn(node);

			assertThat(refused.err(), allOf(containsString("Nothing was co-signed"), containsString("the chain of 2"
				+ " changes that this machine verified before"), containsString("only to start over")));
			assertThat(refused.out(), not(containsString(NOTHING_AWAITS)));
		}
	}

	@Example
	public void recordsALongerChainInPlaceOfTheOneBeforeIt() {
		cosignOn(nodeOf(key(0), key(1), ADDING));

		final Result longer = cosignOn(nodeOf(key(1), key(2), ADDING, SWITCHING_OFF));

		assertThat(longer.out(), equalTo(NOTHING_AWAITS + "\n"));
		assertThat(cosignOn(nodeOf(key(0), key(1), ADDING)).err(), containsString("1 change of the board keys,"
			+ " fewer than the chain of 2 changes"));
	}
}

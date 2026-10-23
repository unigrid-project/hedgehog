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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.BeforeTry;
import net.jqwik.api.lifecycle.PropagationMode;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import org.unigrid.hedgehog.jqwik.MockitHook;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.approve;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.buildIn;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.change;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.keys;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.rootOf;

/* The release builds in keys 0 and 1; the first change adds key 2 and the second switches key 0 off, while another
   first change adds key 3 */
@AddLifecycleHook(value = MockitHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class BoardKeysRecordTest {
	private static final byte[] ROOT = rootOf(keys(0, 1));
	private static final KeyChange ADDING = change(ROOT, List.of(), keys(0, 1, 2), keys(0, 1, 2));
	private static final KeyChange SWITCHING_OFF = change(ROOT, List.of(ADDING), keys(1, 2), keys(1, 2));
	private static final KeyChange ELSEWHERE = change(ROOT, List.of(), keys(0, 1, 3), keys(0, 1, 3));

	private Path directory;

	@BeforeTry
	public void before() {
		buildIn(keys(0, 1));
		directory = BoardKeysRecordFixture.inMemory();
	}

	private static Optional<List<KeyChange>> chain(KeyChange... changes) {
		return Optional.of(List.of(changes));
	}

	@SneakyThrows
	private static Optional<String> keep(Optional<List<KeyChange>> chain) {
		return BoardKeysRecord.ofThisMachine().keep(chain, "the node");
	}

	@SneakyThrows
	private static List<KeyChange> recorded() {
		return BoardKeysRecord.ofThisMachine().read().orElseThrow();
	}

	@Example
	public void shouldRecordTheFirstChainAndSaySo() {
		assertThat(keep(Optional.empty()), is(Optional.empty()));
		assertThat(keep(chain()), is(Optional.empty()));
		assertThat(Files.exists(BoardKeysRecord.ofThisMachine().getFile()), is(false));
		assertThat(keep(chain(ADDING)).orElseThrow(), allOf(containsString("1 change"),
			containsString(BoardKeysRecord.ofThisMachine().getFile().toString())));
		assertThat(BoardKeysRecord.ofThisMachine().getFile().getParent(), equalTo(directory));
		assertThat(recorded().size(), is(1));
	}

	@Example
	public void shouldRefuseNoChainAShorterOneAndAnotherOnceOneIsRecorded() {
		keep(chain(ADDING, SWITCHING_OFF));

		for (Optional<List<KeyChange>> held : List.of(Optional.<List<KeyChange>>empty(), chain(), chain(ADDING),
			chain(ELSEWHERE))) {

			final RecordedChainException refused = assertThrows(RecordedChainException.class,
				() -> BoardKeysRecord.ofThisMachine().keep(held, "the node"));

			assertThat(refused.getMessage(), allOf(containsString("The node holds"), containsString("the chain of 2"),
				containsString(BoardKeysRecord.ofThisMachine().getFile().toString()),
				containsString("start over")));
		}

		assertThat(recorded().size(), is(2));
	}

	@Example
	public void shouldRefuseAChainThatGoesOnFromAnotherFirstChange() {
		keep(chain(ADDING));

		final KeyChange afterElsewhere = change(ROOT, List.of(ELSEWHERE), keys(0, 3), keys(0, 1, 3));

		assertThrows(RecordedChainException.class,
			() -> BoardKeysRecord.ofThisMachine().keep(chain(ELSEWHERE, afterElsewhere), "the node"));
		assertThat(recorded().size(), is(1));
	}

	@Example
	public void shouldTakeTheSameChainWithOtherApprovalsAndRecordALongerOne() {
		keep(chain(ADDING));

		final KeyChange reapproved = new KeyChange(ADDING.getSequence(), ADDING.getMadeAt(), ADDING.getPrevious(),
			new ArrayList<>(ADDING.getActive()), new ArrayList<>());

		approve(reapproved, keys(0, 1, 2));
		assertThat(keep(chain(reapproved)), is(Optional.empty()));
		assertThat(keep(chain(ADDING, SWITCHING_OFF)), is(Optional.empty()));
		assertThat(recorded().size(), is(2));
		assertThat(BoardKeyChain.digestOf(recorded().getLast()), equalTo(BoardKeyChain.digestOf(SWITCHING_OFF)));
	}

	/* A record of another network, of keys of its own, is kept apart */
	@Example
	@SneakyThrows
	public void shouldKeepARecordForEachRoot() {
		keep(chain(ADDING));
		buildIn(keys(4, 5));
		assertThat(keep(Optional.empty()), is(Optional.empty()));
		assertThat(BoardKeysRecord.ofThisMachine().read(), is(Optional.empty()));
	}
}

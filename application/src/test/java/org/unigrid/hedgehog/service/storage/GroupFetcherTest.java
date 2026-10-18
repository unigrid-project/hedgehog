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

package org.unigrid.hedgehog.service.storage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import org.unigrid.hedgehog.model.gridnode.Gridnode;
import org.unigrid.hedgehog.model.spork.StorageSpork;
import org.unigrid.hedgehog.model.storage.ChunkGroups;
import org.unigrid.hedgehog.model.storage.Fragment;
import org.unigrid.hedgehog.model.storage.StorageFormat;
import org.unigrid.hedgehog.model.storage.GroupKey;
import org.unigrid.hedgehog.model.storage.placement.Placement;

public class GroupFetcherTest {
	@Provide
	Arbitrary<StorageSpork.SporkData> parameters() {
		return StorageArbitraries.parameters();
	}

	private static byte[] chunk(StorageSpork.SporkData parameters, Random random) {
		final byte[] chunk = new byte[parameters.getChunkSize()];
		random.nextBytes(chunk);
		return chunk;
	}

	private static int firstRound(StorageSpork.SporkData parameters) {
		return Math.min(parameters.window(), parameters.layout().dataFragments() + 2);
	}

	private static void placeOn(StorageFleet fleet, List<Fragment> fragments, List<Gridnode> holders) {
		for (int i = 0; i < fragments.size(); i++) {
			fleet.getTransport().store(holders.get(i), fragments.get(i).encode()).join();
		}
	}

	private static List<Gridnode> placeAll(StorageFleet fleet, GroupKey key, List<Fragment> fragments) {
		final List<Gridnode> window = Placement.window(key.groupId(), fleet.getGridnodes(), fleet.getParameters().window());

		placeOn(fleet, fragments, window);
		return window;
	}

	private static List<Gridnode> someHolders(List<Gridnode> window, List<Fragment> fragments,
		StorageSpork.SporkData parameters, Random random) {

		final List<Gridnode> holders = new ArrayList<>(window.subList(0, fragments.size()));
		final int count = random.nextInt(parameters.layout().maxFragments() - parameters.layout().dataFragments() + 1);

		Collections.shuffle(holders, random);
		return holders.subList(0, count);
	}

	private static void assertRecovered(List<Fragment> fetched, GroupKey key, byte[] chunk) {
		assertThat(GroupFetcher.isComplete(fetched), is(true));
		assertThat(ChunkGroups.open(fetched), equalTo(chunk));
		assertThat(fetched.stream().map(Fragment::getIndex).distinct().count(), equalTo((long) fetched.size()));
		fetched.forEach(fragment -> assertThat(fragment.verify(key.groupId()), is(true)));
	}

	@Property(tries = 60)
	public void ignoresTamperedFragments(@ForAll("parameters") StorageSpork.SporkData parameters, @ForAll long seed) {
		final Random random = new Random(seed);
		final StorageFleet fleet = new StorageFleet(parameters, parameters.window());
		final GroupKey key = StorageTestData.key(random);
		final byte[] chunk = chunk(parameters, random);
		final List<Fragment> fragments = ChunkGroups.seal(chunk, key, StorageFormat.current(), parameters.layout());
		final List<Gridnode> window = placeAll(fleet, key, fragments);

		someHolders(window, fragments, parameters, random).forEach(gridnode -> fleet.getTransport()
			.tamper(gridnode.getId(), bytes -> {
				bytes[random.nextInt(bytes.length)] ^= (byte) (1 << random.nextInt(8));
				return bytes;
			}));

		assertRecovered(new GroupFetcher(fleet.getTransport()).fetch(key.groupId(), window,
			parameters.layout().dataFragments(), StorageFormat.current()), key, chunk);
	}

	@Property(tries = 60)
	public void ignoresForeignAndRepeatedFragments(@ForAll("parameters") StorageSpork.SporkData parameters,
		@ForAll long seed) {

		final Random random = new Random(seed);
		final StorageFleet fleet = new StorageFleet(parameters, parameters.window());
		final GroupKey key = StorageTestData.key(random);
		final byte[] chunk = chunk(parameters, random);
		final List<Fragment> fragments = ChunkGroups.seal(chunk, key, StorageFormat.current(), parameters.layout());
		final List<Fragment> foreign = ChunkGroups.seal(chunk(parameters, random), StorageTestData.key(random),
			StorageFormat.current(), parameters.layout());
		final List<byte[]> impostors = List.of(fragments.get(0).encode(), foreign.get(0).encode());
		final List<Gridnode> window = placeAll(fleet, key, fragments);

		someHolders(window, fragments, parameters, random).stream().map(Gridnode::getId).collect(Collectors.toList())
			.forEach(id -> fleet.getTransport().tamper(id, bytes -> impostors.get(random.nextInt(impostors.size()))));

		assertRecovered(new GroupFetcher(fleet.getTransport()).fetch(key.groupId(), window,
			parameters.layout().dataFragments(), StorageFormat.current()), key, chunk);
	}

	@Property(tries = 60)
	public void asksOnlyTheBestRankedWhenTheyAnswer(@ForAll("parameters") StorageSpork.SporkData parameters,
		@ForAll long seed) {

		final Random random = new Random(seed);
		final StorageFleet fleet = new StorageFleet(parameters, parameters.window());
		final GroupKey key = StorageTestData.key(random);
		final List<Fragment> fragments = ChunkGroups.seal(chunk(parameters, random), key, StorageFormat.current(),
			parameters.layout());
		final List<Gridnode> window = placeAll(fleet, key, fragments);

		new GroupFetcher(fleet.getTransport()).fetch(key.groupId(), window, parameters.layout().dataFragments(),
			StorageFormat.current());

		assertThat(fleet.getTransport().getFetches().get(), lessThanOrEqualTo(parameters.layout().dataFragments() + 2));
	}

	@Property(tries = 60)
	public void widensWhenTheBestRankedAreGone(@ForAll("parameters") StorageSpork.SporkData parameters,
		@ForAll long seed) {

		/* Only a window wider than the first round of dataFragments + 2 leaves anyone to widen to */
		parameters.setPlacementSlack(Math.max(3, parameters.getPlacementSlack()));

		final Random random = new Random(seed);
		final StorageFleet fleet = new StorageFleet(parameters, parameters.window());
		final GroupKey key = StorageTestData.key(random);
		final byte[] chunk = chunk(parameters, random);
		final List<Fragment> fragments = ChunkGroups.seal(chunk, key, StorageFormat.current(), parameters.layout());
		final List<Gridnode> window = Placement.window(key.groupId(), fleet.getGridnodes(), parameters.window());
		final int dataFragments = parameters.layout().dataFragments();

		/* Holding the group at the bottom of the window leaves too few answers among the best-ranked */
		placeOn(fleet, fragments, window.subList(window.size() - fragments.size(), window.size()));
		window.subList(0, window.size() - dataFragments).forEach(g -> fleet.getTransport().offline(g.getId()));

		final List<Fragment> fetched = new GroupFetcher(fleet.getTransport()).fetch(key.groupId(), window,
			dataFragments, StorageFormat.current());

		assertThat(ChunkGroups.open(fetched), equalTo(chunk));
		assertThat(fleet.getTransport().getFetches().get(), greaterThan(firstRound(parameters)));
	}

	@Property(tries = 60)
	public void finishesARoundWithoutWaitingForSilentGridnodes(@ForAll("parameters") StorageSpork.SporkData parameters,
		@ForAll long seed) throws Exception {

		final Random random = new Random(seed);
		final StorageFleet fleet = new StorageFleet(parameters, parameters.window());
		final GroupKey key = StorageTestData.key(random);
		final byte[] chunk = chunk(parameters, random);
		final List<Fragment> fragments = ChunkGroups.seal(chunk, key, StorageFormat.current(), parameters.layout());
		final List<Gridnode> window = placeAll(fleet, key, fragments);
		final int dataFragments = parameters.layout().dataFragments();
		final int askedHolders = Math.min(firstRound(parameters), fragments.size());
		final List<Gridnode> holders = new ArrayList<>(window.subList(0, askedHolders));

		Collections.shuffle(holders, random);
		holders.subList(0, askedHolders - dataFragments).forEach(g -> fleet.getTransport().silence(g.getId()));
		window.subList(askedHolders, window.size()).forEach(g -> fleet.getTransport().silence(g.getId()));

		final List<Fragment> fetched = CompletableFuture.supplyAsync(() -> new GroupFetcher(fleet.getTransport())
			.fetch(key.groupId(), window, dataFragments, StorageFormat.current())).get(10, TimeUnit.SECONDS);

		assertThat(fetched.size(), equalTo(dataFragments));
		assertRecovered(fetched, key, chunk);
	}
}

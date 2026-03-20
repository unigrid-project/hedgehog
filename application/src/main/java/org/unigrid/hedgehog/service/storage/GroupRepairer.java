/*
    Unigrid Hedgehog
    Copyright © 2021-2023 Stiftelsen The Unigrid Foundation, UGD Software AB

    Stiftelsen The Unigrid Foundation (org. nr: 802482-2408)
    UGD Software AB (org. nr: 559339-5824)

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

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.unigrid.hedgehog.model.gridnode.Gridnode;
import org.unigrid.hedgehog.model.network.packet.FragmentStatus;
import org.unigrid.hedgehog.model.spork.StorageSpork;
import org.unigrid.hedgehog.model.storage.ChunkGroups;
import org.unigrid.hedgehog.model.storage.DeleteProof;
import org.unigrid.hedgehog.model.storage.Fragment;
import org.unigrid.hedgehog.model.storage.GroupDescriptor;
import org.unigrid.hedgehog.model.storage.GroupId;
import org.unigrid.hedgehog.model.storage.LayoutParameters;
import org.unigrid.hedgehog.model.storage.placement.GridnodeDirectory;
import org.unigrid.hedgehog.model.storage.placement.Placement;
import org.unigrid.hedgehog.model.storage.store.FragmentStore;

/* Every round waits on replies that arrive on the Netty event loops, so only the repair executor may run one */
@Slf4j
@RequiredArgsConstructor
public class GroupRepairer {
	private final FragmentStore store;
	private final GridnodeDirectory directory;
	private final FragmentTransport transport;
	private final Supplier<Optional<StorageSpork.SporkData>> spork;
	private final Clock clock;

	/* One holder per group and epoch does the probing, so a group costs O(window) messages per interval,
	   and a dead duty holder only delays the check by one epoch. */
	public static boolean isDuty(final GroupId groupId, final int rank, final long epoch, final int width) {
		return rank == Math.floorMod(epoch + Integer.toUnsignedLong(groupId.prefix()), width);
	}

	public static boolean needsRepair(final GroupDescriptor descriptor, final int present, final int thresholdPercent) {
		final int missing = descriptor.guaranteedFragments() - present;
		return missing >= Math.max(1, LayoutParameters.percentOf(descriptor.getParityFragments(), thresholdPercent));
	}

	public OptionalLong currentEpoch() {
		return spork.get().map(parameters -> OptionalLong.of(epochOf(parameters))).orElse(OptionalLong.empty());
	}

	public void runEpoch() {
		final Optional<StorageSpork.SporkData> parameters = spork.get();
		final Optional<String> self = directory.self();

		if (parameters.isEmpty() || self.isEmpty()) {
			return;
		}

		final Round round = new Round(parameters.get(), directory.active(), self.get(), epochOf(parameters.get()));

		store.groups().forEach(round::tend);
		purgeTombstones();
	}

	private long epochOf(final StorageSpork.SporkData parameters) {
		return clock.millis() / Duration.ofMinutes(parameters.getRepairIntervalMinutes()).toMillis();
	}

	private void purgeTombstones() {
		try {
			store.purgeTombstones();
		} catch (IOException ex) {
			log.atWarn().log("Unable to purge storage tombstones: {}", ex.getClass().getSimpleName());
		}
	}

	@RequiredArgsConstructor
	private final class Round {
		private final StorageSpork.SporkData parameters;
		private final List<Gridnode> active;
		private final String self;
		private final long epoch;
		private final GroupFetcher fetcher = new GroupFetcher(transport);

		/* Anyone can sign a group that fails to rebuild, so one bad group must never end the round */
		void tend(final GroupId groupId) {
			try {
				tendOrFail(groupId);
			} catch (IOException | RuntimeException ex) {
				log.atDebug().log("Tending a stored group failed: {}", ex.getClass().getSimpleName());
			}
		}

		private void tendOrFail(final GroupId groupId) throws IOException {
			final Optional<FragmentStore.Holding> holding = store.holding(groupId);

			if (holding.isEmpty()) {
				return;
			}

			final int width = holding.get().getSlots() + parameters.getPlacementSlack();
			final OptionalInt rank = Placement.rankOf(groupId, active, self);

			if (rank.isEmpty() || rank.getAsInt() >= width) {
				relocate(groupId, width);
			} else if (isDuty(groupId, rank.getAsInt(), epoch, width)) {
				repair(groupId, width);
			}
		}

		private void relocate(final GroupId groupId, final int width) throws IOException {
			final Optional<Fragment> local = localFragment(groupId);

			if (local.isPresent()) {
				final List<Gridnode> window = Placement.window(groupId, active, width);
				final Map<Gridnode, FragmentStatus.Entry> census = census(local.get(), window);
				final Optional<DeleteProof> deletion = deletionOf(local.get(), census);

				if (deletion.isPresent()) {
					tombstone(groupId, deletion.get());
				} else if (deliver(local.get(), free(window, census).iterator())) {
					store.remove(groupId);
				}
			}
		}

		private void repair(final GroupId groupId, final int width) throws IOException {
			final Optional<Fragment> local = localFragment(groupId);

			if (local.isPresent()) {
				final List<Gridnode> window = Placement.window(groupId, active, width);
				final Map<Gridnode, FragmentStatus.Entry> census = census(local.get(), window);
				final Optional<DeleteProof> deletion = deletionOf(local.get(), census);
				final Set<Integer> present = present(local.get(), census);

				if (deletion.isPresent()) {
					tombstone(groupId, deletion.get());
				} else if (needsRepair(local.get().getDescriptor(), present.size(),
					parameters.getRepairThresholdPercent())) {

					rebuild(local.get(), census, present, window);
				}
			}
		}

		private void rebuild(final Fragment local, final Map<Gridnode, FragmentStatus.Entry> census,
			final Set<Integer> present, final List<Gridnode> window) {

			final GroupDescriptor descriptor = local.getDescriptor();

			if (!isAffordable(descriptor)) {
				log.atDebug().log("Skipping the repair of a group larger than the per-node quota");
				return;
			}

			final List<Gridnode> holders = census.entrySet().stream()
				.filter(entry -> entry.getValue().getState() == FragmentStatus.State.HELD)
				.map(Map.Entry::getKey).collect(Collectors.toList());
			final Map<Integer, Fragment> sources = new LinkedHashMap<>();

			sources.put(local.getIndex(), local);
			fetcher.fetch(local.groupId(), holders, descriptor.getDataFragments(), local.format())
				.forEach(fragment -> sources.putIfAbsent(fragment.getIndex(), fragment));

			if (GroupFetcher.isComplete(sources.values())) {
				final List<Integer> missing = IntStream.range(0, descriptor.getMaxFragments())
					.filter(index -> !present.contains(index)).boxed().collect(Collectors.toList());
				final Iterator<Gridnode> targets = free(window, census).iterator();

				ChunkGroups.rebuild(sources.values(), missing)
					.forEach(fragment -> deliver(fragment, targets));
			}
		}

		/* A validly signed group may still claim 255 slots of any size, so a repairer never decodes one that
		   could not even fit the quota of a single gridnode */
		private boolean isAffordable(final GroupDescriptor descriptor) {
			final long groupBytes = (long) descriptor.getMaxFragments() * descriptor.getFragmentSize();
			return groupBytes <= parameters.getMaxBytesPerNode();
		}

		private boolean deliver(final Fragment fragment, final Iterator<Gridnode> targets) {
			final byte[] encoded = fragment.encode();

			while (targets.hasNext()) {
				if (GroupDistributor.stored(transport.store(targets.next(), encoded))) {
					return true;
				}
			}

			return false;
		}

		private Optional<Fragment> localFragment(final GroupId groupId) throws IOException {
			final Optional<Fragment> fragment = store.get(groupId).flatMap(FragmentKeeper::decode)
				.filter(decoded -> decoded.groupId().equals(groupId));

			if (fragment.isEmpty()) {
				store.remove(groupId);
			}

			return fragment;
		}

		private Map<Gridnode, FragmentStatus.Entry> census(final Fragment local, final List<Gridnode> window) {
			final Map<Gridnode, CompletableFuture<FragmentStatus>> replies = new LinkedHashMap<>();
			final Map<Gridnode, FragmentStatus.Entry> census = new LinkedHashMap<>();
			final List<GroupId> probe = List.of(local.groupId());

			window.stream().filter(gridnode -> !gridnode.getId().equals(self))
				.forEach(gridnode -> replies.put(gridnode, transport.has(gridnode, probe)));
			replies.forEach((gridnode, reply) -> entryOf(reply, local)
				.ifPresent(entry -> census.put(gridnode, entry)));
			return census;
		}

		private List<Gridnode> free(final List<Gridnode> window, final Map<Gridnode, FragmentStatus.Entry> census) {
			return window.stream().filter(gridnode -> isFree(census.get(gridnode))).collect(Collectors.toList());
		}

		private Set<Integer> present(final Fragment local, final Map<Gridnode, FragmentStatus.Entry> census) {
			final Stream<Integer> held = census.values().stream()
				.filter(entry -> entry.getState() == FragmentStatus.State.HELD)
				.map(FragmentStatus.Entry::getIndex);

			return Stream.concat(Stream.of(local.getIndex()), held).collect(Collectors.toSet());
		}

		private void tombstone(final GroupId groupId, final DeleteProof proof) throws IOException {
			store.delete(groupId, Duration.ofDays(parameters.getTombstoneDays()), proof);
		}
	}

	private static boolean isFree(final FragmentStatus.Entry entry) {
		return entry != null && entry.getState() == FragmentStatus.State.NONE;
	}

	private static Optional<DeleteProof> deletionOf(final Fragment local,
		final Map<Gridnode, FragmentStatus.Entry> census) {

		return census.values().stream().map(entry -> proofOf(entry, local)).flatMap(Optional::stream).findFirst();
	}

	/* Only the owner's key signs a delete, so a proof under any other key must never remove a fragment */
	private static Optional<DeleteProof> proofOf(final FragmentStatus.Entry entry, final Fragment local) {
		return entry.getProof().filter(proof -> entry.getState() == FragmentStatus.State.TOMBSTONE
			&& proof.verifies(local.groupId())
			&& Arrays.equals(proof.getPublicKey(), local.getDescriptor().getPublicKey()));
	}

	/* A peer's answer is untrusted, so a tombstone it cannot prove counts as no answer at all */
	private static Optional<FragmentStatus.Entry> entryOf(final CompletableFuture<FragmentStatus> reply,
		final Fragment local) {

		try {
			return reply.join().getEntries().stream().findFirst()
				.filter(entry -> local.groupId().equals(entry.getGroupId()))
				.filter(entry -> entry.getState() != FragmentStatus.State.TOMBSTONE
					|| proofOf(entry, local).isPresent());
		} catch (CompletionException ex) {
			return Optional.empty();
		}
	}
}

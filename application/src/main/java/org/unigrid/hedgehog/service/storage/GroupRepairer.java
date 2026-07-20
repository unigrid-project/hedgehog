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
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.unigrid.hedgehog.model.gridnode.Gridnode;
import org.unigrid.hedgehog.model.network.packet.FragmentReply;
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
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class GroupRepairer {
	static final int SPOT_CHECK_EVERY = 4;

	private final FragmentStore store;
	private final GridnodeDirectory directory;
	private final FragmentTransport transport;
	private final Supplier<Optional<StorageSpork.SporkData>> spork;
	private final Clock clock;

	/* A liar that could predict which claim gets checked would simply tell the truth that round */
	private final Random random;

	public GroupRepairer(final FragmentStore store, final GridnodeDirectory directory, final FragmentTransport transport,
		final Supplier<Optional<StorageSpork.SporkData>> spork, final Clock clock) {

		this(store, directory, transport, spork, clock, new SecureRandom());
	}

	/* One holder per group and epoch does the probing, so a group costs O(window) messages per interval,
	   and a dead duty holder only delays the check by one epoch. */
	public static boolean isDuty(final GroupId groupId, final int rank, final long epoch, final int width) {
		return rank == Math.floorMod(epoch + Integer.toUnsignedLong(groupId.prefix()), width);
	}

	/* A spot check moves a whole fragment where a census answer is a few bytes, so checking on every duty visit
	   would add a fragment transfer per group and interval. Every fourth visit cuts that to a quarter, while a liar
	   is still caught within about four times as many visits. Knowing the schedule does not help a liar: answering
	   NONE in a checked round only makes the loss visible. */
	static boolean isSpotCheckDue(final GroupId groupId, final long epoch, final int width) {
		final long cycle = Math.floorDiv(epoch + Integer.toUnsignedLong(groupId.prefix()), width);
		return Math.floorMod(cycle, SPOT_CHECK_EVERY) == 0;
	}

	public static boolean needsRepair(final GroupDescriptor descriptor, final int present, final int thresholdPercent) {
		final int missing = descriptor.guaranteedFragments() - present;
		return missing >= Math.max(1, LayoutParameters.percentOf(descriptor.getParityFragments(), thresholdPercent));
	}

	public OptionalLong currentEpoch() {
		return spork.get().map(parameters -> OptionalLong.of(epochOf(parameters))).orElse(OptionalLong.empty());
	}

	/* A stable order lets shutdown stop between groups at a predictable point */
	public void runEpoch() {
		final Optional<StorageSpork.SporkData> parameters = spork.get();
		final Optional<String> self = directory.self();

		if (parameters.isEmpty() || self.isEmpty()) {
			return;
		}

		final Round round = new Round(parameters.get(), directory.active(), self.get(), epochOf(parameters.get()));

		store.groups().stream().sorted(Comparator.comparing(GroupId::toHex))
			.takeWhile(groupId -> !Thread.currentThread().isInterrupted()).forEach(round::tend);
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

		/* Anyone can sign a group that fails to rebuild, so one bad group must never end the round */
		void tend(final GroupId groupId) {
			try {
				tendOrFail(groupId);
			} catch (IOException ex) {
				log.atWarn().log("Unable to tend a stored group: {}", ex.getClass().getSimpleName());
			} catch (RuntimeException ex) {
				log.atDebug().log("Tending a stored group failed: {}", ex.getClass().getSimpleName());
			}
		}

		/* A node missing from its own view of the active gridnodes cannot tell where the window lies, as happens
		   while the topology fills in after a restart. The other holders' repair covers a node that really left. */
		private void tendOrFail(final GroupId groupId) throws IOException {
			final Optional<FragmentStore.Holding> holding = store.holding(groupId);
			final OptionalInt rank = Placement.rankOf(groupId, active, self);

			if (holding.isEmpty() || rank.isEmpty()) {
				return;
			}

			final int width = holding.get().getSlots() + parameters.getPlacementSlack();

			if (rank.getAsInt() >= width) {
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
				} else if (handOver(local.get(), free(window, census))) {
					store.remove(groupId);
				}
			}
		}

		private void repair(final GroupId groupId, final int width) throws IOException {
			final Optional<Fragment> local = localFragment(groupId);

			if (local.isEmpty()) {
				return;
			}

			final List<Gridnode> window = Placement.window(groupId, active, width);
			final Map<Gridnode, FragmentStatus.Entry> census = census(local.get(), window);
			final Optional<DeleteProof> deletion = deletionOf(local.get(), census);
			final Map<Gridnode, Integer> claims = claimsOf(census);

			if (deletion.isPresent()) {
				tombstone(groupId, deletion.get());
			} else if (isShort(local.get(), claims)
				|| (isSpotCheckDue(groupId, epoch, width) && !spotCheck(local.get(), claims))) {

				rebuild(local.get(), claims, free(window, census));
			}
		}

		private boolean isShort(final Fragment local, final Map<Gridnode, Integer> claims) {
			return needsRepair(local.getDescriptor(), presentOf(local, claims.values()).size(),
				parameters.getRepairThresholdPercent());
		}

		/* A census claim is unauthenticated, so a spot check makes one random claimant prove it. A lone liar
		   hiding a loss is caught after about as many spot checks as there are claimants, and a caught liar counts
		   as absent. L colluding liars can still hold repair back until the real losses reach the threshold plus
		   L - 1, so the parity must outnumber the liars a window can hold. Returns whether the group still holds
		   up without the claims that failed. */
		private boolean spotCheck(final Fragment local, final Map<Gridnode, Integer> claims) {
			if (!claims.isEmpty()) {
				final List<Gridnode> claimants = new ArrayList<>(claims.keySet());
				final Gridnode claimant = claimants.get(random.nextInt(claimants.size()));
				final int claimed = claims.get(claimant);

				if (verified(transport.fetch(claimant, local.groupId()), local, claimed).isEmpty()) {
					claims.remove(claimant);
				}
			}

			return !isShort(local, claims);
		}

		/* Rebuilding decodes the whole group, so a group the current spork could never have produced is left to
		   those who can afford it: a validly signed group may still claim 255 slots of any size */
		private boolean isAffordable(final GroupDescriptor descriptor) {
			final LayoutParameters current = parameters.layout();
			final long ceiling = Math.min(parameters.getMaxBytesPerNode(),
				(long) FragmentKeeper.GROWTH_TOLERANCE * current.maxFragments() * current.getFragmentSize());

			return (long) descriptor.getMaxFragments() * descriptor.getFragmentSize() <= ceiling;
		}

		/* Every claimant has to hand over its fragment, so an index is only counted once it verifies */
		private void rebuild(final Fragment local, final Map<Gridnode, Integer> claims, final List<Gridnode> free) {
			final GroupDescriptor descriptor = local.getDescriptor();

			/* Loud on purpose: after a steep spork shrink no holder may repair the group, so it starves */
			if (!isAffordable(descriptor)) {
				log.atWarn().log("Skipping the repair of a group beyond what this gridnode rebuilds");
				return;
			}

			final Map<Integer, Fragment> sources = fetchAll(local, claims);

			if (GroupFetcher.isComplete(sources.values())) {
				final List<Integer> missing = IntStream.range(0, descriptor.getMaxFragments())
					.filter(index -> !sources.containsKey(index)).boxed().collect(Collectors.toList());
				final Iterator<Gridnode> targets = free.iterator();

				ChunkGroups.rebuild(sources.values(), missing)
					.forEach(fragment -> deliver(fragment, targets));
			}
		}

		private Map<Integer, Fragment> fetchAll(final Fragment local, final Map<Gridnode, Integer> claims) {
			final Map<Gridnode, CompletableFuture<FragmentReply>> replies = new LinkedHashMap<>();
			final Map<Integer, Fragment> sources = new LinkedHashMap<>();
			final GroupId groupId = local.groupId();

			claims.keySet().forEach(claimant -> replies.put(claimant, transport.fetch(claimant, groupId)));
			sources.put(local.getIndex(), local);
			replies.forEach((claimant, reply) -> verified(reply, local, claims.get(claimant))
				.ifPresent(fragment -> sources.putIfAbsent(fragment.getIndex(), fragment)));
			return sources;
		}

		/* An acknowledgement costs a peer nothing, so the local copy only goes once the target serves it back */
		private boolean handOver(final Fragment fragment, final List<Gridnode> targets) {
			final byte[] encoded = fragment.encode();

			return targets.stream().anyMatch(target -> GroupDistributor.stored(transport.store(target, encoded))
				&& servesBack(target, fragment));
		}

		private boolean servesBack(final Gridnode target, final Fragment fragment) {
			final CompletableFuture<FragmentReply> reply = transport.fetch(target, fragment.groupId());
			return verified(reply, fragment, fragment.getIndex()).isPresent();
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

		private void tombstone(final GroupId groupId, final DeleteProof proof) throws IOException {
			store.delete(groupId, Duration.ofDays(parameters.getTombstoneDays()), proof);
		}
	}

	private static boolean isFree(final FragmentStatus.Entry entry) {
		return entry != null && entry.getState() == FragmentStatus.State.NONE;
	}

	private static Map<Gridnode, Integer> claimsOf(final Map<Gridnode, FragmentStatus.Entry> census) {
		final Map<Gridnode, Integer> claims = new LinkedHashMap<>();

		census.forEach((gridnode, entry) -> {
			if (entry.getState() == FragmentStatus.State.HELD) {
				claims.put(gridnode, entry.getIndex());
			}
		});

		return claims;
	}

	private static Collection<Integer> presentOf(final Fragment local, final Collection<Integer> claimed) {
		return Stream.concat(Stream.of(local.getIndex()), claimed.stream()).collect(Collectors.toSet());
	}

	private static Optional<Fragment> verified(final CompletableFuture<FragmentReply> reply, final Fragment local,
		final int claimed) {

		try {
			return GroupFetcher.valid(local.groupId(), local.format(), reply.join())
				.filter(fragment -> fragment.getIndex() == claimed);
		} catch (CompletionException ex) {
			return Optional.empty();
		}
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

	/* A peer's answer is untrusted, so a claim outside the group or a tombstone it cannot prove counts as no
	   answer at all */
	private static boolean isCredible(final FragmentStatus.Entry entry, final Fragment local) {
		return switch (entry.getState()) {
			case HELD -> isSlotOf(local, entry.getIndex());
			case TOMBSTONE -> proofOf(entry, local).isPresent();
			case NONE -> true;
		};
	}

	private static boolean isSlotOf(final Fragment local, final int index) {
		return LayoutParameters.inRange(index, 0, local.getDescriptor().getMaxFragments() - 1);
	}

	private static Optional<FragmentStatus.Entry> entryOf(final CompletableFuture<FragmentStatus> reply,
		final Fragment local) {

		try {
			return reply.join().getEntries().stream().findFirst()
				.filter(entry -> local.groupId().equals(entry.getGroupId()) && isCredible(entry, local));
		} catch (CompletionException ex) {
			return Optional.empty();
		}
	}
}

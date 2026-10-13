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

import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.unigrid.hedgehog.model.network.packet.FragmentStatus.Entry;
import org.unigrid.hedgehog.model.network.packet.FragmentStatus.State;
import org.unigrid.hedgehog.model.spork.StorageSpork;
import org.unigrid.hedgehog.model.storage.DeleteProof;
import org.unigrid.hedgehog.model.storage.Fragment;
import org.unigrid.hedgehog.model.storage.GroupId;
import org.unigrid.hedgehog.model.storage.StorageStatus;
import org.unigrid.hedgehog.model.storage.store.FragmentStore;
import org.unigrid.hedgehog.model.storage.store.FragmentStore.PutResult;
import org.unigrid.hedgehog.model.storage.store.FragmentStore.Tier;

@Slf4j
@RequiredArgsConstructor
public class FragmentKeeper {
	private final FragmentStore store;
	private final Supplier<Optional<StorageSpork.SporkData>> spork;

	public static Optional<Fragment> decode(final byte[] encoded) {
		try {
			final Fragment fragment = Fragment.decode(encoded);
			return fragment.verify(fragment.groupId()) ? Optional.of(fragment) : Optional.empty();
		} catch (IllegalArgumentException | BufferUnderflowException ex) {
			return Optional.empty();
		}
	}

	public StorageStatus store(final byte[] encoded) {
		final Optional<StorageSpork.SporkData> parameters = spork.get();

		if (parameters.isEmpty()) {
			return StorageStatus.DISABLED;
		}

		return decode(encoded).map(fragment -> put(fragment, encoded, parameters.get()))
			.orElse(StorageStatus.INVALID);
	}

	public Optional<byte[]> fetch(final GroupId groupId) {
		try {
			return store.get(groupId);
		} catch (IOException ex) {
			warn("Unable to read a stored fragment", ex);
			return Optional.empty();
		}
	}

	public List<Entry> census(final List<GroupId> groupIds) {
		return groupIds.stream().map(this::entryOf).collect(Collectors.toList());
	}

	/* The proof is self-certifying, so a node that never held the group keeps the tombstone as well */
	public StorageStatus delete(final GroupId groupId, final byte[] publicKey, final long timestamp,
		final byte[] signature) {

		final Optional<StorageSpork.SporkData> parameters = spork.get();

		if (parameters.isEmpty()) {
			return StorageStatus.DISABLED;
		}

		if (store.isTombstoned(groupId)) {
			return StorageStatus.OK;
		}

		final DeleteProof proof = new DeleteProof(publicKey, timestamp, signature);

		if (!proof.verifies(groupId)) {
			return StorageStatus.INVALID;
		}

		return tombstone(groupId, proof, parameters.get().getTombstoneDays());
	}

	private StorageStatus put(final Fragment fragment, final byte[] encoded, final StorageSpork.SporkData parameters) {
		final Tier tier = fragment.isExtra() ? Tier.EXTRA : Tier.GUARANTEED;

		store.limits(parameters.getMaxBytesPerNode(), parameters.getExtraPoolPercent());

		try {
			return statusOf(store.put(fragment.groupId(), fragment.format(),
				fragment.getDescriptor().getMaxFragments(), tier, fragment.getIndex(), encoded));
		} catch (IOException ex) {
			warn("Unable to store a fragment", ex);
			return StorageStatus.ERROR;
		}
	}

	private StorageStatus tombstone(final GroupId groupId, final DeleteProof proof, final int tombstoneDays) {
		try {
			store.delete(groupId, Duration.ofDays(tombstoneDays), proof);
			return StorageStatus.OK;
		} catch (IOException ex) {
			warn("Unable to tombstone a group", ex);
			return StorageStatus.ERROR;
		}
	}

	private Entry entryOf(final GroupId groupId) {
		return store.tombstone(groupId).map(proof -> new Entry(groupId, State.TOMBSTONE, 0, proof))
			.or(() -> store.holding(groupId).map(holding -> new Entry(groupId, State.HELD, holding.getIndex())))
			.orElseGet(() -> new Entry(groupId, State.NONE, 0));
	}

	private static StorageStatus statusOf(final PutResult result) {
		return switch (result) {
			case STORED -> StorageStatus.OK;
			case QUOTA -> StorageStatus.QUOTA;
			case TOMBSTONE -> StorageStatus.TOMBSTONE;
			case DUPLICATE -> StorageStatus.DUPLICATE;
		};
	}

	/* An I/O message can name the fragment's path, and with it the group, which belongs at trace level only */
	private static void warn(final String failure, final IOException ex) {
		log.atWarn().log("{}: {}", failure, ex.getClass().getSimpleName());
		log.atTrace().setCause(ex).log(failure);
	}
}

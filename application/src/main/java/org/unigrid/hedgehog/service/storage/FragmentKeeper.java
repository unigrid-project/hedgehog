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
import java.nio.BufferUnderflowException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.unigrid.hedgehog.model.network.packet.FragmentStatus;
import org.unigrid.hedgehog.model.spork.StorageSpork;
import org.unigrid.hedgehog.model.storage.DeleteProof;
import org.unigrid.hedgehog.model.storage.Fragment;
import org.unigrid.hedgehog.model.storage.GroupId;
import org.unigrid.hedgehog.model.storage.StorageStatus;
import org.unigrid.hedgehog.model.storage.crypto.GroupKey;
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
			log.atWarn().log("Unable to read a stored fragment: {}", ex.getMessage());
			return Optional.empty();
		}
	}

	public List<FragmentStatus.Entry> census(final List<GroupId> groupIds) {
		return groupIds.stream().map(this::entryOf).collect(Collectors.toList());
	}

	public StorageStatus delete(final GroupId groupId, final long timestamp, final byte[] signature) {
		final Optional<StorageSpork.SporkData> parameters = spork.get();

		if (parameters.isEmpty()) {
			return StorageStatus.DISABLED;
		}

		if (store.isTombstoned(groupId)) {
			return StorageStatus.OK;
		}

		final Optional<Fragment> held = fetch(groupId).flatMap(FragmentKeeper::decode);

		if (held.isEmpty()) {
			return StorageStatus.NOT_FOUND;
		}

		final byte[] owner = held.get().getDescriptor().getPublicKey();

		if (!GroupKey.verify(owner, GroupKey.deleteMessage(groupId, timestamp), signature)) {
			return StorageStatus.INVALID;
		}

		return tombstone(groupId, new DeleteProof(owner, timestamp, signature), parameters.get().getTombstoneDays());
	}

	private StorageStatus put(final Fragment fragment, final byte[] encoded, final StorageSpork.SporkData parameters) {
		final Tier tier = fragment.isExtra() ? Tier.EXTRA : Tier.GUARANTEED;

		store.limits(parameters.getMaxBytesPerNode(), parameters.getExtraPoolPercent());

		try {
			return statusOf(store.put(fragment.groupId(), fragment.format(),
				fragment.getDescriptor().getMaxFragments(), tier, fragment.getIndex(), encoded));
		} catch (IOException ex) {
			log.atWarn().log("Unable to store a fragment: {}", ex.getMessage());
			return StorageStatus.ERROR;
		}
	}

	private StorageStatus tombstone(final GroupId groupId, final DeleteProof proof, final int tombstoneDays) {
		try {
			store.delete(groupId, Duration.ofDays(tombstoneDays), proof);
			return StorageStatus.OK;
		} catch (IOException ex) {
			log.atWarn().log("Unable to delete a fragment: {}", ex.getMessage());
			return StorageStatus.ERROR;
		}
	}

	private FragmentStatus.Entry entryOf(final GroupId groupId) {
		if (store.isTombstoned(groupId)) {
			return new FragmentStatus.Entry(groupId, FragmentStatus.State.TOMBSTONE, 0);
		}

		return store.holding(groupId)
			.map(holding -> new FragmentStatus.Entry(groupId, FragmentStatus.State.HELD, holding.getIndex()))
			.orElseGet(() -> new FragmentStatus.Entry(groupId, FragmentStatus.State.NONE, 0));
	}

	private static StorageStatus statusOf(final PutResult result) {
		switch (result) {
			case STORED: return StorageStatus.OK;
			case QUOTA: return StorageStatus.QUOTA;
			case TOMBSTONE: return StorageStatus.TOMBSTONE;
			default: return StorageStatus.DUPLICATE;
		}
	}
}

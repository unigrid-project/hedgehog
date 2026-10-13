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

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.unigrid.hedgehog.model.gridnode.Gridnode;
import org.unigrid.hedgehog.model.network.packet.FragmentReply;
import org.unigrid.hedgehog.model.storage.Fragment;
import org.unigrid.hedgehog.model.storage.GroupId;
import org.unigrid.hedgehog.model.storage.StorageFormat;
import org.unigrid.hedgehog.model.storage.StorageStatus;

@RequiredArgsConstructor
public class GroupFetcher {
	private static final int OVER_FETCH = 2;

	private final FragmentTransport transport;

	public static boolean isComplete(final Collection<Fragment> fragments) {
		return !fragments.isEmpty()
			&& fragments.size() >= fragments.iterator().next().getDescriptor().getDataFragments();
	}

	/* The best-ranked candidates usually suffice; the rest of the list is only asked when they do not. */
	public List<Fragment> fetch(final GroupId groupId, final List<Gridnode> candidates, final int expectedData,
		final StorageFormat format) {

		final int first = Math.min(candidates.size(), expectedData + OVER_FETCH);
		final Map<Integer, Fragment> found = collect(groupId, format, candidates.subList(0, first), new TreeMap<>());

		if (!isComplete(found.values())) {
			collect(groupId, format, candidates.subList(first, candidates.size()), found);
		}

		return List.copyOf(found.values());
	}

	/* Only verified fragments claim an index, so a forged copy can never displace a genuine one */
	private Map<Integer, Fragment> collect(final GroupId groupId, final StorageFormat format,
		final List<Gridnode> targets, final Map<Integer, Fragment> found) {

		final List<CompletableFuture<FragmentReply>> replies = targets.stream()
			.map(target -> transport.fetch(target, groupId)).collect(Collectors.toList());

		for (CompletableFuture<FragmentReply> reply : replies) {
			valid(groupId, format, reply)
				.ifPresent(fragment -> found.putIfAbsent(fragment.getIndex(), fragment));
		}

		return found;
	}

	private static Optional<Fragment> valid(final GroupId groupId, final StorageFormat format,
		final CompletableFuture<FragmentReply> reply) {

		try {
			final FragmentReply response = reply.join();

			if (response.getStatus() != StorageStatus.OK || response.getFragment() == null) {
				return Optional.empty();
			}

			return FragmentKeeper.decode(response.getFragment())
				.filter(fragment -> fragment.groupId().equals(groupId) && fragment.format() == format);
		} catch (CompletionException ex) {
			return Optional.empty();
		}
	}
}

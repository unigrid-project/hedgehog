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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.Validate;
import org.unigrid.hedgehog.model.crypto.ActiveKeys;
import org.unigrid.hedgehog.model.crypto.Signature;
import org.unigrid.hedgehog.model.spork.KeyChange.Approval;

/**
* The board keys as a chain of changes from the keys built into the release. The chain is rooted in those keys and in
* an epoch of the release, so a release that changes either starts a chain of its own and voids every change made
* before it, and a private network with keys of its own has a root of its own.
*
* <p>A change holds when it follows on the change before it, or on the root, lists 2 to 16 keys that differ from the
* keys before it, a majority of the keys before it approved it, and never fewer than two, and every key it adds
* approved it too: a key that is added has to be held by someone, so a mistyped key cannot take the place of the
* board. No other key approves a change. An approval covers the change as a whole except the approvals.
*
* <p>Verifying a change costs a signature for each approval, and a peer can send a chain that a node verified before
* with a change added that does not hold, so a change that verified is remembered by its digest, which covers every
* change before it and the root.
*/
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class BoardKeyChain {
	public static final int MAX_CHANGES = 64;
	public static final int MIN_KEYS = 2;
	public static final int MAX_KEYS = 16;
	public static final int MAX_APPROVALS = 2 * MAX_KEYS;

	private static final byte[] ROOT_TAG = "hedgehog/board-keys/1".getBytes(StandardCharsets.US_ASCII);
	private static final int MAX_REMEMBERED = 4 * MAX_CHANGES;
	private static final Map<String, ActiveKeys> VERIFIED = new ConcurrentHashMap<>();

	/*
	    Root format, of which the root is the SHA-512 digest:
	    [                     "hedgehog/board-keys/1"                    ]
	    [                             epoch                              ]
	    [ n= keys ]   n[  size  ][      key (size long, as given)   >>]
	*/
	public static byte[] root(long epoch, Collection<String> builtInKeys) {
		final ByteBuf out = Unpooled.buffer();

		try {
			out.writeBytes(ROOT_TAG);
			out.writeLong(epoch);
			out.writeShort(builtInKeys.size());

			new TreeSet<>(builtInKeys).forEach(key -> {
				out.writeShort(key.length());
				out.writeBytes(key.getBytes(StandardCharsets.US_ASCII));
			});

			return DigestUtils.sha512(ByteBufUtil.getBytes(out));
		} finally {
			out.release();
		}
	}

	public static int quorumOf(int keys) {
		return Math.max(MIN_KEYS, keys / 2 + 1);
	}

	public static byte[] digestOf(KeyChange change) {
		return DigestUtils.sha512(change.toBytes());
	}

	/* What the next change follows on */
	public static byte[] headOf(byte[] root, List<KeyChange> changes) {
		return changes.isEmpty() ? root : digestOf(changes.getLast());
	}

	/**
	* The keys that the chain makes active, and every key it ever made active, or an IllegalArgumentException that
	* says which change does not hold and why.
	*/
	public static ActiveKeys verify(byte[] root, Collection<String> builtInKeys, List<KeyChange> changes) {
		Validate.isTrue(changes.size() <= MAX_CHANGES, "A chain of board keys holds at most %d changes; a longer"
			+ " one takes a release with a new root", MAX_CHANGES);

		ActiveKeys keys = ActiveKeys.of(builtInKeys);
		byte[] previous = root;

		for (int i = 0; i < changes.size(); i++) {
			final KeyChange change = changes.get(i);

			try {
				requireLink(change, i, previous);
				previous = digestOf(change);
				keys = verified(change, keys, previous);
			} catch (IllegalArgumentException | NullPointerException ex) {
				throw new IllegalArgumentException("Change " + i + " of the board keys does not hold: "
					+ ex.getMessage(), ex);
			}
		}

		return keys;
	}

	/* As verify() answers, or nothing when the chain does not hold */
	public static Optional<ActiveKeys> verified(byte[] root, Collection<String> builtInKeys, List<KeyChange> changes) {
		try {
			return Optional.of(verify(root, builtInKeys, changes));
		} catch (IllegalArgumentException ex) {
			return Optional.empty();
		}
	}

	private static void requireLink(KeyChange change, int index, byte[] previous) {
		Validate.isTrue(change.getSequence() == index, "it has the sequence number %d at place %d",
			change.getSequence(), index);
		Validate.isTrue(Arrays.equals(change.getPrevious(), previous), "it does not follow on the %s",
			index == 0 ? "root of the chain" : "change before it");
	}

	private static ActiveKeys verified(KeyChange change, ActiveKeys before, byte[] digest) {
		final String id = Hex.encodeHexString(digest);
		final ActiveKeys remembered = VERIFIED.get(id);

		if (Objects.nonNull(remembered)) {
			return remembered;
		}

		requireKeys(change.getActive(), before.active());
		requireApprovals(change, before.active());

		final ActiveKeys after = before.after(change.getActive());

		if (VERIFIED.size() < MAX_REMEMBERED) {
			VERIFIED.putIfAbsent(id, after);
		}

		return after;
	}

	private static void requireKeys(List<String> active, Set<String> before) {
		Validate.isTrue(isAscending(active), "its keys are not in ascending order without repeats");
		Validate.isTrue(active.size() >= MIN_KEYS && active.size() <= MAX_KEYS, "it lists %d keys, not %d to %d",
			active.size(), MIN_KEYS, MAX_KEYS);
		Validate.isTrue(active.stream().allMatch(Signature::isPublicKey), "a key it lists is no public key of P-521"
			+ " in lower-case hex");
		Validate.isTrue(!before.equals(Set.copyOf(active)), "it leaves the keys as they are");
	}

	/* Every count is made before any signature is checked, so a change that cannot hold costs none */
	private static void requireApprovals(KeyChange change, Set<String> before) {
		final List<String> approvers = change.getApprovals().stream().map(Approval::getKey).toList();
		final Set<String> added = new TreeSet<>(change.getActive());
		final int quorum = quorumOf(before.size());

		added.removeAll(before);
		Validate.isTrue(isAscending(approvers), "its approvals are not in ascending order of their keys without"
			+ " repeats");
		Validate.isTrue(approvers.stream().allMatch(key -> before.contains(key) || added.contains(key)),
			"an approval is by a key that is neither active before it nor added by it");
		final long approved = approvers.stream().filter(before::contains).count();

		Validate.isTrue(approved >= quorum, "%d of the %d keys before it approved it, %d have to", approved,
			before.size(), quorum);
		Validate.isTrue(approvers.containsAll(added), "a key that it adds did not approve it");

		final byte[] signable = change.getSignable();

		Validate.isTrue(change.getApprovals().stream().allMatch(approval -> Signature.isSignedBy(approval.getKey(),
			signable, approval.getSignature())), "an approval does not verify");
	}

	private static boolean isAscending(List<String> keys) {
		return IntStream.range(1, keys.size()).allMatch(i -> keys.get(i - 1).compareTo(keys.get(i)) < 0);
	}
}

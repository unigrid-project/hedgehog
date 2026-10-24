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
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.IntStream;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.SneakyThrows;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.Validate;
import org.unigrid.hedgehog.command.option.NetOptions;
import org.unigrid.hedgehog.model.Network;
import org.unigrid.hedgehog.model.crypto.ActiveKeys;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
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
* board. No other key approves a change. An approval covers what the change decides, which is all of it but the
* approvals, and the change after it follows on the same.
*
* <p>Verifying a change costs a signature for each approval, and a peer can send a chain that a node verified before
* with a change added that does not hold, so a change that verified is remembered by the digest of all of its bytes,
* which follow on every change before it and on the root. So many are remembered and no more, the one used least
* recently going first, so that copies of a change signed again, each of other bytes, push out none that is in use.
*
* <p>A release that ships the chain up to a change ({@link #shipped()}) takes no chain from its root that does not
* hold it, compared by what the changes decide.
*/
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class BoardKeyChain {
	public static final int MAX_CHANGES = 64;
	public static final int MIN_KEYS = 2;
	public static final int MAX_KEYS = 16;
	public static final int MAX_APPROVALS = 2 * MAX_KEYS;

	private static final byte[] ROOT_TAG = "hedgehog/board-keys/1".getBytes(StandardCharsets.US_ASCII);
	private static final byte[] LINK_TAG = "hedgehog/board-keys/link/1".getBytes(StandardCharsets.US_ASCII);
	static final int MAX_REMEMBERED = 4 * MAX_CHANGES;
	private static final float LOAD_FACTOR = 0.75f;
	private static final Map<String, String> REJECTED = remembering(MAX_REMEMBERED);
	private static final Map<String, String> REJECTED_CHAINS = remembering(MAX_REMEMBERED);

	private static final Map<String, ActiveKeys> VERIFIED = remembering(MAX_REMEMBERED);

	/* The chain of board keys that the release ships, and the board keys built into it */
	record Release(List<KeyChange> chain, Set<String> builtInKeys) {
		/* The chain in the form it travels in, of a resource or of a record of this machine (BoardKeysRecord) */
		static Release read(InputStream in, Set<String> builtInKeys) {
			try (in) {
				return new Release(decoded(in.readNBytes(MAX_BYTES + 1)), builtInKeys);
			} catch (IOException ex) {
				throw new UncheckedIOException(ex);
			}
		}
	}

	/* A chain of the most changes, each of the most keys and approvals, with room to spare */
	static final int MAX_BYTES = 1 << 20;

	static final Release THE_RELEASE = shippedWithTheRelease();

	private static volatile Release release = THE_RELEASE;

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

	/* The root of this node: the epoch of the release and the board keys it was started with */
	public static byte[] root() {
		return root(Network.BOARD_KEYS_EPOCH, builtInKeys());
	}

	private static List<String> builtInKeys() {
		return Arrays.asList(NetworkKey.getBuiltInKeys());
	}

	/* As verify() answers, from the root of this node and through the chain that its release ships */
	public static ActiveKeys verify(List<KeyChange> changes) {
		final List<KeyChange> shipped = shipped();

		Validate.isTrue(holds(changes, shipped), "The chain of board keys does not hold the %d changes of the chain"
			+ " of the board that this release ships", shipped.size());

		return verify(root(), builtInKeys(), changes);
	}

	public static Optional<ActiveKeys> verified(List<KeyChange> changes) {
		return holds(changes, shipped()) ? verified(root(), builtInKeys(), changes) : Optional.empty();
	}

	/**
	* The chain of board keys that the release ships ({@link Network#BOARD_KEYS_RESOURCE}), which a node that goes by
	* the keys built into the release goes by when it holds no chain, and which every chain it takes holds. A network
	* of keys of its own goes without it.
	*/
	public static List<KeyChange> shipped() {
		final Release vouching = release;

		return vouching.chain().isEmpty() || !isBuiltInto(vouching) ? List.of() : vouching.chain();
	}

	private static boolean isBuiltInto(Release vouching) {
		return Optional.ofNullable(NetworkKey.getBuiltInKeys()).map(keys -> Set.copyOf(Arrays.asList(keys))
			.equals(vouching.builtInKeys())).orElse(false);
	}

	private static Release shippedWithTheRelease() {
		final Set<String> builtIn = Set.of(NetOptions.RELEASE_NETWORK_KEYS.split(","));
		final InputStream resource = BoardKeyChain.class.getResourceAsStream(Network.BOARD_KEYS_RESOURCE);

		return Objects.isNull(resource) ? new Release(List.of(), builtIn) : Release.read(resource, builtIn);
	}

	/* What a test stands in for the release, which goes back with THE_RELEASE */
	static void setRelease(Release vouching) {
		release = vouching;
	}

	/* A chain in the form it travels in, which a node reads from its peers and a release and a record hold */
	public static byte[] encoded(List<KeyChange> chain) {
		final BoardKeys.SporkData data = new BoardKeys.SporkData();

		data.setChanges(new ArrayList<>(chain));
		return SporkContentEncoder.encode(GridSpork.Type.BOARD_KEYS, data);
	}

	@SneakyThrows
	static List<KeyChange> decoded(byte[] read) {
		Validate.isTrue(read.length <= MAX_BYTES, "it is larger than a chain of board keys is");

		final ByteBuf bytes = Unpooled.wrappedBuffer(read);
		final List<KeyChange> chain = new BoardKeysDecoder().decodeChunk(null, bytes).orElseThrow().getChanges();

		Validate.isTrue(!bytes.isReadable(), "it holds more than a chain of board keys");
		return chain;
	}

	public static int quorumOf(int keys) {
		return Math.max(MIN_KEYS, keys / 2 + 1);
	}

	/* What the next change follows on: the digest of what this one decides. The approvals decide whether it holds,
	   not which change it is, as whoever holds a change can drop an approval beyond the quorum or turn a signature
	   into its other valid form, and the change that follows would no longer follow on it. Tagged apart from the
	   bytes that an approval signs, so it is no digest that a signature covers. */
	public static byte[] digestOf(KeyChange change) {
		return DigestUtils.sha512(change.decidedBehind(LINK_TAG));
	}

	/* Whether the chain begins with the whole of the other, as their changes decide. The last change of the other is
	   enough to compare, as each change follows on what the one before it decided; a change that cannot be written is
	   no change of either. */
	public static boolean holds(List<KeyChange> chain, List<KeyChange> other) {
		try {
			return chain.size() >= other.size() && (other.isEmpty()
				|| Arrays.equals(digestOf(chain.get(other.size() - 1)), digestOf(other.getLast())));
		} catch (IllegalArgumentException ex) {
			return false;
		}
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
		final Optional<String> id = idOf(root, changes);
		final String rejected = id.map(REJECTED_CHAINS::get).orElse(null);

		Validate.isTrue(Objects.isNull(rejected), "%s", rejected);

		try {
			return verifiedFrom(root, builtInKeys, changes);
		} catch (IllegalArgumentException ex) {
			id.ifPresent(chain -> REJECTED_CHAINS.put(chain, ex.getMessage()));
			throw ex;
		}
	}

	/* A chain that did not hold from a root never will, whatever it failed on, so it is known by the digest of the
	   root and of all of its bytes; one that cannot be written has none */
	private static Optional<String> idOf(byte[] root, List<KeyChange> changes) {
		try {
			final MessageDigest digest = DigestUtils.getSha512Digest();

			digest.update(root);
			changes.forEach(change -> digest.update(change.toBytes()));
			return Optional.of(Hex.encodeHexString(digest.digest()));
		} catch (IllegalArgumentException | NullPointerException ex) {
			return Optional.empty();
		}
	}

	private static ActiveKeys verifiedFrom(byte[] root, Collection<String> builtInKeys, List<KeyChange> changes) {
		Validate.isTrue(changes.size() <= MAX_CHANGES, "A chain of board keys holds at most %d changes; a longer"
			+ " one takes a release with a new root", MAX_CHANGES);

		ActiveKeys keys = ActiveKeys.of(builtInKeys);
		byte[] previous = root;

		for (int i = 0; i < changes.size(); i++) {
			final KeyChange change = changes.get(i);

			try {
				requireLink(change, i, previous);
				keys = verified(change, keys);
				previous = digestOf(change);
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

	/* Remembered by the digest of the change with its approvals, so a copy with an approval that does not verify is
	   checked again. The digest pins every change before it, so a change that did not hold never will, and what it
	   failed on is remembered too: the same forged change sent again costs no signature. */
	private static ActiveKeys verified(KeyChange change, ActiveKeys before) {
		final String id = DigestUtils.sha512Hex(change.toBytes());
		final ActiveKeys remembered = VERIFIED.get(id);

		if (Objects.nonNull(remembered)) {
			return remembered;
		}

		Validate.isTrue(!REJECTED.containsKey(id), "%s", REJECTED.get(id));

		try {
			requireKeys(change.getActive(), before.active());
			requireApprovals(change, before.active());
		} catch (IllegalArgumentException ex) {
			REJECTED.put(id, ex.getMessage());
			throw ex;
		}

		final ActiveKeys after = before.after(change.getActive());

		VERIFIED.put(id, after);

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
		final Set<String> added = requireApprovers(change, before);
		final byte[] signable = change.getSignable();

		/* The approvals of the keys before it first, as a change that a quorum of them did not make fails on the
		   first one that does not verify, before any approval of a key it adds is checked */
		Validate.isTrue(change.getApprovals().stream().sorted(Comparator.comparing(approval -> added.contains(
			approval.getKey()))).allMatch(approval -> Signature.isSignedBy(approval.getKey(), signable,
			approval.getSignature())), "an approval does not verify");
	}

	/* Who approved the change, by the rules that cost no signature; the keys it adds, which approve it too */
	private static Set<String> requireApprovers(KeyChange change, Set<String> before) {
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
		return added;
	}

	/* At most so many, the one used least recently going first, so that what a node is sent again and again stays */
	private static <V> Map<String, V> remembering(int capacity) {
		return Collections.synchronizedMap(new LinkedHashMap<>(capacity, LOAD_FACTOR, true) {
			@Override
			protected boolean removeEldestEntry(Map.Entry<String, V> eldest) {
				return size() > capacity;
			}
		});
	}

	private static boolean isAscending(List<String> keys) {
		return IntStream.range(1, keys.size()).allMatch(i -> keys.get(i - 1).compareTo(keys.get(i)) < 0);
	}
}

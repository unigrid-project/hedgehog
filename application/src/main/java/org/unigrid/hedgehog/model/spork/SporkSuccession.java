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

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.apache.commons.codec.digest.DigestUtils;

/**
* Which version of a spork may follow on the stored one. The spork judged checks its own signatures, at the cost and
* with the caching that it keeps, and decides whether its type follows on a version at all
* ({@link GridSpork#isSuccessorOf}).
*/
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class SporkSuccession {
	/* As GridSpork.canReplace(GridSpork, BooleanSupplier) decides */
	static boolean canReplace(final GridSpork spork, final GridSpork stored, final BooleanSupplier mayVerify) {
		return succeeds(spork, stored, mayVerify, spork::isDoublySigned)
			|| isTheCopyThatCountsOf(spork, stored, mayVerify);
	}

	/* The signatures are no part of a version, so two board members who co-sign one proposal make two copies of it,
	   and a node keeps the copy that it took first. Once the board switches off a key that signed the copy a node
	   holds, that copy counts for nothing there, while the copy that its peers send is no newer, so the node would
	   have no policy of the type until the next version. Whoever holds a key that the board switched off brings a
	   node there that has not heard of the change yet, with its own signature of the head in the place of one of
	   the two. So a copy of the stored version that two different active keys signed takes the place of a stored
	   copy that does not count. It is the same version, by its time stamp and by the digest of what it signs, so no
	   data changes, and its log is the stored one, which was checked when the stored copy was taken. A stored copy
	   that counts keeps its place, so two copies that count never take the place of each other. */
	private static boolean isTheCopyThatCountsOf(final GridSpork spork, final GridSpork stored,
		final BooleanSupplier mayVerify) {

		return mayStandInFor(spork, stored) && hasTheDigestOf(spork, stored) && mayVerify.getAsBoolean()
			&& spork.isDoublySigned();
	}

	/* Every peer sends the head that it holds again and again, which on a node that keeps up is a copy of the stored
	   version, so what costs nothing is judged first. Whether the stored copy counts is worked out once for the keys
	   that the node goes by, and only for one that does not is the digest judged, which takes the stored data encoded
	   and its log hashed. The board keys follow their chain alone (BoardKeys.isSuccessorOf). */
	private static boolean mayStandInFor(final GridSpork spork, final GridSpork stored) {
		return Objects.nonNull(stored) && spork.getType() == stored.getType()
			&& spork.getType() != GridSpork.Type.BOARD_KEYS
			&& millisOf(spork.getTimeStamp()) == millisOf(stored.getTimeStamp()) && !stored.isCounting();
	}

	private static boolean hasTheDigestOf(final GridSpork spork, final GridSpork stored) {
		return Arrays.equals(DigestUtils.sha512(spork.getSignable()), DigestUtils.sha512(stored.getSignable()));
	}

	/* As GridSpork.canBeProposedOver(GridSpork, BooleanSupplier) decides */
	static boolean canBeProposedOver(final GridSpork spork, final GridSpork stored,
		final BooleanSupplier mayVerify) {

		return spork.isPending() && succeeds(spork, stored, mayVerify, spork::isValidSignature);
	}

	/* As GridSpork.isProposalOver(GridSpork, BooleanSupplier) decides */
	static boolean isProposalOver(final GridSpork spork, final GridSpork stored, final BooleanSupplier mayVerify) {
		return spork.isPending() && isMadeFrom(spork, stored)
			&& succeeds(spork, stored, mayVerify, spork::isValidSignature);
	}

	/* As GridSpork.isRenewalOf(GridSpork) decides */
	static boolean isRenewalOf(final GridSpork spork, final GridSpork stored) {
		final SignatureLog storedLog = stored.getSignatureLog();

		return isRenewalOver(spork, stored) && spork.getSignatureLog().size() == storedLog.size() + 1
			&& storedLog.isPrefixOf(spork.getSignatureLog()) && endsWithTheHeadOf(spork, stored);
	}

	/* The previous data and time stamp of a version over a forged one, renewed, are those of the stored spork, so what
	   it was made from is told by the head that its log ends with, which is the stored head for every version that
	   archive() or renew() made from it. Over nothing that is a log of nothing, as a renewal of a forged first version
	   has no previous version either. */
	private static boolean isMadeFrom(final GridSpork spork, final GridSpork stored) {
		if (Objects.isNull(stored)) {
			return isFirstVersion(spork);
		}

		/* Anyone can send a proposal, and refusing one must not take the stored data encoded or its head hashed,
		   which cost far more than the frame does. What the frame holds is judged first, then the data, and the head
		   last. */
		return mayEndWithTheHeadOf(spork, stored) && isOverTheVersionOf(spork, stored)
			&& hasTheDigestOf(spork.getSignatureLog().last(), stored);
	}

	private static boolean isOverTheVersionOf(final GridSpork spork, final GridSpork stored) {
		return isSameVersion(spork, spork.getPreviousTimeStamp(), spork.getPreviousData(), stored.getTimeStamp(),
			stored.getData()) || isRenewalOver(spork, stored);
	}

	/* The time stamps are judged before the data that they stand for is encoded */
	private static boolean isRenewalOver(final GridSpork spork, final GridSpork stored) {
		return millisOf(spork.getPreviousTimeStamp()) == millisOf(stored.getPreviousTimeStamp())
			&& isSameData(spork, spork.getData(), stored.getData())
			&& isSameData(spork, spork.getPreviousData(), stored.getPreviousData());
	}

	private static boolean endsWithTheHeadOf(final GridSpork spork, final GridSpork stored) {
		return mayEndWithTheHeadOf(spork, stored) && hasTheDigestOf(spork.getSignatureLog().last(), stored);
	}

	/* What is public of the stored head, which a log entry can copy without the stored data */
	private static boolean mayEndWithTheHeadOf(final GridSpork spork, final GridSpork stored) {
		final SignatureLog log = spork.getSignatureLog();

		return !log.isEmpty() && mayLogTheHeadOf(log.last(), stored);
	}

	/* The signatures are no part of what a head signs, so one version is had as heads that differ in them: a board
	   member co-signs a proposal twice, or whoever holds a head writes a signature of it in its other form. Neither
	   is newer, so a node keeps the one it has for as long as that one counts (isTheCopyThatCountsOf), and it tells
	   its head by the digest: what is made over any head of the version that it holds is made over its own. What an
	   entry shows of the head costs nothing and is judged before the digest: the time stamp, and a co-signature
	   where the head has one, as an entry without it logs a version that one key made. A head that one key signed,
	   as builds from before co-signatures made, is still told by the bytes of its signature. */
	private static boolean mayLogTheHeadOf(final SignatureLogEntry entry, final GridSpork stored) {
		return millisOf(entry.getTimeStamp()) == millisOf(stored.getTimeStamp())
			&& (stored.isPending() ? hasTheSignaturesOf(entry, stored) : entry.isCosigned());
	}

	/* As every node reads and signs a version: the stored spork of a node may come from a file, where an amount keeps
	   the scale it was written with and a time stamp its nanoseconds, while the wire carries units and milliseconds */
	private static boolean isSameVersion(final GridSpork spork, final Instant stamp, final ChunkData chunk,
		final Instant otherStamp, final ChunkData otherChunk) {

		return millisOf(stamp) == millisOf(otherStamp) && isSameData(spork, chunk, otherChunk);
	}

	private static boolean isSameData(final GridSpork spork, final ChunkData chunk, final ChunkData otherChunk) {
		return Arrays.equals(encoded(spork, chunk), encoded(spork, otherChunk));
	}

	private static long millisOf(final Instant stamp) {
		return Objects.requireNonNullElse(stamp, Instant.EPOCH).toEpochMilli();
	}

	private static byte[] encoded(final GridSpork spork, final ChunkData chunk) {
		return SporkContentEncoder.encode(spork.getType(), Objects.requireNonNullElseGet(chunk,
			() -> GridSpork.emptyDataOf(spork.getType())));
	}

	private static boolean succeeds(final GridSpork spork, final GridSpork stored, final BooleanSupplier mayVerify,
		final BooleanSupplier isSignedHead) {

		final GridSpork anchor = anchorAmong(stored);
		final SignatureLog storedLog = logOf(anchor);

		/* Head first, so a forged log costs one verification rather than one per entry */
		return followsOn(spork, stored, anchor, storedLog) && spork.isNewerThanLog() && mayVerify.getAsBoolean()
			&& isSignedHead.getAsBoolean()
			&& spork.getSignatureLog().isValidFrom(firstUncheckedEntry(spork, storedLog), spork.logKeys());
	}

	private static GridSpork anchorAmong(final GridSpork stored) {
		return Objects.nonNull(stored) && stored.isAnchoring() ? stored : null;
	}

	private static SignatureLog logOf(final GridSpork spork) {
		return Objects.isNull(spork) ? new SignatureLog() : spork.getSignatureLog();
	}

	/* A stored spork that no active key signed is no anchor: the version that the board signs again takes its place
	   without carrying its log on. It still holds its place in time, so that version is newer than it and than every
	   version in its log, and is none of them: a version that someone kept cannot take its data back. */
	private static boolean followsOn(final GridSpork spork, final GridSpork stored, final GridSpork anchor,
		final SignatureLog storedLog) {

		return (anchor == stored || isLaterThanAllOf(spork, stored)) && spork.isSuccessorOf(anchor, storedLog);
	}

	private static boolean isLaterThanAllOf(final GridSpork spork, final GridSpork stored) {
		final byte[] digest = DigestUtils.sha512(spork.getSignable());

		return Objects.nonNull(spork.getTimeStamp()) && spork.isNewerThan(stored)
			&& !Arrays.equals(digest, DigestUtils.sha512(stored.getSignable()))
			&& stored.getSignatureLog().getEntries().stream()
				.allMatch(entry -> isLaterThan(spork, entry, digest));
	}

	private static boolean isLaterThan(final GridSpork spork, final SignatureLogEntry entry, final byte[] digest) {
		return spork.getTimeStamp().isAfter(entry.getTimeStamp()) && !Arrays.equals(entry.getDigest(), digest);
	}

	/* The entries of the stored log were checked when the stored spork was taken; a log that does not carry it on is
	   checked whole */
	private static int firstUncheckedEntry(final GridSpork spork, final SignatureLog storedLog) {
		return storedLog.isPrefixOf(spork.getSignatureLog()) ? storedLog.size() : 0;
	}

	/* As GridSpork.isSuccessorOf() decides for a type with no rule of its own */
	static boolean isSuccessorByLogOf(final GridSpork spork, final GridSpork stored, final SignatureLog storedLog) {
		final SignatureLog log = spork.getSignatureLog();

		/* Whether the log carries on the stored one takes both logs hashed, so what the sizes, the time stamps and
		   the data tell comes first: every peer sends the version that a node holds again and again, and it is
		   not newer */
		return log.size() > storedLog.size() ? goesOnWithTheHeadOf(spork, stored, storedLog)
			: settlesOver(spork, stored) && mayTakeThePlaceOf(spork, stored) && storedLog.isPrefixOf(log);
	}

	/* Which of two versions made from one head wins: the newer one, but for a next version and a renewal. A renewal
	   has the data of the head it was made from, and a board member who renews the sporks of a node that has not
	   heard of a change yet makes one of the version before that change. A node that took it in the place of the
	   change would undo the change, and would refuse the version after it for good, as the log of that one names
	   the change. So a renewal never wins over a next version, and a next version wins over a renewal that is
	   newer than it, if that was made within the lifetime of a proposal after it. The proposals that a node holds
	   settle the same way (PendingSporks). The time stamps tell all of it, so a version that a node holds and is
	   sent again still costs nothing more than them. */
	static boolean settlesOver(final GridSpork spork, final GridSpork other) {
		return spork.isNewerThan(other) ? !(isRenewal(spork) && isNextVersion(other))
			: isNextVersion(spork) && isRenewal(other) && isWithinAProposalsLifetimeOf(other, spork);
	}

	/* A next version takes the time stamp of the head it was made from for its previous one (archive()), and that
	   head is what its log ends with. A renewal keeps the previous time stamp of the head that it renews (renew()),
	   which is that of an earlier version. With no log a version is neither: the first one, and one from before
	   there was a log. */
	private static boolean isNextVersion(final GridSpork spork) {
		return hasARetiredHead(spork) && endsItsLogWithItsPreviousVersion(spork);
	}

	private static boolean isRenewal(final GridSpork spork) {
		return hasARetiredHead(spork) && !endsItsLogWithItsPreviousVersion(spork);
	}

	private static boolean hasARetiredHead(final GridSpork spork) {
		return Objects.nonNull(spork) && !spork.getSignatureLog().isEmpty();
	}

	private static boolean endsItsLogWithItsPreviousVersion(final GridSpork spork) {
		return millisOf(spork.getPreviousTimeStamp()) == millisOf(spork.getSignatureLog().last().getTimeStamp());
	}

	/* A sibling takes the place of the stored spork without carrying it in its log, so one with other data undoes its
	   change unseen: a node that hands out the version before the stored one has a board member sign over it a
	   sibling that every node takes. Two co-signers racing over one version make siblings only while a proposal
	   lives, so one with other data is taken within that time after the stored spork: the network still settles on
	   one of them, the newer or the one that settlesOver() has win, and a rollback is held to the hour after a
	   change. Two versions that make the same change, as two renewals do, settle by time, and so does the first
	   version, which follows nothing. So do two first versions, with no previous time stamp and no log, however far
	   apart they were made, as board members may sign the data of a database again hours apart: an older one never
	   wins back its place, a new one takes two board keys, and the command line makes one only on the word of the
	   operator, as a node can hide what it holds. */
	private static boolean mayTakeThePlaceOf(final GridSpork spork, final GridSpork stored) {
		if (Objects.isNull(stored)) {
			return true;
		}

		return isOverTheSameVersionAs(spork, stored) && (Objects.equals(spork.getData(), stored.getData())
			|| isFirstVersion(spork) || isWithinAProposalsLifetimeOf(spork, stored));
	}

	/* A stored log with entries ends with the head that the stored spork was made from, and a sibling has that very
	   log, which is judged right after this, so the log tells that both were made from one version. The previous data
	   and time stamp do not: a renewal keeps those of the head it was made from, while a next version takes the data
	   and time stamp of that head, so the two differ in them over one version. With no log, as a first version has
	   and a version from before there was one, they are all that tells. */
	private static boolean isOverTheSameVersionAs(final GridSpork spork, final GridSpork stored) {
		return !stored.getSignatureLog().isEmpty()
			|| Objects.equals(spork.getPreviousData(), stored.getPreviousData())
			&& Objects.equals(spork.getPreviousTimeStamp(), stored.getPreviousTimeStamp());
	}

	/* A sibling has the log of the stored spork, so this one being a first version makes both one */
	private static boolean isFirstVersion(final GridSpork spork) {
		return Instant.EPOCH.equals(spork.getPreviousTimeStamp()) && spork.getSignatureLog().isEmpty();
	}

	private static boolean isWithinAProposalsLifetimeOf(final GridSpork spork, final GridSpork stored) {
		return Objects.nonNull(stored.getTimeStamp())
			&& !spork.getTimeStamp().isAfter(stored.getTimeStamp().plus(PendingSporks.LIFETIME));
	}

	/* An entry may carry a single signature, which one key alone can make, so a version that the network never took
	   could follow the stored log as an entry, and a version over it would pass for one over the stored spork. A node
	   that missed versions still finds its own head where its log ends. What the entry shows of the stored head is
	   judged first, as that costs nothing. It is public, so an entry can copy it, and a log that does not carry on the
	   stored one is refused before the stored head is hashed, which costs the stored data encoded and the stored log
	   hashed. */
	private static boolean goesOnWithTheHeadOf(final GridSpork spork, final GridSpork stored,
		final SignatureLog storedLog) {

		final SignatureLog log = spork.getSignatureLog();

		if (Objects.isNull(stored)) {
			return storedLog.isPrefixOf(log);
		}

		final SignatureLogEntry next = log.getEntries().get(storedLog.size());

		return mayLogTheHeadOf(next, stored) && storedLog.isPrefixOf(log) && hasTheDigestOf(next, stored);
	}

	private static boolean hasTheSignaturesOf(final SignatureLogEntry entry, final GridSpork stored) {
		return Arrays.equals(entry.getSignature(), stored.getSignature())
			&& Arrays.equals(entry.getCosignature(), stored.getCosignature());
	}

	private static boolean hasTheDigestOf(final SignatureLogEntry next, final GridSpork stored) {
		return Arrays.equals(next.getDigest(), DigestUtils.sha512(stored.getSignable()));
	}
}

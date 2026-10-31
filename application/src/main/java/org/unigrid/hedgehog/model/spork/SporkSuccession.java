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
		return succeeds(spork, stored, mayVerify, spork::isDoublySigned);
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
			return Instant.EPOCH.equals(spork.getPreviousTimeStamp()) && spork.getSignatureLog().isEmpty();
		}

		final boolean isNextVersion = isSameVersion(spork, spork.getPreviousTimeStamp(), spork.getPreviousData(),
			stored.getTimeStamp(), stored.getData());

		return (isNextVersion || isRenewalOver(spork, stored)) && endsWithTheHeadOf(spork, stored);
	}

	private static boolean isRenewalOver(final GridSpork spork, final GridSpork stored) {
		return Arrays.equals(encoded(spork, spork.getData()), encoded(spork, stored.getData()))
			&& isSameVersion(spork, spork.getPreviousTimeStamp(), spork.getPreviousData(),
				stored.getPreviousTimeStamp(), stored.getPreviousData());
	}

	private static boolean endsWithTheHeadOf(final GridSpork spork, final GridSpork stored) {
		final SignatureLog log = spork.getSignatureLog();

		return !log.isEmpty() && millisOf(log.last().getTimeStamp()) == millisOf(stored.getTimeStamp())
			&& goesOnWithTheHeadOf(spork, stored, log.size() - 1);
	}

	/* As every node reads and signs a version: the stored spork of a node may come from a file, where an amount keeps
	   the scale it was written with and a time stamp its nanoseconds, while the wire carries units and milliseconds */
	private static boolean isSameVersion(final GridSpork spork, final Instant stamp, final ChunkData chunk,
		final Instant otherStamp, final ChunkData otherChunk) {

		return millisOf(stamp) == millisOf(otherStamp)
			&& Arrays.equals(encoded(spork, chunk), encoded(spork, otherChunk));
	}

	private static long millisOf(final Instant stamp) {
		return Objects.requireNonNullElse(stamp, Instant.EPOCH).toEpochMilli();
	}

	private static byte[] encoded(final GridSpork spork, final ChunkData chunk) {
		return SporkContentEncoder.encode(spork.getType(), Objects.requireNonNullElseGet(chunk,
			() -> GridSpork.create(spork.getType()).<ChunkData>getData().empty()));
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
		final boolean isNewerSibling = log.size() == storedLog.size() && spork.isNewerThan(stored)
			&& mayTakeThePlaceOf(spork, stored);
		final boolean isLater = log.size() > storedLog.size()
			&& goesOnWithTheHeadOf(spork, stored, storedLog.size());

		return (isLater || isNewerSibling) && storedLog.isPrefixOf(log);
	}

	/* A sibling takes the place of the stored spork without carrying it in its log, so one with other data undoes its
	   change unseen: a node that hands out the version before the stored one has a board member sign over it a
	   sibling that every node takes. Two co-signers racing over one version make siblings only while a proposal
	   lives, so one with other data is taken within that time after the stored spork: the network still settles on
	   the newer of them, and a rollback is held to the hour after a change. Two versions that make the same change,
	   as two renewals do, settle by time, and so does the first version, which follows nothing. So do two first
	   versions, with no previous time stamp and no log, however far apart they were made, as board members may sign
	   the data of a database again hours apart: an older one never wins back its place, a new one takes two board
	   keys, and the command line makes one only on the word of the operator, as a node can hide what it holds. */
	private static boolean mayTakeThePlaceOf(final GridSpork spork, final GridSpork stored) {
		if (Objects.isNull(stored)) {
			return true;
		}

		final boolean isOverTheSameVersion = Objects.equals(spork.getPreviousData(), stored.getPreviousData())
			&& Objects.equals(spork.getPreviousTimeStamp(), stored.getPreviousTimeStamp());

		return isOverTheSameVersion && (Objects.equals(spork.getData(), stored.getData()) || isFirstVersion(spork)
			|| isWithinAProposalsLifetimeOf(spork, stored));
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
	   that missed versions still finds its own head where its log ends. */
	private static boolean goesOnWithTheHeadOf(final GridSpork spork, final GridSpork stored, final int index) {
		if (Objects.isNull(stored)) {
			return true;
		}

		final SignatureLogEntry next = spork.getSignatureLog().getEntries().get(index);

		return Arrays.equals(next.getSignature(), stored.getSignature())
			&& Arrays.equals(next.getCosignature(), stored.getCosignature())
			&& Arrays.equals(next.getDigest(), DigestUtils.sha512(stored.getSignable()));
	}
}

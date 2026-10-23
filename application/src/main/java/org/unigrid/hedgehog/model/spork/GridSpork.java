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

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.ByteArrayOutputStream;
import java.io.Serializable;
import java.security.InvalidAlgorithmParameterException;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.stream.Stream;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Tolerate;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.SerializationUtils;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.crypto.Signable;
import org.unigrid.hedgehog.model.crypto.Signature;
import org.unigrid.hedgehog.model.crypto.SigningException;

@Data
public class GridSpork implements Serializable, Signable {
	private static final long serialVersionUID = 3180522314476687567L;

	@JsonFormat(shape = JsonFormat.Shape.STRING)
	private Instant timeStamp;

	@JsonFormat(shape = JsonFormat.Shape.STRING)
	private Instant previousTimeStamp;

	private short flags; /* Put Flag values in here */

	@JsonProperty(access = JsonProperty.Access.READ_ONLY)
	private Type type;

	private ChunkData data;
	private ChunkData previousData; /* Flag.DELTA controls the content */
	@Getter private byte[] signature;
	private byte[] cosignature;

	@JsonIgnore
	private SignatureLog signatureLog;

	@Getter(AccessLevel.NONE) @Setter(AccessLevel.NONE)
	private transient SignatureLogEntry retiringHead;

	@Getter(AccessLevel.NONE) @Setter(AccessLevel.NONE) @ToString.Exclude
	private transient volatile Standing standing;

	/* Which of the keys active now signed the head, worked out once until the keys that the node goes by change: the
	   policy of a spork is read thousands of times a second, and a spork that a node holds is replaced whole and never
	   changed in place */
	private record Standing(long generation, boolean counting, boolean anchoring) {
	}

	@AllArgsConstructor
	public enum Flag {
		GOVERNED((short) 0x01),	/* Governed sporks have to be voted on to accept the change on the network */
		DELTA((short) 0x02),	/* Is either delta-data or a raw representation of the previous value */
		WIRE_SIGNABLE((short) 0x04);	/* Signed over its wire encoding rather than its Java serialization */

		@Getter private final short value;
	}

	@AllArgsConstructor
	public enum Type {
		UNDEFINED((short) 0, () -> {
			throw new IllegalArgumentException("Unknown spork type supplied");
		}),
		MINT_STORAGE((short) 1000, MintStorage::new), MINT_SUPPLY((short) 1010, MintSupply::new),
		VESTING_STORAGE((short) 1020, VestingStorage::new), STORAGE((short) 1030, StorageSpork::new),
		LEDGER_BLACKLIST((short) 1040, LedgerBlacklist::new), LEDGER_FEE((short) 1050, LedgerFee::new),
		STATISTICS_PUBKEY((short) 2001, StatisticsPubKey::new), BOARD_KEYS((short) 3000, BoardKeys::new);

		@Getter private final short value;
		private final Supplier<GridSpork> factory;

		public static Type get(short value) {
			return Arrays.stream(values()).filter(type -> type.value == value).findFirst().orElse(UNDEFINED);
		}
	}

	/* Sporks stored by builds from before the log existed deserialize with a null log */
	public SignatureLog getSignatureLog() {
		if (Objects.isNull(signatureLog)) {
			signatureLog = new SignatureLog();
		}

		return signatureLog;
	}

	public <T extends ChunkData> T getData() {
		return (T) data;
	}

	public <T extends ChunkData> T getPreviousData() {
		return (T) previousData;
	}

	@Tolerate
	public void setType(short value) {
		type = Type.get(value);
	}

	public static GridSpork create(Type type) {
		return type.factory.get();
	}

	/**
	* Starts the next version of a spork from the stored one. The data of a proposal still awaiting its
	* co-signature is carried over, so several changes can be proposed in a row and co-signed once. The version
	* is newer than both, even when it is made within the millisecond of one of them.
	*/
	public static GridSpork nextVersion(Type type, GridSpork stored, Optional<? extends GridSpork> proposal) {
		final GridSpork spork = Objects.isNull(stored) ? create(type) : SerializationUtils.clone(stored);

		spork.archive();
		proposal.ifPresent(held -> spork.setData(SerializationUtils.clone(held.<ChunkData>getData())));

		Stream.concat(Stream.ofNullable(stored), proposal.stream()).map(GridSpork::getTimeStamp)
			.filter(Objects::nonNull).max(Comparator.naturalOrder())
			.filter(latest -> !spork.timeStamp.isAfter(latest))
			.ifPresent(latest -> spork.timeStamp = latest.plusMillis(1));

		return spork;
	}

	/**
	* Starts a first version of the data of a spork: no previous data, no signature log and no signature, made now.
	* It stands in for a spork that no current network key signed, which the board can no longer renew, and which a
	* node holds nothing of. A spork stamped ahead of the clock is refused rather than outdone: a version stamped
	* after it would win over every version the board makes until that time comes.
	*/
	public static GridSpork firstVersionOf(GridSpork spork) {
		final GridSpork first = create(spork.type);
		final Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

		if (Objects.nonNull(spork.timeStamp) && !spork.timeStamp.isBefore(now)) {
			throw new IllegalArgumentException("The " + spork.type + " spork is stamped at " + spork.timeStamp
				+ ", ahead of the clock of this machine");
		}

		first.flags |= Flag.WIRE_SIGNABLE.getValue();
		first.setData(SerializationUtils.clone(spork.<ChunkData>getData()));
		first.setPreviousData(first.<ChunkData>getData().empty());
		first.setPreviousTimeStamp(Instant.EPOCH);
		first.setTimeStamp(now);
		return first;
	}

	@JsonIgnore
	public boolean isNewerThan(GridSpork otherSpork) {
		if (Objects.isNull(otherSpork) || Objects.isNull(otherSpork.timeStamp)) {
			return Objects.nonNull(timeStamp);
		}

		if (Objects.isNull(timeStamp)) {
			return !(Objects.nonNull(otherSpork) && Objects.nonNull(otherSpork.timeStamp));
		}

		return timeStamp.isAfter(otherSpork.timeStamp);
	}

	@Override
	@JsonIgnore
	public byte[] getSignable() {
		final ByteArrayOutputStream stream = new ByteArrayOutputStream();

		/* Sporks signed by earlier builds cover their Java serialization, which depends on object identity */
		if (isWireSignable()) {
			stream.writeBytes(SporkContentEncoder.encode(this));
		} else {
			writeJavaSerialized(stream);
		}

		/* An empty log adds nothing, so signatures made before the log existed still verify */
		if (!getSignatureLog().isEmpty()) {
			stream.writeBytes(getSignatureLog().headHash());
		}

		return stream.toByteArray();
	}

	private boolean isWireSignable() {
		return (flags & Flag.WIRE_SIGNABLE.getValue()) != 0;
	}

	private void writeJavaSerialized(ByteArrayOutputStream stream) {
		stream.writeBytes(SerializationUtils.serialize(timeStamp));
		stream.writeBytes(SerializationUtils.serialize(previousTimeStamp));
		stream.writeBytes(SerializationUtils.serialize(flags));
		stream.writeBytes(SerializationUtils.serialize(type));
		stream.writeBytes(SerializationUtils.serialize(data));
		stream.writeBytes(SerializationUtils.serialize(previousData));
	}

	@Override
	public void sign(String privateKeyHex) throws SigningException {
		logRetiringHead();
		flags |= Flag.WIRE_SIGNABLE.getValue();
		signature = signatureOf(privateKeyHex);
		cosignature = null;
	}

	/**
	* Adds the second signature a spork needs before the network accepts it. It covers the same bytes as
	* the first one, and has to come from a different current network key.
	*/
	public void cosign(String privateKeyHex) throws SigningException {
		if (Objects.isNull(signature) || !isPending()) {
			throw new SigningException("Only a spork signed by exactly one network key can be co-signed");
		}

		cosignature = signatureOf(privateKeyHex);

		if (!isDoublySigned()) {
			cosignature = null;
			throw new SigningException("A second, different network key has to co-sign the spork");
		}
	}

	private byte[] signatureOf(String privateKeyHex) throws SigningException {
		try {
			return new Signature(Optional.of(privateKeyHex), Optional.empty()).sign(getSignable());
		} catch (InvalidAlgorithmParameterException | InvalidKeySpecException | NoSuchAlgorithmException ex) {
			throw new SigningException("Failed to sign spork with given private key", ex);
		}
	}

	@JsonIgnore
	public boolean isPending() {
		return Objects.isNull(cosignature);
	}

	@JsonIgnore
	public boolean isValidSignature() {
		return signerOfHead(signature).isPresent();
	}

	@JsonIgnore
	public boolean isDoublySigned() {
		return isDoublySignedBy(headKeys());
	}

	private boolean isDoublySignedBy(Set<String> keys) {
		return isPair(signerOfHead(signature, keys), signerOfHead(cosignature, keys));
	}

	private static boolean isPair(Optional<String> signer, Optional<String> cosigner) {
		return signer.isPresent() && cosigner.isPresent() && !signer.equals(cosigner);
	}

	/* The current network key that made a signature of this spork, if any did */
	public Optional<String> signerOfHead(byte[] headSignature) {
		return signerOfHead(headSignature, headKeys());
	}

	private Optional<String> signerOfHead(byte[] headSignature, Set<String> keys) {
		return Objects.isNull(headSignature) ? Optional.empty()
			: NetworkKey.signerOf(DigestUtils.sha512(getSignable()), headSignature, keys);
	}

	/* The keys that sign the head of the spork: the board keys that are active now */
	protected Set<String> headKeys() {
		return Set.copyOf(Arrays.asList(NetworkKey.getPublicKeys()));
	}

	/* The keys that its signature log may name */
	protected Set<String> logKeys() {
		return NetworkKey.getLogKeys();
	}

	/**
	* Decides whether this spork, received from the network, may replace the stored one. It must be signed
	* by two different current network keys, carry the stored log unchanged, add only entries signed by
	* current keys, and either extend the log, with the stored spork as the first entry it adds, or win against
	* a sibling made over the same version by being newer, when it makes the same change or comes within the
	* lifetime of a proposal after it. A stored spork that no active key signed ({@link #isAnchoring()}) is taken over
	* by any version newer than it and than every version in its log.
	*/
	@JsonIgnore
	public boolean canReplace(GridSpork stored) {
		return canReplace(stored, () -> true);
	}

	/**
	* Decides as {@link #canReplace(GridSpork)} does, but asks {@code mayVerify} right before the first signature
	* is checked and refuses the spork if the answer is no. A signature costs far more than the other checks, so a
	* spork that fails those costs no permission.
	*/
	@JsonIgnore
	public boolean canReplace(GridSpork stored, BooleanSupplier mayVerify) {
		return succeeds(stored, mayVerify, this::isDoublySigned);
	}

	/**
	* Decides whether this spork, signed once, may wait for its co-signature as a proposal to replace the
	* stored one. It has to pass every check of {@link #canReplace(GridSpork)} except the second signature.
	*/
	@JsonIgnore
	public boolean canBeProposedOver(GridSpork stored) {
		return canBeProposedOver(stored, () -> true);
	}

	/**
	* Decides as {@link #canBeProposedOver(GridSpork)} does, asking {@code mayVerify} as
	* {@link #canReplace(GridSpork, BooleanSupplier)} does.
	*/
	@JsonIgnore
	public boolean canBeProposedOver(GridSpork stored, BooleanSupplier mayVerify) {
		return isPending() && succeeds(stored, mayVerify, this::isValidSignature);
	}

	/**
	* Decides whether this spork, handed out as the one a node stores, is one the network took: signed by a current
	* network key and co-signed by a second one, newer than the last version in its signature log, and with a log that
	* current keys signed. A single signature, as builds from before co-signatures made, never counts: one key alone
	* could make it. A board member builds on such a spork only, so that nothing between the node and the network can
	* have them sign on top of a version no board made, and a node keeps no other. A spork that cannot be encoded to
	* check its signatures over, as one of no known type, is none the network took, and nor is one that holds an amount
	* beyond the bounds of the wire, which no node sends.
	*/
	@JsonIgnore
	public boolean isTakenByTheNetwork() {
		return isTakenBy(headKeys());
	}

	/**
	* Decides as {@link #isTakenByTheNetwork()} does, but takes two keys that were ever active for the head: a spork
	* that keys signed which the board switched off since is still one the network carried. A node keeps it where it
	* is, and it counts for nothing ({@link #isCounting()}) until the board signs it again.
	*/
	@JsonIgnore
	public boolean wasTakenByTheNetwork() {
		return isTakenBy(logKeys());
	}

	private boolean isTakenBy(Set<String> keys) {
		try {
			return isWithinBounds() && isDoublySignedBy(keys) && isNewerThanLog()
				&& getSignatureLog().isValidFrom(0, logKeys());
		} catch (RuntimeException ex) {
			return false;
		}
	}

	/**
	* Whether the policy of this spork holds: two different keys that are active now signed it. A spork whose signer
	* the board switched off since stays where it is, but decides nothing and is not sent on until the board signs it
	* again.
	*/
	@JsonProperty(access = JsonProperty.Access.READ_ONLY)
	public boolean isCounting() {
		return standing().counting();
	}

	/* Whether a new version has to follow on this one. One that no active key signed holds nothing back, so the version
	   that the board signs again takes its place, while one that an active key signed still keeps older versions out. */
	@JsonIgnore
	public boolean isAnchoring() {
		return standing().anchoring();
	}

	/* The generation is read before the keys, so a standing worked out over keys that changed meanwhile is worked out
	   again. A spork that cannot be encoded to check its signatures over has no signer. */
	private Standing standing() {
		final long generation = NetworkKey.getGeneration();
		final Standing known = standing;

		if (Objects.nonNull(known) && known.generation() == generation) {
			return known;
		}

		Standing judged;

		try {
			judged = judgedBy(generation, headKeys());
		} catch (RuntimeException ex) {
			judged = new Standing(generation, false, false);
		}

		standing = judged;
		return judged;
	}

	private Standing judgedBy(long generation, Set<String> keys) {
		final Optional<String> signer = signerOfHead(signature, keys);
		final Optional<String> cosigner = signerOfHead(cosignature, keys);

		return new Standing(generation, isPair(signer, cosigner), signer.isPresent() || cosigner.isPresent());
	}

	/* An amount beyond the bounds of the wire writes out as up to gigabytes, so it is judged before the spork is
	   encoded, digested or shown */
	@JsonIgnore
	public boolean isWithinBounds() {
		return Stream.of(data, previousData).filter(Objects::nonNull).allMatch(ChunkData::amountsAreWithinBounds);
	}

	/**
	* Decides whether this spork, handed out as the proposal that waits on the stored one, is what a current network
	* key proposed over it: it may wait for its co-signature over the stored spork, and it was made from it, as its
	* next version or as its renewal. The stored spork is one the network took ({@link #isTakenByTheNetwork()}).
	*/
	@JsonIgnore
	public boolean isProposalOver(GridSpork stored) {
		return canBeProposedOver(stored) && isMadeFrom(stored);
	}

	private boolean isMadeFrom(GridSpork stored) {
		if (Objects.isNull(stored)) {
			return Instant.EPOCH.equals(previousTimeStamp);
		}

		final boolean isNextVersion = Objects.equals(previousTimeStamp, stored.timeStamp)
			&& Objects.equals(previousData, stored.data);
		final boolean isRenewal = Objects.equals(data, stored.data)
			&& Objects.equals(previousData, stored.previousData)
			&& Objects.equals(previousTimeStamp, stored.previousTimeStamp);

		return isNextVersion || isRenewal;
	}

	private boolean succeeds(GridSpork stored, BooleanSupplier mayVerify, BooleanSupplier isSignedHead) {
		final GridSpork anchor = anchorAmong(stored);
		final SignatureLog storedLog = logOf(anchor);

		/* Head first, so a forged log costs one verification rather than one per entry */
		return followsOn(stored, anchor, storedLog) && isNewerThanLog() && mayVerify.getAsBoolean()
			&& isSignedHead.getAsBoolean()
			&& getSignatureLog().isValidFrom(firstUncheckedEntry(storedLog), logKeys());
	}

	private static GridSpork anchorAmong(GridSpork stored) {
		return Objects.nonNull(stored) && stored.isAnchoring() ? stored : null;
	}

	private static SignatureLog logOf(GridSpork spork) {
		return Objects.isNull(spork) ? new SignatureLog() : spork.getSignatureLog();
	}

	/* A stored spork that no active key signed is no anchor: the version that the board signs again takes its place
	   without carrying its log on. It still holds its place in time, so that version is newer than it and than every
	   version in its log, and is none of them: a version that someone kept cannot take its data back. */
	private boolean followsOn(GridSpork stored, GridSpork anchor, SignatureLog storedLog) {
		return (anchor == stored || isLaterThanAllOf(stored)) && isSuccessorOf(anchor, storedLog);
	}

	private boolean isLaterThanAllOf(GridSpork stored) {
		final byte[] digest = DigestUtils.sha512(getSignable());

		return Objects.nonNull(timeStamp) && isNewerThan(stored)
			&& !Arrays.equals(digest, DigestUtils.sha512(stored.getSignable()))
			&& stored.getSignatureLog().getEntries().stream().allMatch(entry -> isLaterThan(entry, digest));
	}

	private boolean isLaterThan(SignatureLogEntry entry, byte[] digest) {
		return timeStamp.isAfter(entry.getTimeStamp()) && !Arrays.equals(entry.getDigest(), digest);
	}

	/* The entries of the stored log were checked when the stored spork was taken; a log that does not carry it on is
	   checked whole */
	private int firstUncheckedEntry(SignatureLog storedLog) {
		return storedLog.isPrefixOf(getSignatureLog()) ? storedLog.size() : 0;
	}

	/* Whether this spork may follow on the stored one, judged by what costs no signature */
	protected boolean isSuccessorOf(GridSpork stored, SignatureLog storedLog) {
		final SignatureLog log = getSignatureLog();
		final boolean isNewerSibling = log.size() == storedLog.size() && isNewerThan(stored)
			&& mayTakeThePlaceOf(stored);
		final boolean isLater = log.size() > storedLog.size() && goesOnWithTheHeadOf(stored, storedLog.size());

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
	private boolean mayTakeThePlaceOf(GridSpork stored) {
		if (Objects.isNull(stored)) {
			return true;
		}

		final boolean isOverTheSameVersion = Objects.equals(previousData, stored.previousData)
			&& Objects.equals(previousTimeStamp, stored.previousTimeStamp);

		return isOverTheSameVersion && (Objects.equals(data, stored.data) || isFirstVersion()
			|| isWithinAProposalsLifetimeOf(stored));
	}

	/* A sibling has the log of the stored spork, so this one being a first version makes both one */
	private boolean isFirstVersion() {
		return Instant.EPOCH.equals(previousTimeStamp) && getSignatureLog().isEmpty();
	}

	private boolean isWithinAProposalsLifetimeOf(GridSpork stored) {
		return Objects.nonNull(stored.timeStamp)
			&& !timeStamp.isAfter(stored.timeStamp.plus(PendingSporks.LIFETIME));
	}

	/* An entry may carry a single signature, which one key alone can make, so a version that the network never took
	   could follow the stored log as an entry, and a version over it would pass for one over the stored spork. A node
	   that missed versions still finds its own head where its log ends. */
	private boolean goesOnWithTheHeadOf(GridSpork stored, int index) {
		if (Objects.isNull(stored)) {
			return true;
		}

		final SignatureLogEntry next = getSignatureLog().getEntries().get(index);

		return Arrays.equals(next.getSignature(), stored.signature)
			&& Arrays.equals(next.getCosignature(), stored.cosignature)
			&& Arrays.equals(next.getDigest(), DigestUtils.sha512(stored.getSignable()));
	}

	private boolean isNewerThanLog() {
		return getSignatureLog().isEmpty()
			|| Objects.nonNull(timeStamp) && timeStamp.isAfter(getSignatureLog().last().getTimeStamp());
	}

	private void retireHead() {
		if (Objects.nonNull(signature) && Objects.isNull(retiringHead)) {
			retiringHead = SignatureLogEntry.builder().timeStamp(timeStamp)
				.digest(DigestUtils.sha512(getSignable()))
				.signature(signature).cosignature(cosignature).build();
		}
	}

	private void logRetiringHead() throws SigningException {
		if (Objects.nonNull(retiringHead)) {
			final String signer = retiringSignerOf(retiringHead.getSignature());
			final String cosigner = Objects.isNull(retiringHead.getCosignature()) ? null
				: retiringSignerOf(retiringHead.getCosignature());

			getSignatureLog().append(retiringHead.toBuilder().signer(signer).cosigner(cosigner).build());
			retiringHead = null;
		}
	}

	/* By any key that the log may name, so that a version that a key signed before it was switched off is signed again
	   with its history */
	private String retiringSignerOf(byte[] headSignature) throws SigningException {
		return NetworkKey.signerOf(retiringHead.getDigest(), headSignature, logKeys()).orElseThrow(
			() -> new SigningException("No board key signed the version being replaced")
		);
	}

	/**
	* Moves the spork to a new point in time without changing its data or its history, so it can be
	* re-signed and still win {@link #isNewerThan(GridSpork)} against the copy it replaces. The version
	* it replaces enters the signature log when the spork is signed again.
	*/
	public void renew() {
		retireHead();
		timeStamp = Instant.now().truncatedTo(ChronoUnit.MILLIS);
	}

	/**
	* Archives the spork by copying {@link #data} and {@link #timeStamp} to {@link #previousData}
	* and {@link #previousTimeStamp}. This should typically be done right before the spork is
	* populated with new values. This method will also update the current timeStamp to `{@code Instant.now()}.
	*/
	public void archive() {
		retireHead();
		previousData = SerializationUtils.clone(data);
		previousTimeStamp = SerializationUtils.clone(timeStamp);
		timeStamp = Instant.now().truncatedTo(ChronoUnit.MILLIS);

		/* Make sure we don't haver empty null properties (instead, we give them a reasonable default "zero") */

		if (Objects.isNull(previousData)) {
			previousData = previousData.empty();
		}

		if (Objects.isNull(previousTimeStamp)) {
			previousTimeStamp = Instant.EPOCH.truncatedTo(ChronoUnit.MILLIS);
		}
	}
}

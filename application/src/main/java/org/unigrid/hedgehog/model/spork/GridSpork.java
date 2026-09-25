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
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Tolerate;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.SerializationUtils;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.crypto.Signable;
import org.unigrid.hedgehog.model.crypto.Signature;
import org.unigrid.hedgehog.model.crypto.SigningException;
import org.unigrid.hedgehog.model.network.chunk.ChunkData;

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

	@AllArgsConstructor
	public enum Flag {
		GOVERNED((short) 0x01),	/* Governed sporks have to be voted on to accept the change on the network */
		DELTA((short) 0x02);	/* Is either delta-data or a raw representation of the previous value */

		@Getter private final short value;
	}

	@AllArgsConstructor
	public enum Type {
		UNDEFINED((short) 0), MINT_STORAGE((short) 1000), MINT_SUPPLY((short) 1010), VESTING_STORAGE((short) 1020),
		STATISTICS_PUBKEY(((short) 2001));

		@Getter private final short value;

		public static Type get(short value) {
			switch (value) {
				case 1000: return MINT_STORAGE;
				case 1010: return MINT_SUPPLY;
				case 1020: return VESTING_STORAGE;
				case 2001: return STATISTICS_PUBKEY;
				default: return UNDEFINED;
			}
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
		switch (type) {
			case MINT_STORAGE: return new MintStorage();
			case MINT_SUPPLY: return new MintSupply();
			case VESTING_STORAGE: return new VestingStorage();
			case STATISTICS_PUBKEY: return new StatisticsPubKey();
			default: throw new IllegalArgumentException("Unknown spork type supplied");
		}
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

		stream.writeBytes(SerializationUtils.serialize(timeStamp));
		stream.writeBytes(SerializationUtils.serialize(previousTimeStamp));
		stream.writeBytes(SerializationUtils.serialize(flags));
		stream.writeBytes(SerializationUtils.serialize(type));
		stream.writeBytes(SerializationUtils.serialize(data));
		stream.writeBytes(SerializationUtils.serialize(previousData));

		/* An empty log adds nothing, so signatures made before the log existed still verify */
		if (!getSignatureLog().isEmpty()) {
			stream.writeBytes(getSignatureLog().headHash());
		}

		return stream.toByteArray();
	}

	@Override
	public void sign(String privateKeyHex) throws SigningException {
		logRetiringHead();
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
		return currentSignerOf(signature).isPresent();
	}

	@JsonIgnore
	public boolean isDoublySigned() {
		final Optional<String> signer = currentSignerOf(signature);
		final Optional<String> cosigner = currentSignerOf(cosignature);

		return signer.isPresent() && cosigner.isPresent() && !signer.equals(cosigner);
	}

	private Optional<String> currentSignerOf(byte[] headSignature) {
		return Objects.isNull(headSignature) ? Optional.empty()
			: NetworkKey.currentSignerOf(DigestUtils.sha512(getSignable()), headSignature);
	}

	/**
	* Decides whether this spork, received from the network, may replace the stored one. It must be signed
	* by two different current network keys, carry the stored log unchanged, add only entries signed by
	* known keys, and either extend the log or win against a sibling signed on top of the same log by being
	* newer.
	*/
	@JsonIgnore
	public boolean canReplace(GridSpork stored) {
		return succeeds(stored, this::isDoublySigned);
	}

	/**
	* Decides whether this spork, signed once, may wait for its co-signature as a proposal to replace the
	* stored one. It has to pass every check of {@link #canReplace(GridSpork)} except the second signature.
	*/
	@JsonIgnore
	public boolean canBeProposedOver(GridSpork stored) {
		return isPending() && succeeds(stored, this::isValidSignature);
	}

	private boolean succeeds(GridSpork stored, BooleanSupplier isSignedHead) {
		final SignatureLog storedLog = Objects.isNull(stored) ? new SignatureLog() : stored.getSignatureLog();

		/* Head first, so a forged log costs one verification rather than one per entry */
		return isSuccessorOf(stored, storedLog) && isNewerThanLog() && isSignedHead.getAsBoolean()
			&& getSignatureLog().isValidFrom(storedLog.size(), NetworkKey.getKnownPublicKeys());
	}

	private boolean isSuccessorOf(GridSpork stored, SignatureLog storedLog) {
		final SignatureLog log = getSignatureLog();
		final boolean isNewerSibling = log.size() == storedLog.size() && isNewerThan(stored);

		return (log.size() > storedLog.size() || isNewerSibling) && storedLog.isPrefixOf(log);
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
			final String signer = knownSignerOf(retiringHead.getSignature());
			final String cosigner = Objects.isNull(retiringHead.getCosignature()) ? null
				: knownSignerOf(retiringHead.getCosignature());

			getSignatureLog().append(retiringHead.toBuilder().signer(signer).cosigner(cosigner).build());
			retiringHead = null;
		}
	}

	private String knownSignerOf(byte[] headSignature) throws SigningException {
		return NetworkKey.signerOf(retiringHead.getDigest(), headSignature).orElseThrow(() -> new SigningException(
			"No known network key signed the version being replaced"
		));
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

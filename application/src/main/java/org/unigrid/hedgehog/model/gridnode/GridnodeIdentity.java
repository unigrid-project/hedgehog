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

package org.unigrid.hedgehog.model.gridnode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.Optional;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.unigrid.hedgehog.model.crypto.Signature;
import org.unigrid.hedgehog.model.crypto.SigningException;
import org.unigrid.hedgehog.model.crypto.VerifySignatureException;

@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class GridnodeIdentity {
	private static final String PRIVATE_LABEL = "Private Key:";
	private static final String PUBLIC_LABEL = "Public Key:";
	private static final byte[] PROBE = "hh-gridnode-key-check".getBytes(StandardCharsets.UTF_8);

	private final Optional<Signature> key;
	private long lastTimestamp;

	public static GridnodeIdentity none() {
		return new GridnodeIdentity(Optional.empty());
	}

	public static GridnodeIdentity of(Signature key) {
		return new GridnodeIdentity(Optional.of(key));
	}

	public static GridnodeIdentity load(Path keyFile) {
		requireOwnerOnly(keyFile);

		final List<String> lines = readLines(keyFile);

		return of(pairOf(valueOf(lines, PRIVATE_LABEL, keyFile), valueOf(lines, PUBLIC_LABEL, keyFile), keyFile));
	}

	public Optional<String> id() {
		return key.map(Signature::getPublicKey);
	}

	/* The time only ever grows, and beats what other nodes still hold for this key, or they would drop the entry */
	public synchronized Gridnode sign(Gridnode.Status status, String hostName, long clockMillis, long newerThan) {
		final Signature signature = key.orElseThrow(() -> new IllegalStateException("This node has no gridnode key")
		);
		final Gridnode gridnode = Gridnode.builder().id(signature.getPublicKey()).status(status).hostName(hostName)
			.timestamp(Math.max(clockMillis, Math.max(lastTimestamp, newerThan) + 1)).build();

		lastTimestamp = gridnode.getTimestamp();

		try {
			gridnode.setSignature(signature.sign(GridnodeSignature.message(gridnode)));
		} catch (SigningException ex) {
			throw new IllegalStateException("Could not sign the gridnode entry", ex);
		}

		return gridnode;
	}

	private static void requireOwnerOnly(Path keyFile) {
		final PosixFileAttributeView view = Files.getFileAttributeView(keyFile, PosixFileAttributeView.class);

		if (view == null) {
			return;
		}

		try {
			if (view.readAttributes().permissions().stream().anyMatch(GridnodeIdentity::isGroupOrOther)) {
				throw new IllegalArgumentException(keyFile + " must be readable by its owner only, "
					+ "try chmod 600");
			}
		} catch (IOException ex) {
			throw unreadable(keyFile, ex);
		}
	}

	private static boolean isGroupOrOther(PosixFilePermission permission) {
		return permission.name().startsWith("GROUP_") || permission.name().startsWith("OTHERS_");
	}

	private static List<String> readLines(Path keyFile) {
		try {
			return Files.readAllLines(keyFile, StandardCharsets.UTF_8);
		} catch (IOException ex) {
			throw unreadable(keyFile, ex);
		}
	}

	private static IllegalArgumentException unreadable(Path keyFile, IOException cause) {
		return new IllegalArgumentException("Cannot read the gridnode key file " + keyFile + ": " + cause, cause);
	}

	private static String valueOf(List<String> lines, String label, Path keyFile) {
		return lines.stream().map(String::strip).filter(line -> line.startsWith(label))
			.map(line -> line.substring(label.length()).strip()).findFirst()
			.orElseThrow(() -> new IllegalArgumentException(keyFile + " has no '" + label + "' line"));
	}

	/* Keys come from the operator, so a private key that does not belong to the public one is a likely typo */
	private static Signature pairOf(String privateKey, String publicKey, Path keyFile) {
		final Signature signature;
		final boolean matching;

		try {
			signature = new Signature(Optional.of(privateKey), Optional.of(publicKey));
			matching = signature.verify(PROBE, signature.sign(PROBE));

		} catch (GeneralSecurityException | SigningException | VerifySignatureException
			| IllegalArgumentException ex) {

			throw new IllegalArgumentException(keyFile + " does not hold a valid key pair: "
				+ ex.getMessage(), ex);
		}

		if (!matching) {
			throw new IllegalArgumentException("The private and public key in " + keyFile + " do not match");
		}

		return signature;
	}
}

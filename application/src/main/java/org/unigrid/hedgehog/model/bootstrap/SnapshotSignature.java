/*
    Unigrid Hedgehog
    Copyright © 2021-2023 Stiftelsen The Unigrid Foundation

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

package org.unigrid.hedgehog.model.bootstrap;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Optional;
import lombok.Cleanup;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.crypto.Signable;
import org.unigrid.hedgehog.model.crypto.Signature;
import org.unigrid.hedgehog.model.crypto.SigningException;
import org.unigrid.hedgehog.model.crypto.VerifySignatureException;

/*
   The signature sits after the snapshot's content, so signing never disturbs a single byte a
   reader depends on and an unsigned local build stays byte-identical to the signed release
   asset over everything they share. That is what lets somebody rebuild the snapshot themselves
   and compare, rather than having to trust whoever produced the release.
*/
@Slf4j
public final class SnapshotSignature implements Signable {
	@Getter private byte[] signable;
	@Getter private byte[] signature;

	private SnapshotSignature(byte[] signable, byte[] signature) {
		this.signable = signable;
		this.signature = signature;
	}

	public static SnapshotSignature read(Path snapshot) throws IOException {
		return new SnapshotSignature(SnapshotDigest.of(snapshot), readBlock(snapshot));
	}

	public static void signAndAppend(Path snapshot, String privateKeyHex)
		throws IOException, SigningException {

		final SnapshotSignature snapshotSignature = read(snapshot);

		snapshotSignature.sign(privateKeyHex);
		Files.write(snapshot, snapshotSignature.toBlock(), StandardOpenOption.WRITE,
			StandardOpenOption.APPEND);
	}

	public boolean isPresent() {
		return signature != null;
	}

	public SignatureStatus getStatus() {
		if (!isPresent()) {
			return SignatureStatus.UNSIGNED;
		}

		return isValidSignature() ? SignatureStatus.SIGNED : SignatureStatus.INVALID;
	}

	@Override
	public void sign(String privateKeyHex) throws SigningException {
		try {
			signature = new Signature(Optional.of(privateKeyHex), Optional.empty()).sign(signable);

		} catch (GeneralSecurityException ex) {
			throw new SigningException("Failed to sign the snapshot digest", ex);
		}
	}

	@Override
	public boolean isValidSignature() {
		for (final String key : NetworkKey.getPublicKeys()) {
			try {
				if (Signature.verify(this, key)) {
					return true;
				}
			} catch (VerifySignatureException ex) {
				log.atTrace().log("Snapshot signature did not match {}: {}", key, ex.getMessage());
			}
		}

		return false;
	}

	private byte[] toBlock() {
		final ByteBuffer block = ByteBuffer
			.allocate(SnapshotFormat.SIGNATURE_HEADER_SIZE + signature.length)
			.order(ByteOrder.BIG_ENDIAN);

		return block.put(SnapshotFormat.SIGNATURE_MAGIC).putInt(signature.length)
			.put(signature).array();
	}

	private static byte[] readBlock(Path snapshot) throws IOException {
		final long content = SnapshotDigest.contentLengthOf(snapshot);

		if (Files.size(snapshot) <= content + SnapshotFormat.SIGNATURE_HEADER_SIZE) {
			return null;
		}

		@Cleanup final FileChannel channel = FileChannel.open(snapshot, StandardOpenOption.READ);
		final ByteBuffer header = ByteBuffer.allocate(SnapshotFormat.SIGNATURE_HEADER_SIZE)
			.order(ByteOrder.BIG_ENDIAN);

		channel.position(content);
		channel.read(header);
		header.flip();

		final byte[] magic = new byte[SnapshotFormat.SIGNATURE_MAGIC.length];

		header.get(magic);

		final int length = header.getInt();

		if (!Arrays.equals(SnapshotFormat.SIGNATURE_MAGIC, magic) || length <= 0
			|| length > SnapshotFormat.MAXIMUM_SIGNATURE_SIZE) {

			log.atWarn().log("Ignoring an unrecognisable block after the snapshot content");
			return null;
		}

		final ByteBuffer bytes = ByteBuffer.allocate(length);

		channel.read(bytes);
		return bytes.array();
	}
}

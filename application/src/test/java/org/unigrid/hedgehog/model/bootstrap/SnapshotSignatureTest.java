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

package org.unigrid.hedgehog.model.bootstrap;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import lombok.Cleanup;
import lombok.SneakyThrows;
import mockit.Mock;
import mockit.MockUp;
import net.jqwik.api.Example;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.crypto.Signature;

public class SnapshotSignatureTest {
	@Example
	@SneakyThrows
	public void shouldReportAFreshSnapshotAsUnsigned() {
		assertThat(SnapshotSignature.read(snapshot()).getStatus(), equalTo(SignatureStatus.UNSIGNED));
	}

	@Example
	@SneakyThrows
	public void shouldVerifyASnapshotSignedByATrustedKey() {
		final Path snapshot = snapshot();
		final Signature key = trustedKey();

		SnapshotSignature.signAndAppend(snapshot, key.getPrivateKey());

		assertThat(SnapshotSignature.read(snapshot).getStatus(), equalTo(SignatureStatus.SIGNED));
	}

	@Example
	@SneakyThrows
	public void shouldLeaveTheContentUntouchedWhenSigning() {
		final Path snapshot = snapshot();
		final byte[] digest = SnapshotDigest.of(snapshot);
		final long content = SnapshotDigest.contentLengthOf(snapshot);

		SnapshotSignature.signAndAppend(snapshot, trustedKey().getPrivateKey());

		assertThat(SnapshotDigest.of(snapshot), equalTo(digest));
		assertThat(SnapshotDigest.contentLengthOf(snapshot), equalTo(content));
	}

	@Example
	@SneakyThrows
	public void shouldRejectASnapshotWhoseContentWasChanged() {
		final Path snapshot = snapshot();

		SnapshotSignature.signAndAppend(snapshot, trustedKey().getPrivateKey());

		final byte[] contents = Files.readAllBytes(snapshot);

		contents[(int) SnapshotDigest.contentLengthOf(snapshot) - 1] ^= 0x01;
		Files.write(snapshot, contents);

		assertThat(SnapshotSignature.read(snapshot).getStatus(), equalTo(SignatureStatus.INVALID));
	}

	@Example
	@SneakyThrows
	public void shouldRejectASignatureFromAnUntrustedKey() {
		final Path snapshot = snapshot();
		final Signature stranger = new Signature();

		trustedKey();
		SnapshotSignature.signAndAppend(snapshot, stranger.getPrivateKey());

		assertThat(SnapshotSignature.read(snapshot).getStatus(), equalTo(SignatureStatus.INVALID));
	}

	@Example
	@SneakyThrows
	public void shouldRejectATruncatedSignatureHeader() {
		final Path snapshot = snapshot();

		SnapshotSignature.signAndAppend(snapshot, trustedKey().getPrivateKey());
		truncateTrailingBlock(snapshot, SnapshotFormat.SIGNATURE_HEADER_SIZE - 1);

		assertThat(SnapshotSignature.read(snapshot).getStatus(), equalTo(SignatureStatus.INVALID));
	}

	@Example
	@SneakyThrows
	public void shouldRejectASignatureBlockWithBadMagic() {
		final Path snapshot = snapshot();

		SnapshotSignature.signAndAppend(snapshot, trustedKey().getPrivateKey());
		corruptByteAfterContent(snapshot, 0);

		assertThat(SnapshotSignature.read(snapshot).getStatus(), equalTo(SignatureStatus.INVALID));
	}

	@Example
	@SneakyThrows
	public void shouldRejectASignatureBlockWithAnInvalidLength() {
		final Path snapshot = snapshot();

		SnapshotSignature.signAndAppend(snapshot, trustedKey().getPrivateKey());
		writeSignatureLength(snapshot, 0);

		assertThat(SnapshotSignature.read(snapshot).getStatus(), equalTo(SignatureStatus.INVALID));
	}

	@SneakyThrows
	private static void truncateTrailingBlock(Path snapshot, int trailingBytes) {
		final long content = SnapshotDigest.contentLengthOf(snapshot);

		@Cleanup final FileChannel channel = FileChannel.open(snapshot, StandardOpenOption.WRITE);

		channel.truncate(content + trailingBytes);
	}

	@SneakyThrows
	private static void corruptByteAfterContent(Path snapshot, int offsetInBlock) {
		final byte[] contents = Files.readAllBytes(snapshot);
		final int index = (int) SnapshotDigest.contentLengthOf(snapshot) + offsetInBlock;

		contents[index] ^= 0x01;
		Files.write(snapshot, contents);
	}

	@SneakyThrows
	private static void writeSignatureLength(Path snapshot, int length) {
		final byte[] contents = Files.readAllBytes(snapshot);
		final int offset = (int) SnapshotDigest.contentLengthOf(snapshot)
			+ SnapshotFormat.SIGNATURE_LENGTH_OFFSET;

		ByteBuffer.wrap(contents, offset, Integer.BYTES).putInt(length);
		Files.write(snapshot, contents);
	}

	@SneakyThrows
	private static Signature trustedKey() {
		final Signature signature = new Signature();

		new MockUp<NetworkKey>() {
			@Mock public String[] getPublicKeys() {
				return new String[] { signature.getPublicKey() };
			}
		};

		return signature;
	}

	@SneakyThrows
	private static Path snapshot() {
		final Path path = Files.createTempFile("hhg-signed-", ".dat");

		path.toFile().deleteOnExit();
		SnapshotBuilder.build(BlockFixture.directory(), path);
		return path;
	}
}

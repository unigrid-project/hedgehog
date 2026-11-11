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
import static org.hamcrest.Matchers.notNullValue;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Collections;
import lombok.Cleanup;
import lombok.SneakyThrows;
import mockit.Mock;
import mockit.MockUp;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.crypto.Signature;
import org.unigrid.hedgehog.model.crypto.SigningException;

public class SnapshotSignatureTest {
	private static final int MAX_ADDRESSES = 8;
	private static final int MAX_TRAILING = 256;

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

	/* A replaced signature must not stay behind the content, or the new one would be appended after it: whatever a
	   snapshot ended in, it ends in the one block of its new signature, which begins where the content ends, and no
	   byte of the content is another */
	@SneakyThrows
	@Property(tries = 30)
	public void shouldEndInOneSignatureWhateverTheSnapshotEndedIn(@ForAll @IntRange(max = MAX_ADDRESSES) int addresses,
		@ForAll @Size(max = MAX_TRAILING) byte[] trailing) {

		final byte[] content = content(addresses);
		final Path snapshot = SyntheticSnapshots.inMemory(joined(content, trailing));

		SnapshotSignature.signAndReplace(snapshot, trustedKey().getPrivateKey());

		final byte[] signed = Files.readAllBytes(snapshot);
		final SnapshotSignature signature = SnapshotSignature.read(snapshot);

		assertThat(Arrays.copyOf(signed, content.length), equalTo(content));
		assertThat(signed.length, equalTo(content.length + SnapshotFormat.SIGNATURE_HEADER_SIZE
			+ signature.getSignature().length));
		assertThat(signature.getStatus(), equalTo(SignatureStatus.SIGNED));
	}

	/* The signature is made before anything is written, so a signing that cannot make one leaves the signature that
	   the snapshot has */
	@Example
	@SneakyThrows
	public void shouldKeepTheSnapshotAsItWasWhenNoSignatureCanBeMade() {
		final Path snapshot = SyntheticSnapshots.inMemory(content(0));

		SnapshotSignature.signAndReplace(snapshot, trustedKey().getPrivateKey());

		final byte[] signed = Files.readAllBytes(snapshot);
		Exception failure = null;

		try {
			SnapshotSignature.signAndReplace(snapshot, "0123456789abcdef");
		} catch (IllegalArgumentException | SigningException ex) {
			failure = ex;
		}

		assertThat(failure, notNullValue());
		assertThat(Files.readAllBytes(snapshot), equalTo(signed));
	}

	/* Nothing is written before the old signature is cut off, so a cut that fails leaves it in place too */
	@Example
	@SneakyThrows
	public void shouldKeepTheSnapshotAsItWasWhenWhatFollowsTheContentCannotBeCutOff() {
		final byte[] content = content(0);
		final byte[] contents = joined(content, new byte[] { 9, 9, 9, 9, 9, 9 });
		final Path snapshot = SyntheticSnapshots.inMemory(contents);

		@Cleanup final FileChannel channel = FileChannel.open(snapshot, StandardOpenOption.WRITE);
		final FileChannel failing = FailingFileChannel.atTheCut(channel);

		SyntheticSnapshots.assertRefused(() -> replaceTrailer(failing, content, new byte[] { 1, 2, 3 }),
			FailingFileChannel.REASON);
		assertThat(Files.readAllBytes(snapshot), equalTo(contents));
	}

	/* A write that stops part of the way, as on a disk that fills up, leaves the content and the start of the block:
	   nothing of what the snapshot ended in before, and no byte of the content is another */
	@SneakyThrows
	@Property(tries = 100)
	public void shouldLeaveTheContentAndTheStartOfTheBlockWhenTheWriteStops(
		@ForAll @IntRange(max = MAX_ADDRESSES) int addresses, @ForAll @Size(max = MAX_TRAILING) byte[] trailing,
		@ForAll @Size(min = 1, max = MAX_TRAILING) byte[] block, @ForAll @IntRange(min = 0) int stop) {

		final byte[] content = content(addresses);
		final int written = stop % block.length;
		final Path snapshot = SyntheticSnapshots.inMemory(joined(content, trailing));

		@Cleanup final FileChannel channel = FileChannel.open(snapshot, StandardOpenOption.WRITE);
		final FileChannel failing = FailingFileChannel.afterBytes(channel, written);

		SyntheticSnapshots.assertRefused(() -> replaceTrailer(failing, content, block), FailingFileChannel.REASON);
		assertThat(Files.readAllBytes(snapshot), equalTo(joined(content, Arrays.copyOf(block, written))));
	}

	private static Void replaceTrailer(FileChannel channel, byte[] content, byte[] block) throws IOException {
		SnapshotSignature.replaceTrailer(channel, content.length, ByteBuffer.wrap(block));
		return null;
	}

	/* The bytes of a snapshot that holds addresses alone, so its content ends where its address table does */
	private static byte[] content(int addresses) {
		final SyntheticSnapshots.HeaderBuilder builder = SyntheticSnapshots.builder()
			.balances(Collections.nCopies(addresses, 1L));

		return builder.transactionTableOffset(builder.build().length).build();
	}

	private static byte[] joined(byte[] content, byte[] trailing) {
		final byte[] contents = Arrays.copyOf(content, content.length + trailing.length);

		System.arraycopy(trailing, 0, contents, content.length, trailing.length);
		return contents;
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

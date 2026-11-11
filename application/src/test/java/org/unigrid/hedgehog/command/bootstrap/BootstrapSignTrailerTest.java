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

package org.unigrid.hedgehog.command.bootstrap;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.SneakyThrows;
import mockit.Invocation;
import mockit.Mock;
import mockit.MockUp;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeTry;
import net.jqwik.api.lifecycle.PropagationMode;
import org.unigrid.hedgehog.command.ExitCode;
import org.unigrid.hedgehog.command.HedgehogCli;
import org.unigrid.hedgehog.jqwik.MockitHook;
import org.unigrid.hedgehog.model.bootstrap.FailingFileChannel;
import org.unigrid.hedgehog.model.bootstrap.Hashing;
import org.unigrid.hedgehog.model.bootstrap.SignatureStatus;
import org.unigrid.hedgehog.model.bootstrap.SnapshotFormat;
import org.unigrid.hedgehog.model.bootstrap.SnapshotSignature;
import org.unigrid.hedgehog.model.bootstrap.SyntheticSnapshots;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.crypto.Signature;
import org.unigrid.hedgehog.model.spork.BoardKeysRecordFixture;

/* The command goes by the snapshot as the classes of the model read it, so what a half-finished signing leaves behind
   is what the command meets */
@AddLifecycleHook(value = MockitHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class BootstrapSignTrailerTest {
	private static final int WRITTEN_BEFORE_THE_END = 8;
	private static final int SIGNATURE_LENGTH = 71;

	/* The head of a block and the longest signature of the curve: two integers of 66 bytes in their DER */
	private static final int LONGEST_BLOCK = SnapshotFormat.SIGNATURE_HEADER_SIZE + 139;
	private static final int WRITES_ALL = -1;

	/* Where the disk fails is told to the one fake that there is of the step that writes a signature. A fake that
	   goes on into the method it stands for goes on into itself once it is made a second time, so it is made once
	   for all the examples, and what it goes by is theirs to set. */
	private static final AtomicBoolean FAILING_DISK = new AtomicBoolean();
	private static int stop;
	private static boolean cutFails;
	private static byte[] block;
	private static int written;

	private Signature board;
	private Path snapshot;

	@BeforeTry
	@SneakyThrows
	public void beforeTry() {
		snapshot = BootstrapCli.snapshotInMemory();
		board = new Signature();
		stop = WRITES_ALL;
		cutFails = false;
		block = null;
		BoardKeysRecordFixture.inMemory();

		new MockUp<NetworkKey>() {
			@Mock public String[] getPublicKeys() {
				return new String[] { board.getPublicKey() };
			}
		};

		if (FAILING_DISK.compareAndSet(false, true)) {
			new MockUp<SnapshotSignature>() {
				@Mock public /* static */ void replaceTrailer(Invocation invocation, FileChannel channel,
					long content, ByteBuffer trailer) {

					invocation.proceed(diskOf(channel, trailer), content, trailer);
				}
			};
		}
	}

	/* The channel that the one step of a signing cuts and writes through: the one of the file, or one that fails
	   where the test says a disk fills up or the process is killed */
	private static FileChannel diskOf(FileChannel channel, ByteBuffer trailer) {
		if (cutFails) {
			return FailingFileChannel.atTheCut(channel);
		}

		if (stop == WRITES_ALL) {
			return channel;
		}

		block = new byte[trailer.remaining()];
		trailer.duplicate().get(block);
		written = stop % block.length;
		return FailingFileChannel.afterBytes(channel, written);
	}

	@AfterTry
	public void afterTry() {
		NetworkKey.reset();
	}

	/* A signing that died inside its last write leaves a part of the block, which carries no signature to keep */
	@Example
	@SneakyThrows
	public void shouldSignAgainOverTheRemainsOfASigningThatWasNeverFinished() {
		final byte[] content = SyntheticSnapshots.builder().transactionTableOffset(SnapshotFormat.HEADER_SIZE).build();
		final ByteBuffer remains = ByteBuffer.allocate(SnapshotFormat.SIGNATURE_HEADER_SIZE + WRITTEN_BEFORE_THE_END);

		remains.put(SnapshotFormat.SIGNATURE_MAGIC).putInt(SnapshotFormat.SIGNATURE_LENGTH_OFFSET, SIGNATURE_LENGTH);
		Files.createDirectories(snapshot.getParent());
		Files.write(snapshot, ByteBuffer.allocate(content.length + remains.capacity()).put(content)
			.put(remains.array()).array());

		final HedgehogCli.Result result = BootstrapCli.run("sign", "-k", board.getPrivateKey(), "--force");

		assertThat(result.err(), result.exitCode(), equalTo(0));
		assertThat(SnapshotSignature.read(snapshot).getStatus(), equalTo(SignatureStatus.SIGNED));
	}

	/* The write of a new signature may stop at any of its bytes, as a process that is killed stops. What it leaves
	   is the content alone when it stopped before the first byte, and else the content and the start of a block,
	   which is no signature. Either way the next signing that is forced leaves the snapshot signed, with the content
	   it had. */
	@SneakyThrows
	@Property(tries = LONGEST_BLOCK)
	public void shouldSignASnapshotWhoseSigningStoppedAtAnyByte(
		@ForAll @IntRange(max = LONGEST_BLOCK - 1) int at) {

		final byte[] content = existing(content());

		SnapshotSignature.signAndReplace(snapshot, board.getPrivateKey());
		stop = at;

		final HedgehogCli.Result stopped = sign("--force");

		stop = WRITES_ALL;
		assertThat(stopped.exitCode(), not(equalTo(0)));
		assertThat(stopped.err(), containsString(FailingFileChannel.REASON));
		assertThat(Files.readAllBytes(snapshot), equalTo(joined(content, Arrays.copyOf(block, written))));
		assertThat(SnapshotSignature.read(snapshot).getStatus(),
			equalTo(written == 0 ? SignatureStatus.UNSIGNED : SignatureStatus.MALFORMED));

		final HedgehogCli.Result repaired = sign("--force");

		assertThat(repaired.err(), repaired.exitCode(), equalTo(0));
		assertSignedOnce(content);
	}

	/* A signing that fails before it cut anything off leaves the signature that the snapshot has */
	@Example
	@SneakyThrows
	public void shouldKeepTheOldSignatureWhenItCannotBeCutOff() {
		final byte[] content = existing(content());

		SnapshotSignature.signAndReplace(snapshot, board.getPrivateKey());

		final byte[] signed = Files.readAllBytes(snapshot);

		cutFails = true;

		final HedgehogCli.Result result = sign("--force");

		assertThat(result.exitCode(), not(equalTo(0)));
		assertThat(result.err(), containsString(FailingFileChannel.REASON));
		assertThat(Files.readAllBytes(snapshot), equalTo(signed));
		assertSignedOnce(content);
	}

	/* The remains are told for what they are, and stay until the user asks for them to be cut off */
	@Example
	@SneakyThrows
	public void shouldSayWhatTheRemainsOfASigningAreAndLeaveThemUnlessForced() {
		final byte[] contents = existing(joined(content(), Arrays.copyOf(SnapshotFormat.SIGNATURE_MAGIC, 3)));
		final HedgehogCli.Result result = sign();

		assertThat(result.exitCode(), equalTo(ExitCode.UNUSABLE));
		assertThat(result.err().lines().toList(), equalTo(List.of(snapshot + " ends in bytes that are no whole"
			+ " signature, as a signing that was cut short leaves them, pass --force to cut them off and"
			+ " sign")));
		assertThat(Files.readAllBytes(snapshot), equalTo(contents));
	}

	/* A whole signature that verifies against no trusted key is no remains: the content is not to be trusted, and
	   no option makes the command sign it */
	@Example
	@SneakyThrows
	public void shouldRefuseAWholeSignatureThatDoesNotVerifyWhenForcedToo() {
		existing(content());
		SnapshotSignature.signAndReplace(snapshot, new Signature().getPrivateKey());

		final byte[] contents = Files.readAllBytes(snapshot);
		final HedgehogCli.Result result = sign("--force");

		assertThat(result.exitCode(), equalTo(ExitCode.UNUSABLE));
		assertThat(result.err(), containsString("already carries a signature that does not verify"));
		assertThat(Files.readAllBytes(snapshot), equalTo(contents));
	}

	/* The content is read to its end before anything is signed, so a snapshot that ends before the transaction id
	   that its header counts is refused, and what it ends in is not taken for the remains of a signing */
	@SneakyThrows
	@Property(tries = Hashing.HASH_SIZE)
	public void shouldRefuseASnapshotThatEndsBeforeItsContentDoes(
		@ForAll @IntRange(max = Hashing.HASH_SIZE - 1) int held) {

		final byte[] contents = existing(joined(SyntheticSnapshots.builder()
			.transactionTableOffset(SnapshotFormat.HEADER_SIZE).transactionCount(1).build(), new byte[held]));
		final HedgehogCli.Result result = sign("--force");

		assertThat(result.exitCode(), equalTo(ExitCode.UNUSABLE));
		assertThat(result.err().lines().toList(), equalTo(List.of("Snapshot ends before its content does")));
		assertThat(Files.readAllBytes(snapshot), equalTo(contents));
	}

	private HedgehogCli.Result sign(String... extra) {
		final String[] args = { "sign", "-k", board.getPrivateKey() };
		final String[] all = Arrays.copyOf(args, args.length + extra.length);

		System.arraycopy(extra, 0, all, args.length, extra.length);
		return BootstrapCli.run(all);
	}

	/* The content, then the one block of a signature that verifies, and nothing after it */
	@SneakyThrows
	private void assertSignedOnce(byte[] content) {
		final byte[] signed = Files.readAllBytes(snapshot);
		final SnapshotSignature signature = SnapshotSignature.read(snapshot);

		assertThat(Arrays.copyOf(signed, content.length), equalTo(content));
		assertThat(signed.length, equalTo(content.length + SnapshotFormat.SIGNATURE_HEADER_SIZE
			+ signature.getSignature().length));
		assertThat(signature.getStatus(), equalTo(SignatureStatus.SIGNED));
	}

	@SneakyThrows
	private byte[] existing(byte[] contents) {
		Files.createDirectories(snapshot.getParent());
		Files.write(snapshot, contents);
		return contents;
	}

	/* A snapshot of a header alone, which is where its content ends */
	private static byte[] content() {
		return SyntheticSnapshots.builder().transactionTableOffset(SnapshotFormat.HEADER_SIZE).build();
	}

	private static byte[] joined(byte[] content, byte[] trailing) {
		final byte[] contents = Arrays.copyOf(content, content.length + trailing.length);

		System.arraycopy(trailing, 0, contents, content.length, trailing.length);
		return contents;
	}
}

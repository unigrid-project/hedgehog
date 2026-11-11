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
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.notNullValue;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.lang.reflect.Field;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import lombok.SneakyThrows;
import mockit.Mock;
import mockit.MockUp;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.AfterTry;
import org.apache.commons.codec.digest.DigestUtils;
import org.slf4j.LoggerFactory;
import org.unigrid.hedgehog.model.NodeStatus;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.crypto.ReleaseKeyFixture;
import org.unigrid.hedgehog.model.crypto.Signature;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.buildIn;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.key;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.keys;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.publicKeysOf;

public class SnapshotInstallerTest {
	private static final String PLAIN = "/bootstrap.dat";
	private static final byte[] NOT_A_HASH = "not the hash of the snapshot\n".getBytes(StandardCharsets.US_ASCII);
	private static final int NEVER = Integer.MAX_VALUE;
	private static final NodeStatus.Phase RUNNING = new NodeStatus().current();
	private static final Duration ENDS_WITHIN = Duration.ofMinutes(1);

	private final NodeStatus status = new NodeStatus();
	private final Path target = target();

	/* The pauses the installer asked for, none of which is waited out, and what the status said in each */
	private final List<Duration> pauses = new ArrayList<>();
	private final List<NodeStatus.Phase> paused = new ArrayList<>();

	@AfterTry
	public void after() {
		NetworkKey.reset();
	}

	/*
	   A download that fails for a reason that passes, such as a network that is not up yet after a boot, is
	   not the last one a node makes: it asks again after a pause until it holds the snapshot. The status tells
	   the failure for as long as it stands, and the success after it.
	*/
	@Example
	@SneakyThrows
	public void shouldTryAgainAfterAFailedDownloadUntilOneSucceeds() {
		final byte[] published = signedSnapshot();

		try (LoopbackHost host = new LoopbackHost()) {
			final AtomicInteger hashRequests = publishFailing(host, published, 2);

			installer(this::record).installUntilInstalled(host.url(PLAIN));

			assertThat(hashRequests.get(), equalTo(3));
		}

		assertThat(sha256(target), equalTo(DigestUtils.sha256Hex(published)));
		assertThat(paused.stream().map(NodeStatus.Phase::activity).toList(),
			equalTo(List.of(NodeStatus.Activity.FAILED, NodeStatus.Activity.FAILED)));
		assertThat(paused.stream().map(NodeStatus.Phase::message).toList(), everyItem(notNullValue()));
		assertThat(status.current(), equalTo(RUNNING));
	}

	/* A node that was only early asks again soon, and one whose network is down for long asks seldom */
	@Example
	@SneakyThrows
	public void shouldPauseTwiceAsLongAfterEachFailureUpToTenMinutes() {
		final byte[] published = signedSnapshot();

		try (LoopbackHost host = new LoopbackHost()) {
			publishFailing(host, published, 8);
			installer(this::record).installUntilInstalled(host.url(PLAIN));
		}

		assertThat(pauses, equalTo(Stream.of(10, 20, 40, 80, 160, 320, 600, 600).map(Duration::ofSeconds).toList()));
	}

	/*
	   An operator who fetches the snapshot while the node pauses has done what the node was about to do, so
	   the node asks no more and the failure that it reported is over.

	   The node opens what is at the path to tell whether it is a snapshot it uses, and a snapshot is mapped as
	   it is opened, which an in-memory file system cannot do, so this one is fetched into a directory on disk.
	*/
	@Example
	@SneakyThrows
	public void shouldReportRunningOnceTheSnapshotCameAnotherWayAfterAFailedDownload() {
		final byte[] published = signedSnapshot();
		final Path fetched = onDisk();

		try (LoopbackHost host = new LoopbackHost()) {
			final AtomicInteger hashRequests = publishFailing(host, published, NEVER);

			installer(new BootstrapSnapshot(fetched), length -> place(fetched, published))
				.installUntilInstalled(host.url(PLAIN));

			assertThat(hashRequests.get(), equalTo(1));
		}

		assertThat(status.current(), equalTo(RUNNING));
	}

	/*
	   A node refuses a snapshot signed by a board key that it has not learned of, and fetches the one of its
	   release over it. The file stays while that fails, and once the node learns of the key, as it does from a
	   peer, the file is a snapshot it uses: it is kept, the node asks no more and the failure it reported is over.
	*/
	@Example
	@SneakyThrows
	public void shouldKeepASnapshotWhoseBoardKeyTheNodeLearnsOfInAPause() {
		buildIn(keys(0, 1));

		final Path signed = onDisk();

		SnapshotBuilder.build(BlockFixture.directory(), signed);
		SnapshotSignature.signAndAppend(signed, key(2).getPrivateKey());

		final String before = sha256(signed);

		try (LoopbackHost host = new LoopbackHost()) {
			final AtomicInteger hashRequests = publishFailing(host, Files.readAllBytes(signed), NEVER);

			installer(new BootstrapSnapshot(signed), length -> {
				record(length);
				NetworkKey.install(NetworkKey.getActiveKeys().after(List.of(publicKeysOf(keys(1, 2)))));
			}).installUntilInstalled(host.url(PLAIN));

			assertThat(hashRequests.get(), equalTo(1));
		}

		assertThat(sha256(signed), equalTo(before));
		assertThat(paused.stream().map(NodeStatus.Phase::activity).toList(),
			equalTo(List.of(NodeStatus.Activity.FAILED)));
		assertThat(status.current(), equalTo(RUNNING));
	}

	/* The node stops the install by interrupting its thread, which a pause reports as a sleep does. No further
	   attempt is made, and the thread stays interrupted for whatever runs it. */
	@Example
	@SneakyThrows
	public void shouldMakeNoFurtherAttemptOnceItIsInterruptedInAPause() {
		final AtomicBoolean interrupted = new AtomicBoolean();

		try (LoopbackHost host = new LoopbackHost()) {
			final AtomicInteger hashRequests = publishFailing(host, signedSnapshot(), NEVER);
			final SnapshotInstaller installer = installer(length -> {
				throw new InterruptedException();
			});

			onAThreadOfItsOwn(() -> {
				installer.installUntilInstalled(host.url(PLAIN));
				interrupted.set(Thread.currentThread().isInterrupted());
			});

			assertThat(hashRequests.get(), equalTo(1));
		}

		assertThat(interrupted.get(), equalTo(true));
	}

	/* An interrupt that comes while a download runs fails that download at most, and is no failure to pause for */
	@Example
	@SneakyThrows
	public void shouldNotPauseOnceItIsInterruptedInADownload() {
		try (LoopbackHost host = new LoopbackHost()) {
			publishFailing(host, signedSnapshot(), NEVER);

			final SnapshotInstaller installer = installer(this::record);
			final URL source = host.url(PLAIN);

			onAThreadOfItsOwn(() -> {
				Thread.currentThread().interrupt();
				installer.installUntilInstalled(source);
			});
		}

		assertThat(pauses, empty());
		assertThat(Files.exists(target), equalTo(false));
	}

	/* A node that stops while the install opens the snapshot interrupts the read of the file, which the install
	   takes for a file that does not open. A thread that is interrupted fetches nothing over it, so a snapshot
	   that the node uses is still there when it starts again. */
	@Example
	@SneakyThrows
	public void shouldLeaveASnapshotWhereItIsOnceItIsInterruptedAsItOpensIt() {
		final byte[] published = signedSnapshot();
		final Path built = onDisk();

		SnapshotBuilder.build(BlockFixture.directory(), built);

		final String before = sha256(built);

		try (LoopbackHost host = new LoopbackHost()) {
			publishFailing(host, published, 0);

			final SnapshotInstaller installer = installer(new BootstrapSnapshot(built), this::record);
			final URL source = host.url(PLAIN);

			onAThreadOfItsOwn(() -> {
				Thread.currentThread().interrupt();
				installer.installUntilInstalled(source);
			});
		}

		assertThat(sha256(built), equalTo(before));
		assertThat(pauses, empty());
	}

	/*
	   A bootstrap import that stopped early leaves a file the node refuses to open, and a node serves no
	   snapshot from it. The file exists, but it is not the snapshot the node started without, so the node
	   has to fetch the one of its release over it.

	   The file is cut inside the header because a snapshot is mapped as it is opened, which an in-memory file
	   system cannot do, and a header that is cut short is refused before that.
	*/
	@Example
	@SneakyThrows
	public void shouldDownloadOverASnapshotThatIsCutOff() {
		final byte[] published = signedSnapshot();

		Files.write(target, Arrays.copyOf(published, SnapshotFormat.HEADER_SIZE / 2));
		replaced(target, published);
	}

	/* The node uses only a snapshot signed by its own board keys, so one whose signature does not verify
	   against them is no snapshot to it either. It is mapped before it is refused, as is one of an older
	   format, so both are files on disk. */
	@Example
	@SneakyThrows
	public void shouldDownloadOverASnapshotWhoseSignatureDoesNotVerify() {
		final byte[] published = signedSnapshot();
		final Path refused = onDisk(published);
		final byte[] contents = published.clone();

		contents[(int) SnapshotDigest.contentLengthOf(refused) - 1] ^= 0x01;
		Files.write(refused, contents);

		assertThat(replaced(refused, published).getReader().isPresent(), equalTo(true));
	}

	@Example
	@SneakyThrows
	public void shouldDownloadOverASnapshotOfAnOlderFormat() {
		final byte[] published = signedSnapshot();
		final byte[] older = published.clone();

		ByteBuffer.wrap(older).putInt(SnapshotFormat.VERSION_OFFSET, SnapshotFormat.VERSION - 1);

		assertThat(replaced(onDisk(older), published).getReader().isPresent(), equalTo(true));
	}

	/* A snapshot that the operator built carries no signature and opens all the same, so the node serves it and
	   asks the release for nothing */
	@Example
	@SneakyThrows
	public void shouldKeepAnUnsignedSnapshotThatOpens() {
		final byte[] published = signedSnapshot();
		final Path built = onDisk(new byte[0]);

		SnapshotBuilder.build(BlockFixture.directory(), built);

		final String before = sha256(built);

		try (LoopbackHost host = new LoopbackHost()) {
			final AtomicInteger hashRequests = publishFailing(host, published, 0);

			installer(new BootstrapSnapshot(built), this::record).installIfMissing(host.url(PLAIN));

			assertThat(hashRequests.get(), equalTo(0));
		}

		assertThat(sha256(built), equalTo(before));
		assertThat(status.current(), equalTo(RUNNING));
	}

	/* An operator who finds the file they put there replaced is told that the node did it, and why it does not
	   use that file: the reason the node gave as it refused to open it */
	@Example
	@SneakyThrows
	public void shouldSayThatItReplacesASnapshotAndWhyItDoesNotUseIt() {
		final byte[] published = signedSnapshot();
		final ListAppender<ILoggingEvent> log = new ListAppender<>();
		final Logger logger = (Logger) LoggerFactory.getLogger(SnapshotInstaller.class);

		Files.write(target, Arrays.copyOf(published, SnapshotFormat.HEADER_SIZE / 2));
		log.start();
		logger.addAppender(log);

		try {
			replaced(target, published);
		} finally {
			logger.detachAppender(log);
		}

		assertThat(warningsOf(log), contains(allOf(containsString(target.toString()),
			containsString("runs past the end of the file"))));
	}

	private static List<String> warningsOf(ListAppender<ILoggingEvent> log) {
		return log.list.stream().filter(event -> event.getLevel() == Level.WARN)
			.map(ILoggingEvent::getFormattedMessage).toList();
	}

	/* Installs over a file that the node refuses to open, and answers the snapshot of what is there then */
	@SneakyThrows
	private BootstrapSnapshot replaced(Path refused, byte[] published) {
		final BootstrapSnapshot snapshot = new BootstrapSnapshot(refused);

		assertThat(snapshot.getReader().isPresent(), equalTo(false));

		try (LoopbackHost host = new LoopbackHost()) {
			publishFailing(host, published, 0);
			installer(snapshot, this::record).installIfMissing(host.url(PLAIN));
		}

		assertThat("The file the node refuses to open was left where it was", sha256(refused),
			equalTo(DigestUtils.sha256Hex(published)));
		assertThat(status.current(), equalTo(RUNNING));
		return snapshot;
	}

	/* A file in a directory of its own on disk, where a snapshot can be mapped and the download leaves nothing
	   beside it */
	@SneakyThrows
	private static Path onDisk(byte[] contents) {
		return Files.write(onDisk(), contents);
	}

	/* The path of such a file, which is not there yet */
	@SneakyThrows
	private static Path onDisk() {
		final Path directory = Files.createTempDirectory("hhg-install-");
		final Path file = directory.resolve("bootstrap.dat");

		directory.toFile().deleteOnExit();
		file.toFile().deleteOnExit();
		return file;
	}

	private void record(Duration length) {
		pauses.add(length);
		paused.add(status.current());
	}

	@SneakyThrows
	private static void place(Path file, byte[] snapshot) {
		Files.write(file, snapshot);
	}

	/* An interrupt is left on the thread it was made on, which is not the one that runs the examples */
	@SneakyThrows
	private static void onAThreadOfItsOwn(Runnable task) {
		assertThat(Thread.ofVirtual().name("snapshot-install").start(task).join(ENDS_WITHIN), equalTo(true));
	}

	private SnapshotInstaller installer(SnapshotInstaller.Pause pause) {
		return installer(new BootstrapSnapshot(target), pause);
	}

	private SnapshotInstaller installer(BootstrapSnapshot snapshot, SnapshotInstaller.Pause pause) {
		final SnapshotInstaller installer = new SnapshotInstaller();

		inject(installer, "snapshot", snapshot);
		inject(installer, "status", status);
		inject(installer, "pause", pause);
		return installer;
	}

	@SneakyThrows
	private static void inject(SnapshotInstaller installer, String name, Object value) {
		final Field field = SnapshotInstaller.class.getDeclaredField(name);

		field.setAccessible(true);
		field.set(installer, value);
	}

	/* Publishes the snapshot beside a hash that is wrong for as many requests as given, which fails those downloads
	   before anything of the snapshot is transferred, and counts the requests for the hash */
	private static AtomicInteger publishFailing(LoopbackHost host, byte[] snapshot, int failures) {
		final byte[] hash = (DigestUtils.sha256Hex(snapshot) + "  " + PLAIN.substring(1) + "\n")
			.getBytes(StandardCharsets.US_ASCII);
		final AtomicInteger hashRequests = new AtomicInteger();

		host.serve(PLAIN, snapshot);
		host.serve(PLAIN + SnapshotDownload.HASH_SUFFIX,
			out -> out.write(hashRequests.incrementAndGet() > failures ? hash : NOT_A_HASH));
		host.serve(PLAIN + SnapshotDownload.HASH_SIGNATURE_SUFFIX, ReleaseKeyFixture.trusted().sign(hash));
		return hashRequests;
	}

	@SneakyThrows
	private static String sha256(Path file) {
		return DigestUtils.sha256Hex(Files.readAllBytes(file));
	}

	@SneakyThrows
	private static Path target() {
		final Path directory = Jimfs.newFileSystem(Configuration.unix()).getPath("/data");

		Files.createDirectories(directory);
		return directory.resolve("bootstrap.dat");
	}

	/* The snapshot a release would publish, signed by a board key this build trusts. */
	@SneakyThrows
	private static byte[] signedSnapshot() {
		final Path path = target();
		final Signature key = new Signature();

		new MockUp<NetworkKey>() {
			@Mock public String[] getPublicKeys() {
				return new String[] { key.getPublicKey() };
			}
		};

		SnapshotBuilder.build(BlockFixture.directory(), path);
		SnapshotSignature.signAndAppend(path, key.getPrivateKey());
		return Files.readAllBytes(path);
	}
}

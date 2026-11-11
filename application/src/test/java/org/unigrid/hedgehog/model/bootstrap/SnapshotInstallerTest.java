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
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.notNullValue;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.lang.reflect.Field;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
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
import org.unigrid.hedgehog.model.NodeStatus;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.crypto.ReleaseKeyFixture;
import org.unigrid.hedgehog.model.crypto.Signature;

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

	/* An operator who fetches the snapshot while the node pauses has done what the node was about to do, so
	   the node asks no more and the failure that it reported is over */
	@Example
	@SneakyThrows
	public void shouldReportRunningOnceTheSnapshotCameAnotherWayAfterAFailedDownload() {
		final byte[] published = signedSnapshot();

		try (LoopbackHost host = new LoopbackHost()) {
			final AtomicInteger hashRequests = publishFailing(host, published, NEVER);

			installer(length -> place(published)).installUntilInstalled(host.url(PLAIN));

			assertThat(hashRequests.get(), equalTo(1));
		}

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

	private void record(Duration length) {
		pauses.add(length);
		paused.add(status.current());
	}

	@SneakyThrows
	private void place(byte[] snapshot) {
		Files.write(target, snapshot);
	}

	/* An interrupt is left on the thread it was made on, which is not the one that runs the examples */
	@SneakyThrows
	private static void onAThreadOfItsOwn(Runnable task) {
		assertThat(Thread.ofVirtual().name("snapshot-install").start(task).join(ENDS_WITHIN), equalTo(true));
	}

	@SneakyThrows
	private SnapshotInstaller installer(SnapshotInstaller.Pause pause) {
		final SnapshotInstaller installer = new SnapshotInstaller();

		inject(installer, "snapshot", new BootstrapSnapshot(target));
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

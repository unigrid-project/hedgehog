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
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import lombok.SneakyThrows;
import mockit.Mock;
import mockit.MockUp;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.Size;
import net.jqwik.api.lifecycle.BeforeTry;
import org.unigrid.hedgehog.command.HedgehogCli;
import org.unigrid.hedgehog.model.bootstrap.SignatureStatus;
import org.unigrid.hedgehog.model.bootstrap.SnapshotDigest;
import org.unigrid.hedgehog.model.bootstrap.SnapshotInspector;
import org.unigrid.hedgehog.model.bootstrap.SnapshotSignature;
import org.unigrid.hedgehog.model.crypto.NetworkKey;

public class BootstrapSignTest {
	private static final String KEY = "0123456789abcdef";
	private static final byte[] CONTENT = { 1, 2, 3, 4 };

	private final List<List<Object>> signings = new ArrayList<>();
	private Path snapshot;

	@BeforeTry
	public void beforeTry() {
		signings.clear();
		snapshot = BootstrapCli.snapshotInMemory();
		keyIsTrusted(true);
		signatureIsPresent(false);
		inspectorFinds(SignatureStatus.UNSIGNED, null);
		contentIs(CONTENT.length);
	}

	private void keyIsTrusted(boolean trusted) {
		new MockUp<NetworkKey>() {
			@Mock public /* static */ boolean isTrusted(String privateKey) {
				return trusted;
			}
		};
	}

	private void inspectorFinds(SignatureStatus status, IOException problem) {
		new MockUp<SnapshotInspector>() {
			@Mock public /* static */ SignatureStatus validate(Path path) throws IOException {
				if (problem != null) {
					throw problem;
				}

				return status;
			}
		};
	}

	private void contentIs(long length) {
		new MockUp<SnapshotDigest>() {
			@Mock public /* static */ long contentLengthOf(Path path) {
				return length;
			}
		};
	}

	private void signatureIsPresent(boolean present) {
		final SnapshotSignature signature = BootstrapCli.withoutConstructor(SnapshotSignature.class);

		new MockUp<SnapshotSignature>() {
			@Mock public /* static */ SnapshotSignature read(Path path) {
				return signature;
			}

			@Mock public boolean isPresent() {
				return present;
			}

			@Mock public /* static */ void signAndAppend(Path path, String privateKeyHex) {
				signings.add(List.of(path, privateKeyHex));
			}
		};
	}

	@SneakyThrows
	private void existingSnapshot(byte[] contents) {
		Files.createDirectories(snapshot.getParent());
		Files.write(snapshot, contents);
	}

	private HedgehogCli.Result sign(String... extra) {
		final String[] args = { "sign", "-k", KEY };
		final String[] all = Arrays.copyOf(args, args.length + extra.length);

		System.arraycopy(extra, 0, all, args.length, extra.length);
		return BootstrapCli.run(all);
	}

	private void assertNothingSigned() {
		assertThat(signings, empty());
	}

	@Example
	public void shouldSignAnUnsignedSnapshot() {
		existingSnapshot(CONTENT);
		signatureIsPresent(false);

		final HedgehogCli.Result result = sign();

		assertThat(result.exitCode(), equalTo(0));
		assertThat(result.out(), containsString("Signed " + snapshot));

		assertThat(signings, equalTo(List.of(List.of(snapshot, KEY))));
	}

	@Example
	public void shouldReportAMissingSnapshot() {
		final HedgehogCli.Result result = sign();

		assertThat(result.exitCode(), equalTo(1));
		assertThat(result.err(), containsString("There is no snapshot at " + snapshot));
		assertNothingSigned();
	}

	@Example
	public void shouldRejectAnUntrustedKey() {
		existingSnapshot(CONTENT);
		keyIsTrusted(false);

		final HedgehogCli.Result result = sign();

		assertThat(result.exitCode(), equalTo(2));
		assertThat(result.err(), containsString("not one the network trusts"));
		assertNothingSigned();
	}

	@Example
	public void shouldRefuseASnapshotThisBuildCannotRead() {
		existingSnapshot(CONTENT);
		inspectorFinds(null, new IOException("Snapshot is format version 3, this build reads 2"));

		final HedgehogCli.Result result = sign();

		assertThat(result.exitCode(), equalTo(2));
		assertThat(result.err(), containsString("format version"));
		result.assertNoStackTrace();
		assertNothingSigned();
	}

	@Example
	public void shouldRefuseASnapshotWhoseSignatureDoesNotVerify() {
		existingSnapshot(CONTENT);
		inspectorFinds(SignatureStatus.INVALID, null);

		final HedgehogCli.Result result = sign();

		assertThat(result.exitCode(), equalTo(2));
		assertThat(result.err(), containsString("does not verify"));
		assertNothingSigned();
	}

	@Example
	public void shouldLeaveAnExistingSignatureAlone() {
		existingSnapshot(CONTENT);
		signatureIsPresent(true);

		final HedgehogCli.Result result = sign();

		assertThat(result.exitCode(), equalTo(1));
		assertThat(result.err(), containsString("is already signed, pass --force to replace it"));
		assertNothingSigned();
	}

	/* A replaced signature must not stay behind the content, or the new one would be appended after it */
	@SneakyThrows
	@Property(tries = 30)
	public void shouldCutTheOldSignatureBeforeSigningAgain(@ForAll @Size(max = 512) byte[] content,
		@ForAll @Size(max = 256) byte[] oldSignature) {

		final byte[] contents = Arrays.copyOf(content, content.length + oldSignature.length);

		System.arraycopy(oldSignature, 0, contents, content.length, oldSignature.length);
		existingSnapshot(contents);
		contentIs(content.length);
		signatureIsPresent(true);

		assertThat(sign("--force").exitCode(), equalTo(0));
		assertThat(Files.readAllBytes(snapshot), equalTo(content));
		assertThat(signings, hasSize(1));
	}
}

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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.unigrid.hedgehog.model.crypto.NetworkKey;

/*
   Most daemons will never be asked about the legacy chain, and a snapshot costs several hundred
   megabytes of mapped pages, so the file is only opened the first time somebody asks for it.
*/
@Slf4j
@RequiredArgsConstructor
public final class BootstrapSnapshot {
	@Getter private final Path path;
	private final AtomicReference<SnapshotReader> reader = new AtomicReference<>();
	private final AtomicReference<FileIdentity> opened = new AtomicReference<>();
	private final AtomicReference<Refusal> refusal = new AtomicReference<>();

	public Optional<SnapshotReader> getReader() {
		if (Files.exists(path) && needsOpen()) {
			open();
		}

		return Optional.ofNullable(reader.get());
	}

	private boolean needsOpen() {
		return reader.get() == null ? !isRefused() : !matches(opened.get());
	}

	private boolean isRefused() {
		final Refusal known = refusal.get();

		return known != null && known.getGeneration() == NetworkKey.getGeneration() && matches(known.getFile());
	}

	private boolean matches(FileIdentity previous) {
		return previous != null && FileIdentity.of(path).map(previous::equals).orElse(false);
	}

	/* The board keys are taken as they are when the open begins, so that keys learnt of meanwhile make the refusal
	   stale on the next request */
	private synchronized void open() {
		if (!needsOpen()) {
			return;
		}

		final long generation = NetworkKey.getGeneration();

		try {
			reader.set(SnapshotReader.open(path));
			opened.set(FileIdentity.of(path).orElse(null));
			refusal.set(null);
			log.atInfo().log("Opened the legacy chain snapshot at {}", path);

			if (reader.get().getInfo().getSignature() == SignatureStatus.UNSIGNED) {
				log.atWarn().log("The legacy chain snapshot at {} carries no signature and has"
					+ " not been verified", path);
			}
		} catch (IOException ex) {
			reader.set(null);
			refusal.set(new Refusal(FileIdentity.of(path).orElse(null), generation));
			log.atWarn().log("Could not open the legacy chain snapshot at {}: {}", path, ex.getMessage());
		}
	}

	/*
	   A snapshot is opened at most once for as long as its size and modification time stay the
	   same, whether the last attempt was served from a reader or was refused: a persistently
	   broken file is digested only once, and a file replaced in place by `bootstrap fetch`, signed
	   or not, is picked up on the next request without a restart.
	*/
	@Value
	private static final class FileIdentity {
		private long size;
		private FileTime modified;

		static Optional<FileIdentity> of(Path path) {
			try {
				return Optional.of(new FileIdentity(Files.size(path), Files.getLastModifiedTime(path)));
			} catch (IOException ex) {
				return Optional.empty();
			}
		}
	}

	/*
	   Whether a signature verifies depends on the board keys the node holds, which grow as it learns of the changes
	   to them, so a refusal also stands only for the keys it was judged against.
	*/
	@Value
	private static final class Refusal {
		private FileIdentity file;
		private long generation;
	}
}

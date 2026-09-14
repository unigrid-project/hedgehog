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

/*
   Most daemons will never be asked about the legacy chain, and a snapshot costs several hundred
   megabytes of mapped pages, so the file is only opened the first time somebody asks for it.
*/
@Slf4j
@RequiredArgsConstructor
public final class BootstrapSnapshot {
	@Getter private final Path path;
	private final AtomicReference<SnapshotReader> reader = new AtomicReference<>();
	private final AtomicReference<FileIdentity> refusal = new AtomicReference<>();

	public Optional<SnapshotReader> getReader() {
		if (reader.get() == null && Files.exists(path) && !matchesRefusal()) {
			open();
		}

		return Optional.ofNullable(reader.get());
	}

	private boolean matchesRefusal() {
		final FileIdentity previous = refusal.get();

		return previous != null && FileIdentity.of(path).map(previous::equals).orElse(false);
	}

	private synchronized void open() {
		if (reader.get() != null || matchesRefusal()) {
			return;
		}

		try {
			reader.set(SnapshotReader.open(path));
			log.atInfo().log("Opened the legacy chain snapshot at {}", path);

			if (reader.get().getInfo().getSignature() == SignatureStatus.UNSIGNED) {
				log.atWarn().log("The legacy chain snapshot at {} carries no signature and has"
					+ " not been verified", path);
			}
		} catch (IOException ex) {
			refusal.set(FileIdentity.of(path).orElse(null));
			log.atWarn().log("Could not open the legacy chain snapshot at {}: {}", path, ex.getMessage());
		}
	}

	/*
	   A refused snapshot is cached by size and modification time rather than outright, so a
	   persistently broken file is digested only once, while a file replaced in place by
	   `bootstrap fetch` is picked up on the next request without a restart.
	*/
	@Value
	private static class FileIdentity {
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
}

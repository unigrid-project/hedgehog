/*
    Unigrid Hedgehog
    Copyright © 2021-2023 The Unigrid Foundation, UGD Software AB

    This program is free software: you can redistribute it and/or modify it under the terms of the
    addended GNU Affero General Public License as published by the The Unigrid Foundation and
    the Free Software Foundation, version 3 of the License (see COPYING and COPYING.addendum).

    This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
    even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU Affero General Public License and the addendum for more details.

    You should have received an addended copy of the GNU Affero General Public License with this program.
    If not, see <http://www.gnu.org/licenses/> and <https://github.com/unigrid-project/hedgehog>.
 */

package org.unigrid.hedgehog.nativeimage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

import org.apache.commons.compress.utils.IOUtils;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import org.apache.commons.exec.CommandLine;
import org.apache.commons.exec.DefaultExecutor;
import org.apache.commons.exec.ExecuteException;
import org.apache.commons.exec.ExecuteWatchdog;
import org.apache.commons.lang3.ArrayUtils;
import org.unigrid.hedgehog.common.model.ApplicationDirectory;

/**
 * Wrapper and Launcher for the Hedgehog Native Image.
 * Handles environment extraction, script sanitization (CRLF to LF), and execution.
 */
public class NativeImage {
	/** Watchdog timeout. */
	public static final long WATCHDOG_TIMEOUT_MS = 60000;

	private final ApplicationDirectory appDir;
	private final String zipName;

	public NativeImage() {
		this.appDir = ApplicationDirectory.create();
		this.zipName = NativeProperties.getBundledJlinkZip().toString();
	}

	private int executePayload(Path basePath, String[] args)
			throws ExecuteException, InterruptedException, IOException {

		final Path binDir = basePath.resolve(NativeProperties.BIN_DIRECTORY);
		final Path script = binDir.resolve(NativeProperties.getRunScript());

		if (Files.notExists(script)) {
			throw new IOException("Script missing: " + script.toAbsolutePath());
		}

		String content = Files.readString(script, StandardCharsets.UTF_8);
		if (content.contains("\r\n")) {
			System.out.println("[Hedgehog] Fixing line endings...");
			Files.writeString(script, content.replace("\r\n", "\n"), StandardCharsets.UTF_8);
		}

		script.toFile().setExecutable(true);
		final CommandLine cmdLine = new CommandLine(script.toString());
		cmdLine.addArguments(args);

		final DefaultExecutor executor = new DefaultExecutor();
		executor.setWorkingDirectory(basePath.toFile());
		executor.setExitValue(0);

		try {
			final ExecuteWatchdog watchdog = new ExecuteWatchdog(WATCHDOG_TIMEOUT_MS);
			executor.setWatchdog(watchdog);
			return executor.execute(cmdLine);
		} catch (ExecuteException ex) {
			if (ex.getExitValue() != 1 && ex.getExitValue() != 2) {
				throw ex;
			}
			return ex.getExitValue();
		}
	}

	private void sanitizeTopLevel(Path root) throws IOException {
		try (Stream<Path> stream = Files.list(root)) {
			var folderToRename = stream
					.filter(Files::isDirectory)
					.filter(p -> p.getFileName().toString().contains(":"))
					.findFirst();

			if (folderToRename.isPresent()) {
				Path source = folderToRename.get();
				String safeName = source.getFileName().toString().replace(":", "-");
				Path target = source.getParent().resolve(safeName);

				System.out.println("[Hedgehog] Sanitizing archive folder name...");
				Files.move(source, target);
			}
		}
	}

	private InputStream getArchiveStream() throws IOException {
		Path externalPath = Paths.get(zipName);
		if (Files.exists(externalPath)) {
			return Files.newInputStream(externalPath);
		}
		InputStream internal = NativeImage.class.getResourceAsStream("/" + zipName);
		if (internal == null) {
			internal = NativeImage.class.getClassLoader().getResourceAsStream(zipName);
		}
		if (internal == null) {
			throw new IOException("Could not locate bundled ZIP: "
					+ zipName);
		}
		return internal;
	}

	public void launch(String[] args) throws Exception {
		if (NativeProperties.getHash() == null) {
			throw new IllegalStateException("NativeProperties not initialized.");
		}

		String safeHash = NativeProperties.getHash().replace(":", "-");
		final Path targetDist = appDir.getUserDataDir().resolve(safeHash);

		if (Files.notExists(targetDist) || ArrayUtils.contains(args, "--force-unpack")) {
			System.out.println("[Hedgehog] Extracting to: " + targetDist);
			Files.createDirectories(targetDist);
			try (InputStream archive = getArchiveStream()) {
				final byte[] bytes = IOUtils.toByteArray(archive);
				try (SeekableByteChannel ch = new SeekableInMemoryByteChannel(bytes)) {
					Unzipper.unzip(ch, targetDist);
				}
			}
			sanitizeTopLevel(targetDist);
		}

		Path finalPath = targetDist;
		Path checkScript = finalPath.resolve(NativeProperties.BIN_DIRECTORY)
				.resolve(NativeProperties.getRunScript());

		if (Files.notExists(checkScript)) {
			try (Stream<Path> stream = Files.list(targetDist)) {
				var subDir = stream.filter(Files::isDirectory).findFirst();
				if (subDir.isPresent()) {
					finalPath = subDir.get();
				}
			}
		}

		System.out.println("[Hedgehog] Starting payload from: " + finalPath.getFileName());
		int exitCode = executePayload(finalPath,
				ArrayUtils.removeAllOccurrences(args, "--force-unpack"));
		System.exit(exitCode);
	}

	public static void main(String[] args) throws Exception {
		NativeImage launcher = new NativeImage();
		launcher.launch(args);
	}
}


















/*
    Unigrid Hedgehog
    Copyright © 2021-2026 The Unigrid Foundation

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
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import net.harawata.appdirs.impl.WindowsAppDirs;
import org.apache.commons.exec.OS;
import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.Feature.DuringSetupAccess;
import org.graalvm.nativeimage.hosted.Feature.IsInConfigurationAccess;
import org.graalvm.nativeimage.hosted.RuntimeClassInitialization;
import org.graalvm.nativeimage.hosted.RuntimeResourceAccess;
import org.unigrid.hedgehog.common.model.Version;

public class BundleFeature implements Feature {
	@Override
	public boolean isInConfiguration(IsInConfigurationAccess access) {
		return true;
	}

	private Path findJlinkArchive() throws IOException {
		String location = getClass().getProtectionDomain().getCodeSource().getLocation().getPath();

		if (OS.isFamilyWindows() && location.startsWith("/")) {
			location = location.substring(1);
		}

		final Path targetDirectory = Paths.get(location).getParent();
		final AtomicReference<Optional<Path>> archive = new AtomicReference(Optional.empty());

		try (DirectoryStream<Path> dirStream = Files.newDirectoryStream(targetDirectory, "*-jlink.zip")) {
			dirStream.forEach(path -> {
				archive.set(Optional.of(path));
			});
		}

		if (archive.get().isEmpty()) {
			throw new IllegalStateException("JLink archive is required for proper operation");
		}

		return archive.get().get();
	}

	private String hash(byte[] data) {
		try {
			final MessageDigest digest = MessageDigest.getInstance("SHA");
			return HexFormat.of().formatHex(digest.digest(data));
		} catch (NoSuchAlgorithmException ex) {
			ex.printStackTrace();
			throw new IllegalStateException("SHA-1 not found in JVM, cannot create bundle", ex);
		}
	}

	@Override
	public void duringSetup(DuringSetupAccess access) {
		try {
			final Path jlinkArchive = findJlinkArchive();
			System.out.println(String.format("Including JLink image at '%s'", jlinkArchive));
			final byte[] data = Files.readAllBytes(jlinkArchive);

			NativeProperties.setBundledJlinkZip(jlinkArchive.getFileName());
			NativeProperties.setHash(hash(data));

			RuntimeResourceAccess.addResource(getClass().getModule(),
				jlinkArchive.getFileName().toString(), data
			);

			/* Primarily initializes Logback, SL4J & Commons Compress */

			RuntimeClassInitialization.initializeAtBuildTime(NativeProperties.class);
			RuntimeClassInitialization.initializeAtBuildTime(OS.class);
			RuntimeClassInitialization.initializeAtBuildTime(Version.class);
			RuntimeClassInitialization.initializeAtBuildTime(WindowsAppDirs.FolderId.class);
			RuntimeClassInitialization.initializeAtBuildTime("ch.qos.logback");
			RuntimeClassInitialization.initializeAtBuildTime("org.apache.commons.compress");
			RuntimeClassInitialization.initializeAtBuildTime("org.apache.commons.io");
			RuntimeClassInitialization.initializeAtBuildTime("org.slf4j");

		} catch (IOException ex) {
			throw new IllegalStateException("Failed to bundle the jlink image into the launcher", ex);
		}
	}
}

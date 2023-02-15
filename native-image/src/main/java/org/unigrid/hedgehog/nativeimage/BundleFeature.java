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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.core.status.StatusBase;
import ch.qos.logback.core.util.Loader;
import ch.qos.logback.core.util.StatusPrinter;
import com.oracle.svm.core.jni.JNIRuntimeAccess;
import com.sun.jna.platform.win32.Shell32;
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
import org.apache.commons.exec.OS;
import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.Feature.DuringSetupAccess;
import org.graalvm.nativeimage.hosted.Feature.IsInConfigurationAccess;
import org.graalvm.nativeimage.hosted.RuntimeClassInitialization;
import org.graalvm.nativeimage.hosted.RuntimeResourceAccess;
import org.slf4j.LoggerFactory;
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

			RuntimeClassInitialization.initializeAtBuildTime(Level.class);
			RuntimeClassInitialization.initializeAtBuildTime(Loader.class);
			RuntimeClassInitialization.initializeAtBuildTime(LoggerFactory.class);
			RuntimeClassInitialization.initializeAtBuildTime(Logger.class);
			RuntimeClassInitialization.initializeAtBuildTime(NativeProperties.class);
			RuntimeClassInitialization.initializeAtBuildTime(OS.class);
			RuntimeClassInitialization.initializeAtBuildTime(StatusBase.class);
			RuntimeClassInitialization.initializeAtBuildTime(StatusPrinter.class);
			RuntimeClassInitialization.initializeAtBuildTime(Version.class);
			RuntimeClassInitialization.initializeAtBuildTime("org.apache.commons.compress");

		} catch (IllegalStateException | IOException ex) {
			System.err.println("Failed to bundle required resources for archive");
			ex.printStackTrace();
			System.exit(0);
		}
	}
}

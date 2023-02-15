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

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Platform;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.commons.compress.utils.IOUtils;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import org.apache.commons.exec.CommandLine;
import org.apache.commons.exec.DefaultExecutor;
import org.apache.commons.exec.ExecuteException;
import org.apache.commons.exec.ExecuteWatchdog;
import org.apache.commons.lang3.ArrayUtils;
import org.graalvm.polyglot.Context;
import org.unigrid.hedgehog.common.model.ApplicationDirectory;

public class NativeImage {
	/*static {
		System.loadLibrary("/com/sun/jna/win32-x86-64/jnidispatch.dll");
	}*/

	public static final long WATCHDOG_TIMEOUT_MS = 60000;
	public static final long ERROR_26_WAIT_MS = 50;

	private static int start(Path basePath, String[] args) throws ExecuteException, InterruptedException, IOException {
		final Path script = basePath.resolve(Path.of(
			NativeProperties.BIN_DIRECTORY, NativeProperties.getRunScript())
		);

		final CommandLine cmdLine = CommandLine.parse(String.format("%s %s",
			script.toString(), String.join(" ", args))
		);

		final DefaultExecutor executor = new DefaultExecutor();
		executor.setExitValue(0);

		/* We retry untill some unrecoverable error is detected */
		while (true) {
			try {
				final ExecuteWatchdog watchdog = new ExecuteWatchdog(WATCHDOG_TIMEOUT_MS);
				executor.setWatchdog(watchdog);
				return executor.execute(cmdLine);

			/* error code 2 is just a generic error from PicoCLI that we can ignore */
			} catch (ExecuteException ex) {
				if (ex.getExitValue() != 2) {
					throw ex;
				}

				return ex.getExitValue();
			} catch (IOException ex) {
				/* Error 26 is "Text file busy", which can happen during rapid turnarounds */
				if (ex.getMessage().contains("error=26")) {
					Thread.sleep(ERROR_26_WAIT_MS);
				} else {
					throw ex;
				}
			}
		}
	}

	interface Shell32 extends Library {
		Shell32 INSTANCE = Native.load(Shell32.class);

		void printf(String format, Object... args);
	}

	public static void main(String[] args) throws ExecuteException, InterruptedException, IOException {
	        //CLibrary INSTANCE = Native.load(Platform.isWindows() ? "msvcrt" : "c", CLibrary.class);




		final InputStream archive = Thread.currentThread().getContextClassLoader()
			.getResourceAsStream(NativeProperties.getBundledJlinkZip().toString());

		final ApplicationDirectory applicationDirectory = ApplicationDirectory.create();
		final Path jlinkDistribution = applicationDirectory.getUserDataDir().resolve(Path.of(NativeProperties.getHash()));

		if (Files.notExists(jlinkDistribution) || ArrayUtils.contains(args, "--force-unpack")) {
			final SeekableByteChannel channel = new SeekableInMemoryByteChannel(IOUtils.toByteArray(archive));
			Unzipper.unzip(channel, applicationDirectory.getUserDataDir());
		}

		start(jlinkDistribution, ArrayUtils.removeAllOccurrences(args, "--force-unpack"));
	}
}

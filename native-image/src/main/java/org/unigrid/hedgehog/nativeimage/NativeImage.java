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

import java.nio.channels.SeekableByteChannel;
import java.nio.file.*;
import org.apache.commons.compress.utils.*;
import org.apache.commons.exec.*;
import org.apache.commons.lang3.ArrayUtils;
import org.unigrid.hedgehog.common.model.ApplicationDirectory;

public class NativeImage {
    public static void main(String[] args) throws Exception {
        // Vi hämtar sökvägen och säkerställer att den tolkas korrekt
        Path archivePath = Paths.get(NativeProperties.getBundledJlinkZip().toString());
        
        final ApplicationDirectory appDir = ApplicationDirectory.create();
        final Path jlinkDist = appDir.getUserDataDir().resolve(NativeProperties.getHash());

        if (Files.notExists(jlinkDist) || ArrayUtils.contains(args, "--force-unpack")) {
            try (var is = Files.newInputStream(archivePath)) {
                SeekableByteChannel channel = new SeekableInMemoryByteChannel(IOUtils.toByteArray(is));
                Unzipper.unzip(channel, appDir.getUserDataDir());
            }
        }
        
        start(jlinkDist, ArrayUtils.removeAllOccurrences(args, "--force-unpack"));
    }

    private static void start(Path basePath, String[] args) throws Exception {
        final Path script = basePath.resolve(NativeProperties.BIN_DIRECTORY).resolve(NativeProperties.getRunScript());
        CommandLine cmdLine = new CommandLine(script.toString());
        cmdLine.addArguments(args);
        
        DefaultExecutor executor = new DefaultExecutor();
        executor.setWatchdog(new ExecuteWatchdog(60000));
        executor.execute(cmdLine);
    }
}





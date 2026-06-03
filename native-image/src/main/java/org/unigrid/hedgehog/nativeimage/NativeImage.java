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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

import org.apache.commons.exec.CommandLine;
import org.apache.commons.exec.DefaultExecutor;
import org.apache.commons.lang3.ArrayUtils;
import org.unigrid.hedgehog.common.model.ApplicationDirectory;

public class NativeImage {
    public static void main(String[] args) throws Exception {
        // Ändrat till "application.properties" utan inledande /
        try (InputStream is = NativeImage.class.getClassLoader().getResourceAsStream("application.properties")) {
            if (is == null) {
                System.err.println("VARNING: application.properties hittades inte!");
            } else {
                Properties props = new Properties();
                props.load(is);
            }
        }

        String hash = NativeProperties.getHash();
        final ApplicationDirectory appDir = ApplicationDirectory.create();
        final Path jlinkDist = appDir.getUserDataDir().resolve(hash);

        if (Files.notExists(jlinkDist) || ArrayUtils.contains(args, "--force-unpack")) {
            // Ändrat till "jlink.zip" utan inledande /
            try (InputStream is = NativeImage.class.getClassLoader().getResourceAsStream("jlink.zip")) {
                if (is != null) {
                    Path tempZip = Files.createTempFile("hedgehog-jlink", ".zip");
                    Files.copy(is, tempZip, StandardCopyOption.REPLACE_EXISTING);
                    try (SeekableByteChannel channel = Files.newByteChannel(tempZip)) {
                        Unzipper.unzip(channel, appDir.getUserDataDir());
                    }
                    Files.deleteIfExists(tempZip);
                } else {
                    // Ändrat felmeddelande utan /
                    throw new RuntimeException("Kunde inte hitta jlink.zip i resurserna!");
                }
            }
        }
        start(jlinkDist, ArrayUtils.removeAllOccurrences(args, "--force-unpack"));
    }

    private static void start(Path basePath, String[] args) throws Exception {
        Path script = basePath.resolve(NativeProperties.BIN_DIRECTORY).resolve(NativeProperties.getRunScript());
        if (!Files.exists(script)) throw new IOException("Start script missing at: " + script.toAbsolutePath());
        
        CommandLine cmdLine = new CommandLine(System.getProperty("os.name").toLowerCase().contains("win") ? "cmd.exe" : "/bin/sh");
        if (!cmdLine.getExecutable().equals("cmd.exe")) cmdLine.addArgument(script.toString());
        cmdLine.addArguments(args);
        
        DefaultExecutor executor = new DefaultExecutor();
        executor.setExitValues(new int[]{0, 143, 2});
        executor.execute(cmdLine);
    }
}


















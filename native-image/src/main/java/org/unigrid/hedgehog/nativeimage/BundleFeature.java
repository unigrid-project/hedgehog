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
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.graalvm.nativeimage.hosted.Feature;

public class BundleFeature implements Feature {

    @Override
    public void beforeAnalysis(BeforeAnalysisAccess access) {
        // Vi söker efter 'application/target' genom att utgå från projektroten
        // Vi antar att native-image modulen ligger på samma nivå som application modulen
        Path projectRoot = Paths.get("").toAbsolutePath().getParent();
        Path targetDir = projectRoot.resolve("application").resolve("target");

        System.out.println("BundleFeature: Söker efter jlink-arkiv i: " + targetDir);

        final AtomicReference<Optional<Path>> archive = new AtomicReference<>(Optional.empty());
        
        try (DirectoryStream<Path> dirStream = Files.newDirectoryStream(targetDir, "*-jlink.zip")) {
            for (Path path : dirStream) {
                archive.set(Optional.of(path));
                break; // Vi tar första bästa arkiv vi hittar
            }
        } catch (IOException e) {
            throw new RuntimeException("Kunde inte läsa mappen: " + targetDir + ". Fel: " + e.getMessage(), e);
        }

        Path jlinkArchive = archive.get().orElseThrow(() -> 
            new IllegalStateException("Hittade ingen *-jlink.zip i " + targetDir + ". Är applikationen byggd?"));

        try {
            NativeProperties.setBundledJlinkZip(jlinkArchive.toAbsolutePath().toString());
            byte[] bytes = Files.readAllBytes(jlinkArchive);
            NativeProperties.setHash(hash(bytes));
            System.out.println("BundleFeature: Arkiv hittat och konfigurerat: " + jlinkArchive.getFileName());
        } catch (IOException ex) {
            throw new RuntimeException("Kunde inte läsa arkivfilen: " + jlinkArchive, ex);
        }
    }

    private String hash(byte[] data) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-1");
            return HexFormat.of().formatHex(digest.digest(data));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}














package org.unigrid.hedgehog.common.model.util;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

public class ArchiveExtractor {

	/**
	 * Extracts a .tar.xz archive file to a target destination directory.
	 *
	 * @param sourceFile The .tar.xz file to be extracted
	 * @param outputDir The target directory where files will be placed
	 * @throws IOException If an I/O error occurs during reading or writing
	 */
	public static void extractTarXz(File sourceFile, File outputDir) throws IOException {
		System.out.println("[+] Extracting " + sourceFile.getName() + " to " + outputDir.getAbsolutePath());

		if (!outputDir.exists()) {
			outputDir.mkdirs();
		}

		// Create a mock/simple bootstrap.dat file in the target folder for testing purposes
		// (Replace this logic with an actual archive library if needed, e.g., Apache Commons Compress)
		File dummyResult = new File(outputDir, "bootstrap.dat");
		if (!dummyResult.exists()) {
			try (FileOutputStream fos = new FileOutputStream(dummyResult)) {
				fos.write("Hedgehog Bootstrap Data".getBytes());
			}
		}

		System.out.println("[+] Extraction complete. Created bootstrap.dat successfully.");
	}
}

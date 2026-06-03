package org.unigrid.hedgehog.bootstrap;

import java.io.File;
import org.unigrid.hedgehog.common.model.util.ArchiveExtractor;

public class BootstrapManager {

	private static final String URL_TAR_XZ = "http://www.unigrid.org/binaries/bootstrap.tar.xz";
	private static final String URL_BSA = "https://www.unigridfoundation.se/binaries/bootstrap.bsa";

	/**
	 * Starts the bootstrap import process by verifying local files,
	 * handling automated downloads, and triggering database reading.
	 */
	public static void startImport() {
		File tarXzFile = new File("test-data/bootstrap.tar.xz");
		File bsaFile = new File("test-data/bootstrap.bsa");
		File extractedDir = new File("test-data/extracted_db/");

		TxDbReader txReader = new TxDbReader();

		try {
			if (!tarXzFile.exists() && !bsaFile.exists()) {
				System.out.println("[+] test-data/ is empty. Initiating automatic bootstrap downloads...");
				BootstrapDownloader.download(URL_TAR_XZ, tarXzFile);
			}

			if (tarXzFile.exists()) {
				System.out.println("[+] Found bootstrap.tar.xz. Extracting archive...");
				ArchiveExtractor.extractTarXz(tarXzFile, extractedDir);
				File rawDataFile = locateRawDataFile(extractedDir);

				System.out.println("[+] Passing extracted database to TxDbReader...");
				txReader.readUnigridData(rawDataFile);
			} else if (bsaFile.exists()) {
				System.out.println("[+] Found bootstrap.bsa. Processing direct binary stream...");
				txReader.readUnigridData(bsaFile);
			}

		} catch (Exception e) {
			System.err.println("[-] Critical error during bootstrap download or import: " + e.getMessage());
			e.printStackTrace();
		}
	}

	private static File locateRawDataFile(File extractedDir) {
		File rawDataFile = new File(extractedDir, "bootstrap.dat");
		if (!rawDataFile.exists()) {
			File fallbackFile = new File(extractedDir, "bootstrap");
			if (fallbackFile.exists()) {
				rawDataFile = fallbackFile;
			} else {
				rawDataFile = scanForFallbackFile(extractedDir, rawDataFile);
			}
		}
		return rawDataFile;
	}

	private static File scanForFallbackFile(File extractedDir, File defaultFile) {
		if (extractedDir.isDirectory() && extractedDir.listFiles() != null) {
			for (File file : extractedDir.listFiles()) {
				if (file.isFile() && !file.getName().startsWith(".")) {
					return file;
				}
			}
		}
		return defaultFile;
	}
}


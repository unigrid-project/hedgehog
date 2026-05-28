package org.unigrid.hedgehog.bootstrap;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;

public class BootstrapDownloader {

	/**
	 * Downloads a file from a URL and saves it to the specified destination.
	 * Implemented from scratch using standard Java networking.
	 *
	 * @param urlString   The source URL string to download from
	 * @param destination The target local File destination
	 * @throws IOException If an I/O error occurs during download or writing
	 */
	public static void download(String urlString, File destination) throws IOException {
		System.out.println("[+] Starting download: " + urlString);
		System.out.println("[+] Target destination: " + destination.getAbsolutePath());

		// Create parent directories (like test-data/) if they don't exist
		if (destination.getParentFile() != null) {
			destination.getParentFile().mkdirs();
		}

		URL url = new URL(urlString);
		HttpURLConnection connection = (HttpURLConnection) url.openConnection();
		connection.setRequestMethod("GET");

		// Open streams to transfer data
		try (BufferedInputStream in = new BufferedInputStream(connection.getInputStream());
				FileOutputStream fileOutputStream = new FileOutputStream(destination)) {

			byte[] dataBuffer = new byte[8192]; // 8 KB chunks
			int bytesRead;
			long totalBytesRead = 0;

			while ((bytesRead = in.read(dataBuffer, 0, dataBuffer.length)) != -1) {
				fileOutputStream.write(dataBuffer, 0, bytesRead);
				totalBytesRead += bytesRead;

				// Print progress status for every 20 Megabytes downloaded
				if (totalBytesRead % (20 * 1024 * 1024) == 0) {
					System.out.println("[+] Downloaded: " + (totalBytesRead / (1024 * 1024)) + " MB...");
				}
			}
			System.out.println("[+] Download complete for: " + destination.getName());
		} finally {
			connection.disconnect();
		}
	}
}



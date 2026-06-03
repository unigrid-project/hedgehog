package org.unigrid.hedgehog.common.model.util;

import java.io.IOException;
import java.io.InputStream;

public class VarIntReader {

	/**
	 * Reads a Bitcoin/Unigrid-style VarInt from a binary input stream.
	 *
	 * @param stream The binary input stream to read from
	 * @return The decoded long value of the VarInt
	 * @throws IOException If EOF is reached unexpectedly or an I/O error occurs
	 */
	public static long readVarInt(InputStream stream) throws IOException {
		long n = 0;
		while (true) {
			int ch = stream.read();
			if (ch == -1) {
				throw new IOException("EOF reached while reading VarInt");
			}
			n = (n << 7) | (ch & 0x7F);
			if ((ch & 0x80) != 0) {
				n++;
			} else {
				break;
			}
		}
		return n;
	}
}


/*
	The Janus Wallet
	Copyright © 2021-2022 The Unigrid Foundation, UGD Software AB

	This program is free software: you can redistribute it and/or modify it under the terms of the
	addended GNU Affero General Public License as published by the Free Software Foundation, version 3
	of the License (see COPYING and COPYING.addendum).

	This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
	even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
	GNU Affero General Public License for more details.

	You should have received an addended copy of the GNU Affero General Public License with this program.
	If not, see <http://www.gnu.org/licenses/> and <https://github.com/unigrid-project/janus-java>.
 */
package org.unigrid.hedgehog.model.storage;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.commons.lang3.Conversion;
import org.unigrid.hedgehog.model.ApplicationDirectory;

@ApplicationScoped
public class DecayRate {

	public void run() {
		ApplicationDirectory appDir = new ApplicationDirectory();

		File dir = appDir.getUserDataDir().toFile();
		System.out.println(dir.getAbsolutePath());
		for (File dir2 : dir.listFiles()) {
			System.out.println(dir.getAbsolutePath());
			for (File dir3 : dir2.listFiles()) {
				System.out.println(dir3.getAbsolutePath());
				for (File file : dir3.listFiles()) {
					System.out.println(file.getAbsolutePath());
					try {
						RandomAccessFile randFile = new RandomAccessFile(file.getAbsolutePath(), "rwd");
						int i = randFile.readInt();
						System.out.println("int in file = " + i);
						i = i <= 0 ? 0 : i - 1;
						System.out.println("int after change = " + i);
						randFile.seek(0);
						randFile.write(intToByteArray(i), 0, 4);
						randFile.close();
					} catch (FileNotFoundException ex) {
						System.out.println(ex.getMessage());
						Logger.getLogger(DecayRate.class.getName()).log(Level.SEVERE, null, ex);
					} catch (IOException ex) {
						System.out.println(ex.getMessage());
						Logger.getLogger(DecayRate.class.getName()).log(Level.SEVERE, null, ex);
					}
				}
			}
		}
	}

	public static final byte[] intToByteArray(int value) {
		byte[] result = new byte[4];
		result[0] = (byte) ((value & 0xFF000000) >> 24);
		result[1] = (byte) ((value & 0x00FF0000) >> 16);
		result[2] = (byte) ((value & 0x0000FF00) >> 8);
		result[3] = (byte) ((value & 0x000000FF) >> 0);
		return result;
	}
}

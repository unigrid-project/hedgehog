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
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import org.unigrid.hedgehog.model.ApplicationDirectory;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.commons.codec.binary.Hex;

@ApplicationScoped
public class Storage {

	public void store(String key, BlockData blockData) {
		String path = mkDir(getFirstByte(key), getSecondByte(key));
		RandomAccessFile file;
		try {
			file = new RandomAccessFile(path + "/" + key, "rwd");
			FileChannel channel = file.getChannel();
			ByteBuf buff = Unpooled.buffer();
			buff.writeInt(blockData.getAccessed());
			buff.writeBytes(blockData.getBuffer());
			System.out.println(key);
			MappedByteBuffer out;
			out = channel.map(FileChannel.MapMode.READ_WRITE, 0, buff.array().length);
			out.put(buff.array());
			file.close();
		} catch (FileNotFoundException ex) {
			Logger.getLogger(Storage.class.getName()).log(Level.SEVERE, null, ex);
			System.out.println(ex.getMessage());
		} catch (IOException ex) {
			Logger.getLogger(Storage.class.getName()).log(Level.SEVERE, null, ex);
			System.out.println(ex.getMessage());
		}
	}

	public BlockData getFile(String key) {

		ByteBuf buff = Unpooled.buffer();
		String path = new ApplicationDirectory().getUserDataDir()
			+ "/"
			+ getFirstByte(key)
			+ "/"
			+ getSecondByte(key)
			+ "/"
			+ key;

		RandomAccessFile file;
		try {
			file = new RandomAccessFile(path, "rw");
		} catch (FileNotFoundException ex) {
			Logger.getLogger(Storage.class.getName()).log(Level.SEVERE, null, ex);
			return null;
		}
		ByteBuffer dst;
		try {
			dst = ByteBuffer.allocate((int) file.length());
		} catch (IOException ex) {
			Logger.getLogger(Storage.class.getName()).log(Level.SEVERE, null, ex);
			return null;
		}

		FileChannel channel = file.getChannel();
		try {
			channel.read(dst);
		} catch (IOException ex) {
			Logger.getLogger(Storage.class.getName()).log(Level.SEVERE, null, ex);
		}
		BlockData blockData = new BlockData();
		blockData.setAccessed(dst.flip().getInt());
		blockData.setBuffer(buff.setBytes(0, dst));
		return blockData;
	}

	public int getAccessed(String path) {
		RandomAccessFile file;
		int num = 0;
		try {
			file = new RandomAccessFile(path, "rw");
			num = file.readInt();
			file.close();
		} catch (FileNotFoundException ex) {
			Logger.getLogger(Storage.class.getName()).log(Level.SEVERE, null, ex);
		} catch (IOException ex) {
			Logger.getLogger(Storage.class.getName()).log(Level.SEVERE, null, ex);
		}
		
		return num;
	}

	public String mkDir(String firstByte, String secondByte) {
		Path dataDir = new ApplicationDirectory().getUserDataDir();
		File first = new File(dataDir.toString() + "/" + firstByte);
		File second = new File(first + "/" + secondByte);

		if (!first.exists()) {
			first.mkdir();
			if (!second.exists()) {
				second.mkdir();
			}
		} else {
			if (!second.exists()) {
				second.mkdir();
			}
		}
		return second.getAbsolutePath();
	}

	public String getFirstByte(String key) {
		char c = key.charAt(0);
		return Integer.toHexString(c);
	}

	public String getSecondByte(String key) {
		char c = key.charAt(1);
		return Integer.toHexString(c);
	}

	public String getHex(String key) {
		return Hex.encodeHexString(key.getBytes());
	}
}

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

import io.netty.buffer.ByteBuf;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.commons.codec.binary.Base32;
import org.apache.commons.collections4.map.AbstractMapDecorator;
import org.unigrid.hedgehog.model.ApplicationDirectory;
import org.unigrid.hedgehog.model.collection.SortedList;

public class PassiveMap<K, V> extends AbstractMapDecorator<K, V> implements StorageTranscoder {

	private Storage storage;

	private long maxSize = 2 * 1024 * 1024 * 1024 - 1;

	private long currentSize = 0;

	private HashMap<String, BlockData> map = new HashMap();

	private SortedList<WeigthedKey> sortedList = new SortedList();

	public PassiveMap() {
		super();

		storage = new Storage();
		populateMap();
	}

	public int size() {
		return map.size();
	}

	public boolean put(String key, BlockData blockData) {

		addToMap(key, blockData);
		byte[] bytes = key.getBytes();
		String byteKey = encode(bytes);
		try {
			storage.store(byteKey, blockData);
		} catch (IOException ex) {
			Logger.getLogger(PassiveMap.class.getName()).log(Level.SEVERE, null, ex);
			return false;
		}

		return true;
	}

	public ByteBuf get(String key) {
		WeigthedKey weigthedKey = new WeigthedKey(key,
			0.0,
			0,
			0,
			new Date());

		if (map.containsKey(key)) {
			int index = sortedList.indexOf(weigthedKey);
			WeigthedKey wKey = sortedList.get(index);
			wKey.increassesAccessed();
			map.get(key).setAccessed(map.get(key).getAccessed() + 1);
			changeListOrder(wKey);
			return map.get(key).getBuffer();
		}
		String byteKey = encode(key.getBytes());
		BlockData blockData = new BlockData();
		try {
			blockData = storage.getFile(byteKey);
		} catch (IOException ex) {
			Logger.getLogger(PassiveMap.class.getName()).log(Level.SEVERE, null, ex);
		}
		blockData.setAccessed(blockData.getAccessed() + 1);
		addToMap(key, blockData);

		return blockData.getBuffer();
	}
	
	private void changeListOrder(WeigthedKey key) {
		sortedList.remove(key);
		sortedList.add(key);
	}

	public WeigthedKeyInterface lastKey() {
		return sortedList.get(sortedList.size());
	}

	private void addToMap(String key, BlockData data) {

		WeigthedKey weigthedKey = new WeigthedKey(key,
			0.0,
			data.getAccessed(),
			data.getBuffer().capacity(),
			new Date());
		
		sortedList.add(weigthedKey);
		map.put(key, data);

		currentSize = currentSize + data.getBuffer().array().length;

		if (currentSize > maxSize) {
			removeFromMap();
		}
	}
	
	public double getWeigth(String key) {
		WeigthedKey weigthedKey = new WeigthedKey(key,
			0.0,
			0,
			0,
			new Date());
		int index = sortedList.indexOf(weigthedKey);
		WeigthedKey wKey = sortedList.get(index);
		return wKey.getWeigth();
	}

	private void removeFromMap() {
		while (currentSize > maxSize) {
			WeigthedKey key = sortedList.get(0);
			sortedList.remove(0);
			currentSize = currentSize - map.get(key.getKey()).getBuffer().array().length;
			map.remove(key.getKey());
		}
	}

	private void populateMap() {
		System.out.println(map);
		ApplicationDirectory appDir = new ApplicationDirectory();

		File dir = appDir.getUserDataDir().toFile();
		for (File dir2 : dir.listFiles()) {
			for (File dir3 : dir2.listFiles()) {
				for (File file : dir3.listFiles()) {
					try {	
						addToMap(file.getName(), storage.getFile(file.getName()));
					} catch (IOException ex) {
						Logger.getLogger(PassiveMap.class.getName()).log(Level.SEVERE, null, ex);
					}
				}
			}
		}
	}

	@Override
	public String encode(byte[] bytes) {
		Base32 base32 = new Base32();
		return base32.encodeToString(bytes);
	}

	@Override
	public byte[] decode(byte[] bytes) {
		Base32 base = new Base32();
		return base.decode(bytes);
	}
}

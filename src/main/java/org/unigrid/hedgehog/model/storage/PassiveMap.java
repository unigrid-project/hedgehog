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
import java.util.Date;
import java.util.TreeMap;
import org.apache.commons.codec.binary.Base32;
import org.apache.commons.collections4.map.AbstractMapDecorator;
import org.unigrid.hedgehog.model.ApplicationDirectory;

public class PassiveMap<K, V> extends AbstractMapDecorator<K, V> implements StorageTranscoder{

	//@Inject
	private Storage storage;
	
	private long maxSize = 2 * 1024 * 1024 * 1024 - 1;
	
	private long currentSize = 0;
	
	final private TreeMap<WeigthedKeyInterface<String, Integer>, BlockData> map =
		new TreeMap<WeigthedKeyInterface<String, Integer>, BlockData>((k1, k2) -> {
			System.out.println(k1.getClass());
			System.out.println(k2.getClass());
			if(k1.getWeigth()>= k2.getWeigth()){
				return 1;
			}
			return 0; 
		});;

	public PassiveMap() {
		super();
		
		storage = new Storage();
		populateMap();
	}
	
	public int size() {
		return map.size();
	}

	public WeigthedKeyInterface put(String key, BlockData blockData) {
		WeigthedKey weigthedKey = new WeigthedKey(key,
							0,
							blockData.getAccessed(), blockData.getBuffer().capacity(),
							new Date());
		
		addToMap(weigthedKey.getKey(), blockData);
		String s = (String) weigthedKey.getKey();
		byte[] bytes = s.getBytes();
		String byteKey = encode(bytes);
		storage.store(byteKey, blockData);
		
		return weigthedKey;
	}
	
	public ByteBuf get(String key) {
		if(map.containsKey(key)) {
			map.get(key).setAccessed(map.get(key).getAccessed() + 1);
			return map.get(key).getBuffer();
		}
		
		
		String byteKey = encode(key.getBytes());
		BlockData blockData = storage.getFile(byteKey);
		blockData.setAccessed(blockData.getAccessed() + 1);
		return blockData.getBuffer();
	}
	
	public BlockData getBlock(String key) {
		if(map.containsKey(key)) {
			map.get(key).setAccessed(map.get(key).getAccessed() + 1);
			return map.get(key);
		}
		BlockData blockData = storage.getFile(encode(key.getBytes()));
		blockData.setAccessed(blockData.getAccessed() + 1);
		return blockData;
	}
	
	private void updateMap(WeigthedKeyInterface key, BlockData data) {
		currentSize = currentSize + data.getBuffer().array().length;
		map.put(key, data);
	}
	
	private void addToMap(String key, BlockData data) {
		
		WeigthedKey weigthedKey = new WeigthedKey(key, 0, data.accessed, data.getBuffer().capacity(), new Date());
		if(currentSize < maxSize) {
			System.out.println("adding this to the map");
			updateMap(weigthedKey, data);
			return;
		}
		System.out.println("what is happening");
		for (Entry<WeigthedKeyInterface<String, Integer>, BlockData> entry : map.entrySet()) {
			if(entry.getValue().getAccessed() < data.getAccessed()) {
				map.put(weigthedKey, data);
				removeFromMap();
				return;
			}
		}
	}
	
	private void removeFromMap() {
		while(currentSize < maxSize) {
			map.remove(map.firstKey());
		}
	}
	
	private void populateMap() {
		System.out.println(map);
		ApplicationDirectory appDir = new ApplicationDirectory();
		
		File dir = appDir.getUserDataDir().toFile();
		for(File dir2 : dir.listFiles()) {
			for (File file : dir2.listFiles()) {
				storage.getAccessed(file.getAbsolutePath());
				addToMap(file.getName(), storage.getFile(file.getName()));
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
	
	/*
	private static class ValueCompare implements Comparator {

		private Map _data;
		
		public ValueCompare(Map data) {
			super();
			_data = data;
		}
		
		@Override
		public int compare(Object o1, Object o2) {
			int i1 = (int) _data.get(o1).getAccessed();
		}
		
	}
	*/
}

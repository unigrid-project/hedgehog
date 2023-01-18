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
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.File;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.apache.commons.collections4.map.AbstractMapDecorator;
import org.apache.commons.collections4.map.PassiveExpiringMap;
import org.unigrid.hedgehog.model.ApplicationDirectory;

@ApplicationScoped
public class PassiveMap<K, V> extends AbstractMapDecorator<K, V> {

	@Inject
	private Storage storage;
	
	private int maxSize = 2 * 1024 * 1024;
	
	private int curretnSize = 0;
	
	private TreeMap<String, BlockData> map;
	
	private void init() {
		//Find all local files and read the accesed number from them and build the map
		populateMap();
	}

	public PassiveMap(PassiveExpiringMap<K, V> map) {
		super(map);
	}

	public Optional<V> getOptional(K key) {
		final V value = get(key);

		if (get(key) == null) {
			return Optional.empty();
		}

		return Optional.of(value);
	}

	public void put(String key, BlockData blockData) {
		storage.store(key, blockData);
	}
	
	public ByteBuf get(String key) {
		BlockData blockData = new BlockData();
		blockData = storage.getFile(key);
		blockData.setAccessed(blockData.getAccessed() + 1);
		return blockData.getBuffer();
	}
	
	private void updateMap(BlockData blockData) {
		
	}
	
	private void addToMap(String key, BlockData data) {
		for (Entry<String, BlockData> entry : map.entrySet()) {
			if(entry.getValue().getAccessed() < data.getAccessed()) {
				map.put(key, data);
			}
		}
	}
	
	private void populateMap() {
		map = new TreeMap((k1, k2) -> {
			if(map.get(k1).getAccessed() > map.get(k2).getAccessed()){
				return 1;
			}
			return 0; 
		});
		ApplicationDirectory appDir = new ApplicationDirectory();
		
		File dir = appDir.getUserDataDir().toFile();
		for(File dir2 : dir.listFiles()) {
			for (File file : dir2.listFiles()) {
				storage.getAccessed(file.getAbsolutePath());
				addToMap(file.getName(), storage.getFile(file.getName()));
			}
		}
	}
	
}

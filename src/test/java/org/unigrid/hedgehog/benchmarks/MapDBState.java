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
package org.unigrid.hedgehog.benchmarks;

import java.util.Map;
import org.mapdb.DB;
import org.mapdb.DBMaker;
import org.mapdb.Serializer;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

@State(Scope.Benchmark)
public class MapDBState {
	
	public Map<String, String> map;
	public DB db;
	
	@Setup(Level.Invocation)
	public void setup() {
		// Create a MapDB database using the default file location
		db = DBMaker.fileDB(System.getProperty("user.home") + "/Documents/mapDB")
			//.executorEnable()
			//.fileChannelEnable()
			.make();
		// Create a PassiveExpiringMap that expires entries when the map size exceeds 100 entries
		map = db.hashMap("map", Serializer.JAVA, Serializer.JAVA).createOrOpen();
		
		db.commit();
	}
	
}

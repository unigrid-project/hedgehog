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

import java.io.File;
import java.nio.ByteBuffer;
import org.lmdbjava.Dbi;
import org.lmdbjava.DbiFlags;
import org.lmdbjava.Env;
import static org.lmdbjava.Env.create;
import org.lmdbjava.EnvFlags;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

@State(Scope.Benchmark)
public class LmdbState {
	private static final String DB_NAME = "hedgehoglmdb";
	//private static final String PATH = System.getProperty("user.dir") + "/Documents/";
	
	public Env<ByteBuffer> env;
	public Dbi<ByteBuffer> db;
	
	
	@Setup(Level.Iteration)
	public void setup() {
		
		final File path = new File(System.getProperty("user.dir"));
		env = create()
			.setMapSize(10_485_760)
			.setMaxDbs(1)
			.open(path);
		
		db = env.openDbi(DB_NAME, DbiFlags.MDB_CREATE);
	}
}

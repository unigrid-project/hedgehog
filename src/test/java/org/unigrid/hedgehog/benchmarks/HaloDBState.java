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

import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import com.oath.halodb.HaloDB;
import com.oath.halodb.HaloDBOptions;
import com.oath.halodb.HaloDBStats;
import java.io.File;
import lombok.SneakyThrows;

@State(Scope.Benchmark)
public class HaloDBState {
	
	public HaloDB db;
	public HaloDBOptions options;
	public String dir = "halodb";

	@SneakyThrows
	@Setup(Level.Iteration)
	public void setup() {
		File file = new File(dir);
		if(file.exists()) {
			
		}
		options = new HaloDBOptions();
		options.setMaxFileSize(1024 * 1024 * 1024);
		options.setMaxTombstoneFileSize(10 * 1024 * 1024);
		options.setBuildIndexThreads(2);
		options.setCompactionThresholdPerFile(0.7);
		options.setCompactionJobRate(50 * 1024 * 1024);
		options.setNumberOfRecords(100_000_000);
		options.setCleanUpTombstonesDuringOpen(true);
		options.setUseMemoryPool(true);
		options.setCleanUpInMemoryIndexOnClose(true);
		options.setMemoryPoolChunkSize(2 * 1024 * 1024);
		options.setFixedKeySize(50);
		
		//System.out.println("we are opening the database");
		//db = HaloDB.open(dir, options);
		//HaloDBStats stats = db.stats();
		//System.out.println(stats.toString());
	}
}

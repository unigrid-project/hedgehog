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

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.apache.commons.collections4.map.PassiveExpiringMap;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

@State(Scope.Benchmark)
public class BenchmarkData {

	@Param({/*"10", "100", */"1000", /*"10000", "100000"*/})
	public int intirations;

	@Param({ /*"TEN_BYTES", "ONE_KB",*/ "ONE_MB", /*"TEN_MB"*/ })
	public String sizeString;
	
	public int chunk;

	public int size = 100;

	public PassiveExpiringMap<String, byte[]> expieringMap;

	@Setup(Level.Iteration)
	public void setup() {

		chunk = Size.valueOf(sizeString).getChunk();
	}

	@AllArgsConstructor
	public enum Size {
		TEN_BYTES(10),
		ONE_KB(1024),
		ONE_MB(1024 * 1024),
		TEN_MB(10 * 1024 * 1024);

		@Getter
		private final int chunk;
	}
}

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

import java.util.Date;
import org.unigrid.hedgehog.jqwik.BaseMockedWeldTest;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Provide;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;

public class WeigthedKeyTest extends BaseMockedWeldTest {
	
	@Property
	public void testWeigthSystem(@ForAll("accessed")int accessed, @ForAll("size") int size) {
		WeigthedKey key = new WeigthedKey("laskdfnoi3eräpawos3", 0.0, accessed, size, new Date());
		
		System.out.println(accessed + " || " + size + " || " + key.getWeigth());
		
		assert(key.getWeigth() > 0.0);
	}
	
	@Provide
	Arbitrary<Integer> accessed() {
		return Arbitraries.of(0, 1, 123, 2, 1542, 53120, 19);
	}
	
	@Provide
	Arbitrary<Integer> size() {
		return Arbitraries.of(1024, 120, 1024 * 1024 * 50, 1024 * 1024 * 1, 1024 * 1024 * 256 - 1, 1024 * 1024 * 60);
	}
}

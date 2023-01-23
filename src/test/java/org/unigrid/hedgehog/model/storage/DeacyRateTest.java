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
import io.netty.buffer.Unpooled;
import jakarta.inject.Inject;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.BeforeExample;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Provide;
import net.jqwik.api.ForAll;
import net.jqwik.api.Arbitraries;
import org.unigrid.hedgehog.jqwik.BaseMockedWeldTest;
import org.unigrid.hedgehog.jqwik.WeldSetup;

//@WeldSetup(Storage.class)
public class DeacyRateTest extends BaseMockedWeldTest{
	
	//@Inject
	//Storage storage;
	
	@BeforeExample
	public void makeFiles() {
		String[] key = {"kadre124jndsop2mdask23", "jrdre124jjdsop4mdsask23", "lndre424jjd8op6mdsask23"};
		String s = "hello im am unigrid";
		
		Storage storage = new Storage();
		BlockData block1 = new BlockData();
		BlockData block2 = new BlockData();
		BlockData block3 = new BlockData();
		ByteBuf buff = Unpooled.buffer();
		block1.setAccessed(1);
		buff.writeBytes(s.getBytes());
		block1.setBuffer(buff);
		block2.setAccessed(5);
		block2.setBuffer(buff);
		block3.setAccessed(0);
		block3.setBuffer(buff);
		storage.store(key[0], block1);
		storage.store(key[1], block2);
		storage.store(key[2], block3);
	}
	
	
	@Example
	public void testTheDecayRateOfFiles(@ForAll("data")byte[] data) {
		Storage storage = new Storage();
		String[] key = {"kadre124jndsop2mdask23", "jrdre124jjdsop4mdsask23", "lndre424jjd8op6mdsask23"};
		
		//makeFiles(key, data);
		DecayRate decay = new DecayRate();
		decay.run();
		
		ByteBuf buff = Unpooled.buffer();
		
		BlockData testBlock1 = storage.getFile(key[0]);
		System.out.println("Block1 = " + testBlock1.getAccessed());
		assert(testBlock1.getAccessed() == 0);
		assert(testBlock1.getBuffer().equals(buff));
		
		BlockData testBlock2 = storage.getFile(key[1]);
		System.out.println("Block2 = " + testBlock2.getAccessed());
		assert(testBlock2.getAccessed() == 4);
		assert(testBlock2.getBuffer().equals(buff));
		
		BlockData testBlock3 = storage.getFile(key[2]);
		System.out.println("Block3 = " + testBlock3.getAccessed());
		assert(testBlock3.getAccessed() == 0);
		assert(testBlock3.getBuffer().equals(buff));
	}
	
	@Provide
	Arbitrary<byte[]> data() {
		String s = "hello im am unigrid";
		return Arbitraries.of(s.getBytes());
	}
	
	
}

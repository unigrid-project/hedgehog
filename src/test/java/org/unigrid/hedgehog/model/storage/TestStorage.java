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
import java.security.SecureRandom;
import java.util.concurrent.ThreadLocalRandom;
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Provide;
import net.jqwik.api.ForAll;
import net.jqwik.api.Arbitraries;
import org.unigrid.hedgehog.jqwik.BaseMockedWeldTest;

//@WeldSetup(Storage.class)
public class TestStorage extends BaseMockedWeldTest {
	byte[] bytes;

	//@Inject
	//Storage storage;
	
	@Example
	@SneakyThrows
	public void testStoringOneFile(@ForAll("key")String key, @ForAll("data") byte[] bytes) {
		Storage storage = new Storage();
		ByteBuf buff = Unpooled.buffer();
		buff.writeBytes(bytes);

		BlockData blockData = new BlockData();
		blockData.setAccessed(2);
		blockData.setBuffer(buff);
		System.out.println(key);
		storage.store(key, blockData);
	}
	
	@Example
	@SneakyThrows
	public void testGettingStoredOneFile(@ForAll("key") String key, @ForAll("data") byte[] bytes) {
		Storage storage = new Storage();
		boolean test = false;
		BlockData blockData = storage.getFile(key);
		
		if(blockData.getAccessed() == 2) {
			test = true;
			byte[] arr = blockData.getBuffer().array();
			for (int i = 0; i < bytes.length - 1; i++) {
				byte a = arr[i];
				byte b = bytes[i];
				if(a != b) {
					test = false;
				}
			}
		}
		assert(test);
	}
	
	@Provide
	Arbitrary<String> key() {
		return Arbitraries.of("kadre124jndsop2mdask23");
	}
	
	@Provide
	Arbitrary<byte[]> data() {
		String s = "hello im am unigrid";
		return Arbitraries.of(s.getBytes());
	}
	
	public static byte[] generateRandomByteArray(int iteration) {
		double min = iteration * 0.9;
		double max = iteration;
		SecureRandom random = new SecureRandom();

		int length = (int) ThreadLocalRandom.current().nextDouble(min, max);
		byte[] bytes = new byte[length];
		random.nextBytes(bytes);
		return bytes;
	}
}
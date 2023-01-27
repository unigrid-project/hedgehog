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

import org.unigrid.hedgehog.jqwik.BaseMockedWeldTest;
import net.jqwik.api.Example;
import net.jqwik.api.Property;
import net.jqwik.api.lifecycle.BeforeProperty;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.security.InvalidAlgorithmParameterException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Provide;
import net.jqwik.api.ForAll;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.lang3.RandomUtils;
import org.unigrid.hedgehog.model.Signature;

public class PassiveMapTest extends BaseMockedWeldTest {

	PassiveMap map;
	List<String> keys;
	
	@Example
	public void testPutAndGetInMap(@ForAll("key") String key, @ForAll("data") byte[] data) {
		PassiveMap map = new PassiveMap();
		ByteBuf inData = Unpooled.buffer();
		inData.setBytes(0, data);
		BlockData blockData = new BlockData();
		blockData.setAccessed(0);
		blockData.setBuffer(inData);
		map.put(key, blockData);

		ByteBuf buff = map.get(key);

		assert (buff.equals(blockData.getBuffer()));
	}

	@Example
	public void testMapExcideMemLimit() {
		PassiveMap map = new PassiveMap();

		for (int i = 0; i < 500; i++) {

			byte[] byteKey = RandomUtils.nextBytes(64);

			byte[] data = generateRandomByteArray(5 * 1024 * 1024);
			String stringKey = Hex.encodeHexString(byteKey);
			System.out.println(stringKey);
			ByteBuf buff = Unpooled.copiedBuffer(data);
			//buff.capacity(data.length);
			buff.setBytes(0, data);
			BlockData blockData = new BlockData();
			blockData.setBuffer(buff);
			map.put(stringKey, blockData);
			System.out.println("the size of the map is = " + map.size());

		}

		System.out.println(map.size());

		assert (map.size() < 500);

	}
	
	@Property
	public void testMultiplePutAndGet() {
		
		byte[] byteKey = RandomUtils.nextBytes(64);
		String key = Hex.encodeHexString(byteKey);
		keys.add(key);
		
		byte[] data = generateRandomByteArray(RandomUtils.nextInt(1, 1024 * 1024 * 256));
		ByteBuf buff = Unpooled.copiedBuffer(data);
		BlockData blockData = new BlockData();
		blockData.setBuffer(buff);

		map.put(key, blockData);
	}
	
	@BeforeProperty
	public void init() {
		map = new PassiveMap();
		keys = new ArrayList();
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

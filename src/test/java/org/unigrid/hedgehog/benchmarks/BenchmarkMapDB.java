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

//import com.oath.halodb.HaloDB;
//import com.oath.halodb.HaloDBException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import jnr.ffi.StructLayout;
import lombok.SneakyThrows;
import org.apache.commons.collections4.map.PassiveExpiringMap;
import org.mapdb.DB;
import org.mapdb.DBMaker;
import org.mapdb.HTreeMap;
import org.mapdb.Serializer;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;

@State(Scope.Benchmark)
@Fork(value = 1, warmups = 1)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class BenchmarkMapDB {
	
	@Benchmark
	public void mapDBLoop(BenchmarkData data, MapDBState db) {
		db.map = data.expieringMap;
		for (int i = 0; i < data.intirations; i++) {
			String key = "";
			byte[] value;
			try {
				MessageDigest digest = MessageDigest.getInstance("SHA-256");
				byte[] hash = digest.digest(Integer.toString(i * 32).getBytes(StandardCharsets.UTF_8));
				key = Base64.getEncoder().encodeToString(hash);
				value = generateRandomByteArray(data.chunk);;
			} catch (NoSuchAlgorithmException e) {
				throw new RuntimeException(e);
			}
			db.map.put(key, value);
		}
		/*Map<String, String> outputMap = db.hashMap("map", Serializer.JAVA, Serializer.JAVA).open();
		for (Map.Entry<String, String> entry : outputMap.entrySet()) {
			System.out.println("Object key = " + entry.getKey());
			System.out.println("Object val = " + entry.getValue());
			
		}*/
		db.db.close();
	}
	
	/*@Benchmark
	public void haloDBLoop(BenchmarkData data, HaloDBState db) {

		try {
			db.db = HaloDB.open(db.dir, db.options);			
		} catch (HaloDBException e) {
			System.out.println(e.getMessage());
			return;
		}
		for (int i = 0; i < data.intirations; i++) {
			try {
				MessageDigest digest = MessageDigest.getInstance("SHA-256");
				//int rand = (int)(Math.random() * 100);
				byte[] hash = digest.digest(Integer.toString(i * 32).getBytes(StandardCharsets.UTF_8));
				byte[] key = Base64.getEncoder().encodeToString(hash).getBytes();
				byte[] value = generateRandomByteArray(data.chunk);
				try {
					db.db.put(key, value);
				} catch (HaloDBException e) {
					System.out.println("Error on put!");
					System.out.println(e.getMessage());
				}
			} catch (NoSuchAlgorithmException e) {
				System.out.println(e.getMessage());
			}
			
		}
		try {
			db.db.close();			
		} catch (HaloDBException e) {
			System.out.println("error on database close!!!!");
			System.out.println(e.getMessage());
		}
	}*/
	
	@Benchmark
	public void lmdbLoop(BenchmarkData data, LmdbState db) {
		for (int i = 0; i < data.intirations; i++) {
			ByteBuffer key = ByteBuffer.allocateDirect(db.env.getMaxKeySize());
			ByteBuffer value = ByteBuffer.allocateDirect(700);
			try {
				MessageDigest digest = MessageDigest.getInstance("SHA-256");
				byte[] hash = digest.digest(Integer.toString(i * 32).getBytes(StandardCharsets.UTF_8));
				key.put(hash).flip();
				value.put(generateRandomByteArray(data.chunk)).flip();
			} catch (NoSuchAlgorithmException e) {
				throw new RuntimeException(e);
			}
			db.db.put(key, value);
		}
		/*Map<String, String> outputMap = db.hashMap("map", Serializer.JAVA, Serializer.JAVA).open();
		for (Map.Entry<String, String> entry : outputMap.entrySet()) {
			System.out.println("Object key = " + entry.getKey());
			System.out.println("Object val = " + entry.getValue());
			
		}*/
	}
	
	public static byte[] generateRandomByteArray(int iteration) {
		double min = iteration * 0.9;
		double max = iteration;
		SecureRandom random = new SecureRandom();
		
		int length = (int)ThreadLocalRandom.current().nextDouble(min, max);
		byte[] bytes = new byte[length];
		random.nextBytes(bytes);
		return bytes;
	}
}

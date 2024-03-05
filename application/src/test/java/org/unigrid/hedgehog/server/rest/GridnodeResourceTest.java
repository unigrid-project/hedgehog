/*
    Unigrid Hedgehog
    Copyright © 2021-2023 Stiftelsen The Unigrid Foundation, UGD Software AB

    Stiftelsen The Unigrid Foundation (org. nr: 802482-2408)
    UGD Software AB (org. nr: 559339-5824)

    This program is free software: you can redistribute it and/or modify it under the terms of the
    addended GNU Affero General Public License as published by the The Unigrid Foundation and
    the Free Software Foundation, version 3 of the License (see COPYING and COPYING.addendum).

    This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
    even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU Affero General Public License and the addendum for more details.

    You should have received an addended copy of the GNU Affero General Public License with this program.
    If not, see <http://www.gnu.org/licenses/> and <https://github.com/unigrid-project/hedgehog>.
 */

package org.unigrid.hedgehog.server.rest;

import com.fasterxml.jackson.core.JsonProcessingException;
import static org.hamcrest.MatcherAssert.*;
import static org.hamcrest.Matchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import lombok.SneakyThrows;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Provide;
import net.jqwik.api.Property;
import net.jqwik.api.Example;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.ForAll;
import net.jqwik.api.ShrinkingMode;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.StringLength;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.Sha256Hash;
import org.unigrid.hedgehog.client.ResponseOddityException;
import org.unigrid.hedgehog.model.network.ActivateGridnode;
import org.unigrid.hedgehog.model.network.Node;
import org.unigrid.hedgehog.jqwik.ArbitraryGenerator;
import org.unigrid.hedgehog.model.Collateral;
import org.unigrid.hedgehog.model.crypto.GridnodeKey;
import org.unigrid.hedgehog.model.crypto.Signature;
import org.unigrid.hedgehog.model.gridnode.Delegations;
import org.unigrid.hedgehog.model.gridnode.Gridnode;
import org.unigrid.hedgehog.model.gridnode.HeartbeatData;
import org.unigrid.hedgehog.model.network.Topology;
import org.unigrid.hedgehog.model.network.schedule.PublishGridnodeScheduleTest;

public class GridnodeResourceTest extends BaseRestClientTest{

	private List<ECKey> gridnodeKeys = new ArrayList<>();

	@Inject
	private Topology topology;

	@Provide
	public Arbitrary<String> provideAccount(@ForAll @AlphaChars @StringLength(36) String account) {
		return Arbitraries.of(account);
	}
	
	@Provide
	public Arbitrary<String> provideMessage() {
		return Arbitraries.of("");
	}
	
	@Provide
	public Arbitrary<String> providePrivateKey() {
		return Arbitraries.of("");
	}
	
	private enum Family {
		IP4, IP6
	}

	@Provide
	@SneakyThrows
	public Arbitrary<Gridnode> provideGridnode(@ForAll Family family,
		@ForAll @IntRange(min = 1024, max = 65535) int port) {

		String address = switch (family) {
			case IP4 -> ArbitraryGenerator.ip4();
			case IP6 -> ArbitraryGenerator.ip6();
		};

		Node node;

		if (port % 3 == 0) {
			node = Node.fromAddress((family == Family.IP4 ? "%s" : "[%s]").formatted(address));
		} else {
			node = Node.fromAddress((family == Family.IP4 ? "%s:%d" : "[%s]:%d").formatted(address, port));
		}
		ECKey key = new ECKey();
		Gridnode gridnode = Gridnode.builder().hostName(node.getAddress().getHostName())
			.id(key.getPublicKeyAsHex()).build();
		topology.addNode(node);
		topology.addGridnode(gridnode);
		return Arbitraries.of(gridnode);
	}

	@SneakyThrows
	public void provideActiveGridnode(@ForAll Family family,
		@ForAll @IntRange(min = 1024, max = 65535) int port, String key) {

		String address = switch (family) {
			case IP4 -> ArbitraryGenerator.ip4();
			case IP6 -> ArbitraryGenerator.ip6();
		};

		Node node;

		if (port % 3 == 0) {
			node = Node.fromAddress((family == Family.IP4 ? "%s" : "[%s]").formatted(address));
		} else {
			node = Node.fromAddress((family == Family.IP4 ? "%s:%d" : "[%s]:%d").formatted(address, port));
		}

		Gridnode gridnode = Gridnode.builder().hostName(node.getAddress().getHostName()).id(key)
			.status(Gridnode.Status.ACTIVE).build();
		topology.addNode(node);
		topology.addGridnode(gridnode);
	}

	@SneakyThrows
	public void provideInactiveGridnode(@ForAll Family family,
		@ForAll @IntRange(min = 1024, max = 65535) int port, String key) {

		String address = switch (family) {
			case IP4 -> ArbitraryGenerator.ip4();
			case IP6 -> ArbitraryGenerator.ip6();
		};

		Node node;

		if (port % 3 == 0) {
			node = Node.fromAddress((family == Family.IP4 ? "%s" : "[%s]").formatted(address));
		} else {
			node = Node.fromAddress((family == Family.IP4 ? "%s:%d" : "[%s]:%d").formatted(address, port));
		}

		Gridnode gridnode = Gridnode.builder().hostName(node.getAddress().getHostName()).id(key)
			.build();
		topology.addNode(node);
		topology.addGridnode(gridnode);
	}

	@SneakyThrows
	private List<Delegations> generateHeartbeatMap() {
		List<Delegations> list = new ArrayList<>();

		for(int i = 0; i < 10; i++) {

			Signature key = new Signature();
			String account = key.getPublicKey();
			int nodeAmount = new Random().nextInt(1, 1000);
			gridnodeKeys.addAll(GridnodeKey.generateKeys(account, 0));
			Double amount = new Random().nextDouble(2000, 2000 * 100);
			Delegations heartbeat = new Delegations();
			heartbeat.setAccount(account);
			heartbeat.setDelegatedAmount(amount.toString());
			list.add(heartbeat);
		}

		return list;
	}

	/*@Example
	public void shouldVerifyActiveNodes(@ForAll("provideNode") Node node) {
		List<Delegation> list = generateHeartbeatMap();

		double cost = new Collateral().get(0);

		for (Delegation heartbeat : list) {
			String account = heartbeat.getAccount();
			Double val = heartbeat.getDelegatedAmount();

			int amountOfNodes = (int) Math.round(val/cost);
			List<ECKey> keys = GridnodeKey.generateKeys(account, amountOfNodes);

			for (ECKey key : keys) {
				provideActiveGridnode(Family.IP4, new Random().nextInt(1024, 65535), key.getPublicKeyAsHex());
			}
		}
		
		String initNodes = topology.cloneNodes().toString();
		System.out.println(topology.cloneNodes().size());
		int status = 0;
		try {
			ObjectMapper mapper = new ObjectMapper();
			String json = mapper.writeValueAsString(list);
			System.out.println("json = " + json);
			final String url = "/gridnode/heartbeat/";
			final Response response = client.put(url, Entity.json(json));
			status = response.getStatus();
			
		} catch (ResponseOddityException ex) {
			System.out.println(ex.getMessage());
		} catch (JsonProcessingException ex) {
			System.out.println(ex.getMessage());
		}
		
		String nodes = topology.cloneNodes().toString();
		System.out.println(topology.cloneNodes().size());
		assertThat(initNodes, equalTo(nodes));
		assertThat(status, equalTo(200));
	}*/

	/*@Property(tries = 5)
	public void shouldRemoveIllegalNodes(@ForAll("provideSignature") Signature signature,
		@ForAll("provideGridnode") Gridnode node) {
		List<Delegation> list = generateHeartbeatMap();
		final String validatorUrl = "/gridspork/validator/";
		double cost = new Collateral().get(0);

		for (int i = 0; i < list.size(); i++) {
			String account = list.get(i).getAccount();
			Double val = list.get(i).getDelegatedAmount();

			int amountOfNodes = (int) Math.round(val/cost);
			
			System.out.println(amountOfNodes + 3);
			List<ECKey> keys = GridnodeKey.generateKeys(account, amountOfNodes + 3);

			for (ECKey key : keys) {
				provideActiveGridnode(Family.IP4, new Random().nextInt(1024, 65535), key.getPublicKeyAsHex());
				
			}
		}
		ECKey key = new ECKey();
		String pubKey = key.getPublicKeyAsHex();
		try {
			final Response putResponse = client.putWithHeaders(validatorUrl,
				Entity.text(pubKey), new MultivaluedHashMap(Map.of("privateKey",
					signature.getPrivateKey()))
			);
		} catch (ResponseOddityException ex) {
			Logger.getLogger(GridnodeResourceTest.class.getName()).log(Level.SEVERE, null, ex);
		}
		Set<Gridnode> initGridnodes = topology.cloneGridnode();
		String initNodes = initGridnodes.toString();
		int count = 0;
		for (Gridnode g : initGridnodes) {
			if(g.getStatus() == Gridnode.Status.ACTIVE) {
				count++;
			}
		}
		System.out.println(initGridnodes.size());
		System.out.println("number of active gridnodes = " + count);
		int status = 0;
		try {
			String message = "message";
			HeartbeatData heartbeatData = new HeartbeatData();
			heartbeatData.setMessage(message);
			heartbeatData.setDelagations(list);
			ECKey.ECDSASignature sign = key.sign(Sha256Hash.of(message.getBytes()));
			String derSign = Base64.getEncoder().encodeToString(sign.encodeToDER());
			heartbeatData.setSign(derSign);
			final String url = "/gridnode/heartbeat/";
			final Response response = client.put(url, Entity.json(heartbeatData));
			status = response.getStatus();
			System.out.println("response status = " + status);
		} catch (ResponseOddityException ex) {
			System.out.println(ex.getMessage());
		} /*catch (JsonProcessingException ex) {
			System.out.println(ex.getMessage());
		}*/
		/*Set<Gridnode> gridnodes = topology.cloneGridnode();
		String nodes = gridnodes.toString();
		System.out.println(gridnodes.size());
		count = 0;
		for (Gridnode g : gridnodes) {
			if(g.getStatus() == Gridnode.Status.ACTIVE) {
				count++;
			}
		}
		System.out.println("number of active gridnodes = " + count);
		assertThat(initNodes, not(nodes));
		assertThat(status, equalTo(200));
		
	}*/

	@Property(tries = 10, shrinking = ShrinkingMode.OFF)
	public void shouldReturnBaseCost() {
		Set<Gridnode> gridnodes = topology.cloneGridnode();
		int count = 0;
		for(Gridnode g : gridnodes){
			if(g.getStatus() == Gridnode.Status.ACTIVE) {
				count++;
			}
		}

		
		double result = 0;
		double expetedResult = new Collateral().get(count);
		try {
			final String url = "/gridnode/collateral/";
			final Response response = client.get(url);

			result = response.readEntity(Double.class);

		} catch (ResponseOddityException ex) {
			System.out.println(ex.getMessage());
		}
		System.out.println(expetedResult);
		System.out.println(result);
		assert result == expetedResult;
	}

	@Example
	public void shouldActivateGridnode(@ForAll("provideGridnode") Gridnode node) {
		try {
			//ECKey key = keys.get(0);
			String s = "start gridnode";
			ECKey key = new ECKey();
			String pubKey = key.getPublicKeyAsHex();
			String privKey = key.getPrivateKeyAsHex();
			List<ECKey> gridnodeKeys = GridnodeKey.generateKeys(pubKey, 1);
			String gridnodeKey = gridnodeKeys.get(0).getPublicKeyAsHex();
			System.out.println("1");
			Sha256Hash message = Sha256Hash.of(gridnodeKey.getBytes());
			ECKey.ECDSASignature sign = key.sign(message);
			ActivateGridnode gridnode = ActivateGridnode.builder().publicKey(pubKey)
				.gridnodeId(gridnodeKey).build();
			provideInactiveGridnode(Family.IP4, new Random().nextInt(1024, 65535), gridnodeKey);
			System.out.println("1");

			String signature = Base64.getEncoder().encodeToString(sign.encodeToDER());

			String initNodes = topology.cloneGridnode().toString();
			System.out.println("1");
			final String url = "/gridnode/start/";
			final Response response = client.putWithHeaders(url, Entity.json(gridnode),
				new MultivaluedHashMap(Map.of("sign", signature)));
			String nodes = topology.cloneGridnode().toString();

			int status = response.getStatus();

			assertThat(initNodes, not(nodes));
			assertThat(status, equalTo(202));
		} catch (ResponseOddityException ex) {
			System.out.println(ex.getMessage());
		}
	}
}

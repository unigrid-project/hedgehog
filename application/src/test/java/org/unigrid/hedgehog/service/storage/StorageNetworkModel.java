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

package org.unigrid.hedgehog.service.storage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.SneakyThrows;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import org.unigrid.hedgehog.model.gridnode.Gridnode;
import org.unigrid.hedgehog.model.spork.StorageSpork;
import org.unigrid.hedgehog.model.storage.crypto.Fingerprint;

public class StorageNetworkModel {
	static final StorageSpork.SporkData PARAMETERS = eagerRepair();

	private final StorageFleet fleet = new StorageFleet(PARAMETERS, PARAMETERS.window() + 8);
	private final StorageService service = fleet.service(new SecureRandom());
	private final Map<Fingerprint, byte[]> live = new LinkedHashMap<>();
	private final List<Fingerprint> deleted = new ArrayList<>();
	private int damage;

	/* Repair on the first missing fragment, so a heal restores full redundancy and the damage budget can reset.
	   Without extras, outer parity or spare manifest copies, the inner parity is the whole tolerance. */
	private static StorageSpork.SporkData eagerRepair() {
		final StorageSpork.SporkData parameters = StorageTestData.parameters();

		parameters.setRepairThresholdPercent(1);
		parameters.setMaxParityPercent(parameters.getInnerParityPercent());
		parameters.setOuterParityPercent(0);
		parameters.setManifestCopies(1);
		return parameters;
	}

	boolean canDamage() {
		return damage < PARAMETERS.layout().parityFragments();
	}

	boolean hasFiles() {
		return !live.isEmpty();
	}

	boolean hasOffline() {
		return fleet.online().size() < fleet.getGridnodes().size();
	}

	@SneakyThrows
	void store(byte[] file) {
		live.put(service.store(new ByteArrayInputStream(file)), file);
	}

	@SneakyThrows
	void delete(int pick) {
		final Fingerprint fingerprint = new ArrayList<>(live.keySet()).get(pick % live.size());

		service.delete(fingerprint);
		live.remove(fingerprint);
		deleted.add(fingerprint);
	}

	void kill(int pick) {
		final List<Gridnode> online = fleet.online();

		fleet.getTransport().offline(online.get(pick % online.size()).getId());
		damage++;
	}

	void wipe(int pick) {
		final List<Gridnode> online = fleet.online();

		fleet.wipe(online.get(pick % online.size()).getId());
		damage++;
	}

	void revive(int pick) {
		final List<Gridnode> offline = fleet.getGridnodes().stream()
			.filter(g -> !fleet.getTransport().isOnline(g.getId())).collect(Collectors.toList());

		fleet.getTransport().online(offline.get(pick % offline.size()).getId());
	}

	/* A join shifts every lower rank by one, pushing at most one fragment per group out of its window. */
	void join() {
		fleet.join();
		damage++;
	}

	/* Repair alone never restores what an offline gridnode holds, so the damage budget stays spent */
	void repair(int epochs) {
		fleet.runRepairEpochs(epochs);
	}

	void heal() {
		fleet.getGridnodes().forEach(g -> fleet.getTransport().online(g.getId()));
		fleet.runRepairEpochs(2 * PARAMETERS.window());
		damage = 0;

		deleted.forEach(fingerprint -> assertThrows(FingerprintNotFoundException.class,
			() -> service.open(fingerprint)));
		fleet.groups().forEach(groupId -> assertThat(fleet.distinctIndicesOf(groupId, PARAMETERS.window()),
			greaterThanOrEqualTo((long) PARAMETERS.layout().guaranteedFragments())));
	}

	@SneakyThrows
	void assertLiveFilesReadBack() {
		for (Map.Entry<Fingerprint, byte[]> file : live.entrySet()) {
			final ByteArrayOutputStream output = new ByteArrayOutputStream();

			service.retrieve(file.getKey(), output);
			assertThat(output.toByteArray(), equalTo(file.getValue()));
		}
	}

	@Override
	public String toString() {
		return "StorageNetworkModel[files=" + live.size() + ", deleted=" + deleted.size() + ", damage=" + damage
			+ "]";
	}
}

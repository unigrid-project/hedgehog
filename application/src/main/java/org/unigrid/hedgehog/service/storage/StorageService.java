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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.unigrid.hedgehog.model.gridnode.Gridnode;
import org.unigrid.hedgehog.model.spork.StorageSpork;
import org.unigrid.hedgehog.model.storage.crypto.Fingerprint;
import org.unigrid.hedgehog.model.storage.crypto.FingerprintKeys;
import org.unigrid.hedgehog.model.storage.placement.GridnodeDirectory;

/* Every operation waits on replies that arrive on the Netty event loops, so calling one from a loop deadlocks.
   A delete returns even when some gridnodes never acknowledged it: tombstones spread through repair and reach
   them later, so waiting for every holder would only let one silent gridnode block the caller. */
@RequiredArgsConstructor
public class StorageService {
	private final GridnodeDirectory directory;
	private final FragmentTransport transport;
	private final Supplier<Optional<StorageSpork.SporkData>> spork;
	private final SecureRandom random;
	private final Duration maxJitter;

	public Fingerprint store(final InputStream input) throws IOException, StorageException {
		final StorageSpork.SporkData parameters = parameters();
		final List<Gridnode> gridnodes = directory.active();
		final int required = parameters.layout().guaranteedFragments();

		if (gridnodes.size() < required) {
			throw new InsufficientGridnodesException(required, gridnodes.size());
		}

		final Fingerprint fingerprint = Fingerprint.generate(random);

		new StorageUpload(new FingerprintKeys(fingerprint), parameters, gridnodes, distributor(), random).run(input);
		return fingerprint;
	}

	public Retrieval open(final Fingerprint fingerprint) throws StorageException {
		return Retrieval.open(new FingerprintKeys(fingerprint), parameters(), directory.active(),
			new GroupFetcher(transport));
	}

	public void retrieve(final Fingerprint fingerprint, final OutputStream output)
		throws IOException, StorageException {

		open(fingerprint).writeTo(output);
	}

	public void delete(final Fingerprint fingerprint) throws StorageException {
		open(fingerprint).withdraw(distributor(), System.currentTimeMillis());
	}

	private StorageSpork.SporkData parameters() throws StorageDisabledException {
		return spork.get().orElseThrow(StorageDisabledException::new);
	}

	private GroupDistributor distributor() {
		return new GroupDistributor(transport, random, maxJitter);
	}
}

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

package org.unigrid.hedgehog.model.storage.crypto;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.Size;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import org.unigrid.hedgehog.model.storage.GroupId;

public class GroupKeyTest {
	@Property(tries = 50)
	public void isDeterministicPerSeed(@ForAll @Size(32) byte[] seed) {
		assertThat(new GroupKey(seed).groupId(), equalTo(new GroupKey(seed.clone()).groupId()));
	}

	@Property(tries = 50)
	public void groupIdIsTheHashOfThePublicKey(@ForAll @Size(32) byte[] seed) {
		final GroupKey key = new GroupKey(seed);

		assertThat(key.groupId(), equalTo(GroupId.of(Hashes.sha256(key.publicKey()))));
	}

	@Property(tries = 50)
	public void signaturesVerifyOnlyForTheSignedMessage(@ForAll @Size(32) byte[] seed,
		@ForAll @Size(min = 1, max = 64) byte[] message) {

		final GroupKey key = new GroupKey(seed);
		final byte[] signature = key.sign(message);
		final byte[] tampered = message.clone();
		tampered[0]++;

		assertThat(GroupKey.verify(key.publicKey(), message, signature), is(true));
		assertThat(GroupKey.verify(key.publicKey(), tampered, signature), is(false));
	}

	@Property(tries = 50)
	public void deleteSignaturesBindGroupAndTime(@ForAll @Size(32) byte[] seed, @ForAll long timestamp) {
		final GroupKey key = new GroupKey(seed);
		final byte[] signature = key.signDelete(timestamp);

		assertThat(GroupKey.verify(key.publicKey(), GroupKey.deleteMessage(key.groupId(), timestamp), signature),
			is(true)
		);
		assertThat(GroupKey.verify(key.publicKey(), GroupKey.deleteMessage(key.groupId(), timestamp + 1), signature),
			is(false)
		);
	}
}

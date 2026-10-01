/*
    Unigrid Hedgehog
    Copyright © 2021-2026 Stiftelsen The Unigrid Foundation

    Stiftelsen The Unigrid Foundation (org. nr: 802482-2408)

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

import java.util.Arrays;
import net.jqwik.api.Assume;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import org.unigrid.hedgehog.model.storage.crypto.Fingerprints.Secrets;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.Size;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import org.unigrid.hedgehog.model.storage.GroupId;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;

public class GroupKeyTest {
	@Property(tries = 50)
	public void isDeterministicPerSeed(@ForAll(supplier = Secrets.class) byte[] seed) {
		assertThat(new GroupKey(seed).groupId(), equalTo(new GroupKey(seed.clone()).groupId()));
	}

	@Property(tries = 50)
	public void groupIdIsTheHashOfThePublicKey(@ForAll(supplier = Secrets.class) byte[] seed) {
		final GroupKey key = new GroupKey(seed);

		assertThat(key.groupId(), equalTo(GroupId.of(Hashes.sha256(key.publicKey()))));
	}

	@Property(tries = 50)
	public void rejectsSeedsOfTheWrongLength(@ForAll @Size(max = 64) byte[] seed) {
		Assume.that(seed.length != GroupKey.SEED_SIZE);
		assertThrows(IllegalArgumentException.class, () -> new GroupKey(seed));
	}

	@Property(tries = 50)
	public void signaturesVerifyOnlyForTheSignedMessage(@ForAll(supplier = Secrets.class) byte[] seed,
		@ForAll @Size(min = 1, max = 64) byte[] message) {

		final GroupKey key = new GroupKey(seed);
		final byte[] signature = key.sign(message);
		final byte[] tampered = message.clone();
		tampered[0]++;

		assertThat(GroupKey.verify(key.publicKey(), message, signature), is(true));
		assertThat(GroupKey.verify(key.publicKey(), tampered, signature), is(false));
	}

	@Property(tries = 50)
	public void signaturesVerifyOnlyUnderTheSigningKey(@ForAll(supplier = Secrets.class) byte[] seed,
		@ForAll(supplier = Secrets.class) byte[] otherSeed, @ForAll @Size(max = 64) byte[] message) {

		Assume.that(!Arrays.equals(seed, otherSeed));

		final byte[] signature = new GroupKey(seed).sign(message);
		assertThat(GroupKey.verify(new GroupKey(otherSeed).publicKey(), message, signature), is(false));
	}

	@Property(tries = 1000)
	public void rejectsArbitraryKeysWithoutThrowing(@ForAll @Size(32) byte[] publicKey,
		@ForAll @Size(max = 64) byte[] message, @ForAll @Size(64) byte[] signature) {

		assertThat(GroupKey.verify(publicKey, message, signature), is(false));
	}

	@Property(tries = 100)
	public void rejectsPublicKeysOfTheWrongLengthWithoutThrowing(@ForAll @Size(max = 48) byte[] publicKey,
		@ForAll(supplier = Secrets.class) byte[] seed, @ForAll @Size(16) byte[] message) {

		Assume.that(publicKey.length != GroupKey.PUBLIC_KEY_SIZE);
		assertThat(GroupKey.verify(publicKey, message, new GroupKey(seed).sign(message)), is(false));
	}

	@Property(tries = 100)
	public void rejectsSignaturesOfTheWrongLengthWithoutThrowing(@ForAll(supplier = Secrets.class) byte[] seed,
		@ForAll @Size(max = 96) byte[] signature, @ForAll @Size(16) byte[] message) {

		Assume.that(signature.length != GroupKey.SIGNATURE_SIZE);
		assertThat(GroupKey.verify(new GroupKey(seed).publicKey(), message, signature), is(false));
	}

	@Property(tries = 50)
	public void deleteSignaturesBindGroupAndTime(@ForAll(supplier = Secrets.class) byte[] seed, @ForAll long timestamp) {
		final GroupKey key = new GroupKey(seed);
		final byte[] signature = key.signDelete(timestamp);

		assertThat(GroupKey.verify(key.publicKey(), GroupKey.deleteMessage(key.groupId(), timestamp), signature),
			is(true)
		);
		assertThat(GroupKey.verify(key.publicKey(), GroupKey.deleteMessage(key.groupId(), timestamp + 1),
			signature), is(false)
		);
	}

	@Property(tries = 50)
	public void deleteSignaturesDoNotCarryOverToAnotherGroup(@ForAll(supplier = Secrets.class) byte[] seed,
		@ForAll(supplier = Secrets.class) byte[] otherSeed, @ForAll long timestamp) {

		Assume.that(!Arrays.equals(seed, otherSeed));

		final GroupKey key = new GroupKey(seed);
		final GroupKey other = new GroupKey(otherSeed);
		final byte[] signature = key.signDelete(timestamp);

		assertThat(GroupKey.verify(key.publicKey(), GroupKey.deleteMessage(other.groupId(), timestamp), signature),
			is(false)
		);
		assertThat(GroupKey.verify(other.publicKey(), GroupKey.deleteMessage(other.groupId(), timestamp),
			signature), is(false)
		);
	}

	@Example
	public void derivesTheKnownGroupId() {
		final FingerprintKeys keys = new FingerprintKeys(Fingerprints.of(Fingerprints.knownSecret()));

		assertThat(new GroupKey(keys.chunkSeed(1, 2)).groupId().toHex(),
			equalTo("bab27d24f3677c2fefa6222c0cc5e5a224e0e6977e242808415ee3ff3083e266")
		);
	}
}

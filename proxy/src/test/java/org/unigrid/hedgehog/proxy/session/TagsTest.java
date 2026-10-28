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

package org.unigrid.hedgehog.proxy.session;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.Size;
import org.unigrid.hedgehog.proxy.Expect;
import org.unigrid.hedgehog.proxy.chain.Tag;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

public class TagsTest {
	private static final int LAST_COUNTER = 10_000;

	/* The documented tag, computed with the platform HMAC alone: the first 16 bytes of HMAC-SHA256 over the
	   direction code and the u64 counter */
	private static byte[] documentedTag(byte[] tagKey, Direction direction, long counter)
		throws GeneralSecurityException {
		final Mac mac = Mac.getInstance("HmacSHA256");

		mac.init(new SecretKeySpec(tagKey, "HmacSHA256"));
		return Arrays.copyOf(mac.doFinal(ByteBuffer.allocate(1 + Long.BYTES).put(direction.code()).putLong(counter)
			.array()), Tag.SIZE);
	}

	@Property(tries = 5)
	public void tagsDifferPerCounterAndDirection(@ForAll @Size(32) byte[] tagKey) {
		final Set<Tag> tags = new HashSet<>();

		for (long counter = 0; counter <= LAST_COUNTER; counter++) {
			for (Direction direction : Direction.values()) {
				assertThat(tags.add(Tags.of(tagKey, direction, counter)), is(true));
			}
		}
	}

	@Property(tries = 50)
	public void tagsAreTheDocumentedHmac(@ForAll @Size(32) byte[] tagKey, @ForAll long counter)
		throws GeneralSecurityException {
		for (Direction direction : Direction.values()) {
			assertThat(Tags.of(tagKey, direction, counter).bytes(),
				equalTo(documentedTag(tagKey, direction, counter)));
		}
	}

	@Property(tries = 50)
	public void anotherKeyGivesOtherTags(@ForAll @Size(32) byte[] tagKey, @ForAll long counter) {
		final byte[] other = tagKey.clone();

		other[0] ^= 1;
		assertThat(Tags.of(other, Direction.CLIENT_TO_EXIT, counter),
			not(equalTo(Tags.of(tagKey, Direction.CLIENT_TO_EXIT, counter))));
	}

	@Example
	public void aRandomTagFillsAllSixteenBytes() {
		final SecureRandom random = new SecureRandom();

		assertThat(Tag.random(random).bytes().length, is(Tag.SIZE));
		assertThat(Tag.random(random), not(equalTo(Tag.random(random))));
	}

	@Example
	public void aTagIsExactlySixteenBytes() {
		final IllegalArgumentException thrown = Expect.assertThrows(IllegalArgumentException.class,
			() -> new Tag(new byte[Tag.SIZE + 1]));

		assertThat(thrown.getMessage(), is("Tag is exactly 16 bytes, found 17"));
	}
}

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

package org.unigrid.hedgehog.s3.spi;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.unigrid.hedgehog.s3.jqwik.Expect.assertThrows;

public class MemoryBlobStoreTest {
	private final MemoryBlobStore store = new MemoryBlobStore();

	private BlobId store(final byte[] content) throws Exception {
		return store.store(new ByteArrayInputStream(content));
	}

	private static byte[] read(final Blob blob) throws Exception {
		final ByteArrayOutputStream output = new ByteArrayOutputStream();

		blob.writeTo(output);
		return output.toByteArray();
	}

	@Property
	public void readsBackWhatItStored(@ForAll final byte[] content) throws Exception {
		final Blob blob = store.open(store(content));

		assertThat(blob.size(), equalTo((long) content.length));
		assertThat(read(blob), equalTo(content));
	}

	@Property
	public void givesEveryStoredBlobItsOwnId(@ForAll final byte[] content) throws Exception {
		assertThat(store(content), not(equalTo(store(content))));
	}

	@Property
	public void forgetsADeletedBlob(@ForAll final byte[] content) throws Exception {
		final BlobId id = store(content);

		store.delete(id);
		assertThrows(BlobNotFoundException.class, () -> store.open(id));
		assertThrows(BlobNotFoundException.class, () -> store.delete(id));
	}
}

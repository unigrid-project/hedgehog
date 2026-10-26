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

package org.unigrid.hedgehog.s3.index;

import java.nio.file.FileSystem;
import java.time.Instant;
import java.util.List;
import net.jqwik.api.Example;
import org.unigrid.hedgehog.s3.spi.BlobId;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

public class GarbageTest {
	private static final String BUCKET = "bucket";
	private static final String KEY = "key";
	private static final BlobId BLOB = new BlobId("blob");

	private static void putAndDelete(final Namespace namespace, final Instant at) throws Exception {
		final Part part = Part.builder().number(1).slices(List.of(new BlobRef(BLOB, 0, 1))).build();

		namespace.put(ObjectVersion.builder().bucket(BUCKET).key(KEY).lastModified(at).parts(List.of(part)).build(),
			latest -> true);
		namespace.delete(BUCKET, KEY, at, latest -> true);
	}

	/* A blob picked as due can be referred to and released again before it is claimed, which starts a new grace
	   period that the claim has to honour */
	@Example
	public void claimsOnlyWhatIsStillDueWhenClaimed() throws Exception {
		final MutableClock clock = new MutableClock();
		final NamespaceConfig config = TreeTest.CONFIG.toBuilder().clock(clock).build();
		final FlakyBlobStore blobs = new FlakyBlobStore();

		try (FileSystem files = PosixFiles.create();
			Namespace namespace = Namespace.open(files.getPath("/namespace"), blobs, config)) {

			namespace.createBucket(Bucket.builder().name(BUCKET).created(Instant.EPOCH).build());
			putAndDelete(namespace, clock.instant());
			clock.advance(config.getGrace().plusSeconds(1));

			final Instant now = clock.instant();
			final List<BlobId> due = namespace.dueGarbage(now);

			assertThat(due, equalTo(List.of(BLOB)));
			putAndDelete(namespace, now);
			assertThat(namespace.claim(due, now), empty());
			namespace.maintain();
			assertThat(blobs.deletions(), not(hasItem(BLOB)));
			clock.advance(config.getGrace().plusSeconds(1));
			namespace.maintain();
			assertThat(blobs.deletions(), hasItem(BLOB));
		}
	}
}

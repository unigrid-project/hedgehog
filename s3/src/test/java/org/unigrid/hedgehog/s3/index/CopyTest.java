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
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.function.Function;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeTry;
import org.unigrid.hedgehog.s3.spi.BlobId;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.unigrid.hedgehog.s3.jqwik.Expect.assertThrows;

/* A copy refers to the blobs of its source, so the source has to be read in the same mutation that stores the copy:
   read any earlier, its blobs may be released and deleted before the copy refers to them */
public class CopyTest {
	private static final String BUCKET = "bucket";
	private static final String SOURCE = "source";
	private static final String TARGET = "target";
	private static final BlobId BLOB = new BlobId("blob");
	private static final BlobId OTHER = new BlobId("other");

	private final MutableClock clock = new MutableClock();
	private final NamespaceConfig config = TreeTest.CONFIG.toBuilder().clock(clock).build();
	private final FlakyBlobStore blobs = new FlakyBlobStore();
	private FileSystem files;
	private Namespace namespace;

	@BeforeTry
	void open() throws Exception {
		files = PosixFiles.create();
		namespace = Namespace.open(files.getPath("/namespace"), blobs, config);
		namespace.createBucket(Bucket.builder().name(BUCKET).created(Instant.EPOCH).build());
	}

	@AfterTry
	void close() throws Exception {
		namespace.close();
		files.close();
	}

	private static Part part(final int number, final BlobRef... slices) {
		return Part.builder().number(number).slices(List.of(slices)).build();
	}

	private ObjectVersion put(final String key, final Part... parts) throws Exception {
		return namespace.put(ObjectVersion.builder().bucket(BUCKET).key(key).lastModified(clock.instant())
			.parts(List.of(parts)).build(), latest -> true);
	}

	private void sweepAfterGrace() throws Exception {
		clock.advance(config.getGrace().plusSeconds(1));
		namespace.maintain();
	}

	private static Function<Optional<ObjectVersion>, ObjectVersion> to(final String key) {
		return found -> found.orElseThrow().toBuilder().key(key).versionId(null).build();
	}

	@Example
	public void refusesToCopyASourceDeletedAndSweptSinceItWasRead() throws Exception {
		final ObjectVersion read = put(SOURCE, part(1, new BlobRef(BLOB, 0, 1)));

		namespace.delete(BUCKET, SOURCE, clock.instant(), latest -> true);
		sweepAfterGrace();

		assertThat(blobs.deletions(), hasItem(BLOB));
		assertThrows(NoSuchElementException.class, () -> namespace.copy(new VersionRef(BUCKET, SOURCE,
			read.versionId()), to(TARGET), latest -> true));
		assertThat(namespace.latest(BUCKET, TARGET), equalTo(Optional.empty()));
	}

	@Example
	public void keepsTheBlobsOfACopyAfterItsSourceIsGone() throws Exception {
		put(SOURCE, part(1, new BlobRef(BLOB, 0, 1)));

		final ObjectVersion copy = namespace.copy(new VersionRef(BUCKET, SOURCE, null), to(TARGET), latest -> true);

		namespace.delete(BUCKET, SOURCE, clock.instant(), latest -> true);
		sweepAfterGrace();
		assertThat(blobs.deletions(), not(hasItem(BLOB)));
		assertThat(namespace.latest(BUCKET, TARGET), equalTo(Optional.of(copy)));

		namespace.delete(BUCKET, TARGET, clock.instant(), latest -> true);
		sweepAfterGrace();
		assertThat(blobs.deletions(), hasItem(BLOB));
	}

	@Example
	public void refusesToCopyAPartFromASourceDeletedAndSweptSinceItWasRead() throws Exception {
		final Upload upload = namespace.createUpload(Upload.builder().template(ObjectVersion.builder().bucket(BUCKET)
			.key(TARGET).lastModified(clock.instant()).build()).build());
		final ObjectVersion read = put(SOURCE, part(1, new BlobRef(BLOB, 0, 1)));
		final Function<Optional<ObjectVersion>, Part> copied = found -> found.orElseThrow().parts().getFirst();

		namespace.delete(BUCKET, SOURCE, clock.instant(), latest -> true);
		sweepAfterGrace();

		assertThrows(NoSuchElementException.class, () -> namespace.copyPart(BUCKET, TARGET, upload.uploadId(),
			new VersionRef(BUCKET, SOURCE, read.versionId()), copied));
		assertThat(namespace.parts(BUCKET, TARGET, upload.uploadId(), 0, 1).entries(), equalTo(List.of()));

		final ObjectVersion again = put(SOURCE, part(1, new BlobRef(OTHER, 0, 1)));

		assertThat(namespace.copyPart(BUCKET, TARGET, upload.uploadId(), new VersionRef(BUCKET, SOURCE, null),
			copied), equalTo(again.parts().getFirst()));
		namespace.delete(BUCKET, SOURCE, clock.instant(), latest -> true);
		sweepAfterGrace();
		assertThat(blobs.deletions(), not(hasItem(OTHER)));
	}

	@Example
	public void refusesToCopyFromABucketThatIsNotThere() throws Exception {
		assertThrows(NoSuchBucketException.class, () -> namespace.copy(new VersionRef("nowhere", SOURCE, null),
			to(TARGET), latest -> true));
	}

	/* A version refers to a blob once, however many slices of it its parts hold */
	@Example
	public void countsABlobOnceHoweverManySlicesOfItAVersionHolds() throws Exception {
		put(SOURCE, part(1, new BlobRef(BLOB, 0, 1), new BlobRef(OTHER, 0, 2), new BlobRef(BLOB, 1, 1)),
			part(2, new BlobRef(BLOB, 2, 1)));
		put(TARGET, part(1, new BlobRef(BLOB, 1, 2)));

		namespace.delete(BUCKET, SOURCE, clock.instant(), latest -> true);
		sweepAfterGrace();
		assertThat(blobs.deletions(), hasItem(OTHER));
		assertThat(blobs.deletions(), not(hasItem(BLOB)));

		namespace.delete(BUCKET, TARGET, clock.instant(), latest -> true);
		sweepAfterGrace();
		assertThat(blobs.deletions(), hasItem(BLOB));
	}
}

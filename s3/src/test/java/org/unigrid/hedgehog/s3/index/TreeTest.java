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

import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeTry;
import org.unigrid.hedgehog.s3.spi.BlobId;
import org.unigrid.hedgehog.s3.spi.BlobStoreException;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.unigrid.hedgehog.s3.jqwik.Expect.assertThrows;

public class TreeTest {
	static final NamespaceConfig CONFIG = NamespaceConfig.builder().pageBytes(48).cacheBytes(1024)
		.maintenanceInterval(Duration.ZERO).build();

	private sealed interface Step permits Write, Flush, Reopen {
	}

	private record Write(Batch batch) implements Step {
	}

	private record Flush() implements Step {
	}

	private record Reopen() implements Step {
	}

	private FileSystem files;
	private Path directory;
	private FlakyBlobStore blobs;
	private NavigableMap<byte[], Slot> model;

	@BeforeTry
	void prepare() {
		files = PosixFiles.create();
		directory = files.getPath("/namespace");
		blobs = new FlakyBlobStore();
		model = new TreeMap<>(Arrays::compareUnsigned);
	}

	@AfterTry
	void closeFiles() throws Exception {
		files.close();
	}

	@Provide
	Arbitrary<List<Step>> steps() {
		return Arbitraries.<Step>frequencyOf(Tuple.of(8, IndexArbitraries.batches().map(Write::new)),
			Tuple.of(1, Arbitraries.just(new Flush())), Tuple.of(1, Arbitraries.just(new Reopen()))
		).list().ofMaxSize(30);
	}

	/* Ends on a write that keeps a value, so a flush of them always has a run to store */
	@Provide
	Arbitrary<List<Batch>> batches() {
		return IndexArbitraries.batches().list().ofMinSize(1).ofMaxSize(10).map(batches -> {
			final Batch put = new Batch();

			put.put(new byte[] {Keys.BUCKET}, new byte[] {1});
			return Stream.concat(batches.stream(), Stream.of(put)).toList();
		});
	}

	private Tree open() throws Exception {
		return Tree.open(directory, blobs, CONFIG, null);
	}

	private void write(final Tree tree, final Batch batch) throws Exception {
		tree.write(transaction -> {
			for (Slot slot : batch.slots()) {
				if (slot.tombstone()) {
					transaction.delete(slot.key());
				} else {
					transaction.put(slot.key(), slot.value());
				}
			}

			return null;
		});

		batch.slots().forEach(slot -> model.put(slot.key(), slot));
	}

	private void write(final Tree tree, final List<Batch> batches) throws Exception {
		for (Batch batch : batches) {
			write(tree, batch);
		}
	}

	private static String hex(final Optional<byte[]> value) {
		return value.map(HexFormat.of()::formatHex).orElse("absent");
	}

	private void assertHolds(final Tree tree) throws Exception {
		final Snapshot snapshot = tree.snapshot();

		assertThat(RunTest.drain(snapshot.scan(new byte[0], null)),
			equalTo(model.values().stream().filter(slot -> !slot.tombstone()).toList()));

		for (Slot slot : model.values()) {
			assertThat(hex(snapshot.get(slot.key())), equalTo(hex(Optional.ofNullable(slot.value()))));
		}
	}

	@Property(tries = 200)
	public void keepsEveryWriteAcrossFlushesAndRestarts(@ForAll("steps") final List<Step> steps) throws Exception {
		Tree tree = open();

		for (Step step : steps) {
			switch (step) {
				case Write write -> write(tree, write.batch());
				case Flush flush -> tree.flush();
				case Reopen reopen -> {
					tree.close();
					tree = open();
				}
			}

			assertHolds(tree);
		}

		tree.close();
	}

	@Property(tries = 50)
	public void mountsTheFlushedNamespaceOnAFreshNode(@ForAll("batches") final List<Batch> batches)
		throws Exception {

		final BlobId root;

		try (Tree tree = open()) {
			write(tree, batches);
			tree.flush();
			root = tree.root().orElseThrow();
		}

		try (Tree mounted = Tree.open(files.getPath("/elsewhere"), blobs, CONFIG, root)) {
			assertHolds(mounted);
		}
	}

	@Property(tries = 50)
	public void recoversFromAFlushThatDiesBeforeTheRootMoves(@ForAll("batches") final List<Batch> flushed,
		@ForAll("batches") final List<Batch> unflushed, @ForAll @IntRange(max = 1) final int storesBeforeFailure)
		throws Exception {

		final BlobId root;

		try (Tree tree = open()) {
			write(tree, flushed);
			tree.flush();
			root = tree.root().orElseThrow();
			write(tree, unflushed);
			blobs.failStoreAfter(storesBeforeFailure);
			assertThrows(BlobStoreException.class, tree::flush);
			assertHolds(tree);
		}

		blobs.heal();
		assertThat(Files.readString(directory.resolve(Tree.ROOT)), equalTo(root.value()));

		try (Tree tree = open()) {
			assertHolds(tree);
			tree.flush();
		}

		try (Tree tree = open()) {
			assertHolds(tree);
		}
	}

	@Property(tries = 50)
	public void replaysAJournalThatOutlivedItsFlush(@ForAll("batches") final List<Batch> batches) throws Exception {
		final byte[] journal;

		try (Tree tree = open()) {
			write(tree, batches);
			journal = Files.readAllBytes(directory.resolve(Journal.ACTIVE));
			tree.flush();
		}

		Files.write(directory.resolve(Journal.ACTIVE), journal);

		try (Tree tree = open()) {
			assertHolds(tree);
			tree.flush();
		}

		try (Tree tree = open()) {
			assertHolds(tree);
		}
	}

	@Example
	public void letsOnlyItsOwnerReadTheRoot() throws Exception {
		try (Tree tree = open()) {
			write(tree, IndexArbitraries.batches().sample());
			tree.flush();
		}

		assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(directory.resolve(Tree.ROOT))),
			equalTo("rw-------"));
	}

	@Example
	public void refusesToMountOverWritesNotYetFlushed() throws Exception {
		try (Tree tree = open()) {
			write(tree, IndexArbitraries.batches().sample());
		}

		assertThrows(IllegalStateException.class, () -> Tree.open(directory, blobs, CONFIG,
			new BlobId("elsewhere")));

		assertThat(Files.exists(directory.resolve(Tree.ROOT)), equalTo(false));
		assertThat(new String(Files.readAllBytes(directory.resolve(Journal.ACTIVE)), StandardCharsets.ISO_8859_1)
			.isEmpty(), equalTo(false));
	}
}

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

package org.unigrid.hedgehog.proxy.chain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.ShrinkingMode;
import net.jqwik.api.constraints.Size;
import org.unigrid.hedgehog.proxy.Expect;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

public class FeedWriterTest {
	private static final long NOW = 1_750_000_000_000L;
	private static final ChainId CHAIN = ChainFixtures.chainId(1);
	private static final int THREADS = 8;
	private static final int WRITES_PER_THREAD = 50;

	private static List<Entry> writeFromThread(FeedWriter writer, CyclicBarrier start, long now) throws Exception {
		final List<Entry> entries = new ArrayList<>();

		start.await();

		for (int i = 0; i < WRITES_PER_THREAD; i++) {
			entries.add(writer.write(now + i, Fingerprint.NONE, EntryKind.SEALED, new byte[0]));
		}

		return entries;
	}

	@Provide
	public Arbitrary<List<byte[]>> payloadLists() {
		return ChainFixtures.payloads().list().ofMaxSize(20);
	}

	@Provide
	public Arbitrary<List<Long>> clocks() {
		return Arbitraries.longs().between(-5, 5).list().ofMinSize(1).ofMaxSize(20);
	}

	@Provide
	public Arbitrary<List<Long>> clockSkews() {
		return Arbitraries.longs().between(0, 10_000).list().ofSize(THREADS);
	}

	@Provide
	public Arbitrary<Fingerprint> fingerprints() {
		return ChainFixtures.bytes(Fingerprint.SIZE).map(Fingerprint::new).filter(
			fingerprint -> !fingerprint.equals(Fingerprint.NONE));
	}

	@Property(tries = 30)
	public void shouldWriteAFeedThatLinks(@ForAll("payloadLists") List<byte[]> payloads,
		@ForAll @Size(WriterKey.SEED_SIZE) byte[] seed) {
		final WriterKey key = new WriterKey(seed);
		final FeedWriter writer = new FeedWriter(key, CHAIN);
		final Feed feed = new Feed(key.id());

		for (int i = 0; i < payloads.size(); i++) {
			final Entry entry = writer.write(NOW + i, Fingerprint.NONE, EntryKind.SEALED, payloads.get(i));

			assertThat(entry.isAuthentic(), is(true));
			assertThat(entry.header().seq(), is((long) i));
			assertThat(entry.header().chainId(), equalTo(CHAIN));
			assertThat(entry.payload(), equalTo(payloads.get(i)));
			assertThat(feed.append(entry), is(Link.NEXT));
		}
	}

	@Example
	public void shouldStartWithSequenceZeroAndNoPrevious() {
		final Entry first = new FeedWriter(ChainFixtures.key(1), CHAIN).write(NOW, Fingerprint.NONE, EntryKind.JOIN,
			new byte[0]);

		assertThat(first.header().seq(), is(0L));
		assertThat(first.header().prev(), is(0L));
	}

	@Example
	public void shouldHoldTheTimeWhenTheClockGoesBack() {
		final WriterKey key = ChainFixtures.key(1);
		final FeedWriter writer = new FeedWriter(key, CHAIN);
		final Feed feed = new Feed(key.id());
		final Entry first = writer.write(NOW, Fingerprint.NONE, EntryKind.JOIN, new byte[0]);
		final Entry second = writer.write(NOW - 5000, Fingerprint.NONE, EntryKind.SEALED, new byte[] { 1 });

		assertThat(second.header().timestamp(), is(NOW));
		assertThat(feed.append(first), is(Link.NEXT));
		assertThat(feed.append(second), is(Link.NEXT));
	}

	@Property(tries = 50)
	public void shouldWriteTheRunningMaximumOfTheClock(@ForAll("clocks") List<Long> clocks) {
		final WriterKey key = ChainFixtures.key(1);
		final FeedWriter writer = new FeedWriter(key, CHAIN);
		final Feed feed = new Feed(key.id());
		long latest = Long.MIN_VALUE;

		for (final long now : clocks) {
			latest = Math.max(latest, now);

			final Entry entry = writer.write(now, Fingerprint.NONE, EntryKind.SEALED, new byte[0]);

			assertThat(entry.header().timestamp(), is(latest));
			assertThat(feed.append(entry), is(Link.NEXT));
		}
	}

	@Property(tries = 30)
	public void shouldPassTheKindAndFingerprintThrough(@ForAll EntryKind kind,
		@ForAll("fingerprints") Fingerprint fingerprint) {
		final Entry entry = new FeedWriter(ChainFixtures.key(1), CHAIN).write(NOW, fingerprint, kind, new byte[0]);

		assertThat(entry.header().kind(), is(kind));
		assertThat(entry.header().fingerprint(), equalTo(fingerprint));
		assertThat(entry.isAuthentic(), is(true));
	}

	@Property(tries = 20, shrinking = ShrinkingMode.OFF)
	public void shouldKeepTheFeedLinkedWhenThreadsShareAWriter(@ForAll("clockSkews") List<Long> skews)
		throws Exception {
		final FeedWriter writer = new FeedWriter(ChainFixtures.key(1), CHAIN);
		final CyclicBarrier start = new CyclicBarrier(skews.size());
		final ExecutorService pool = Executors.newFixedThreadPool(skews.size());
		final List<Entry> entries = new ArrayList<>();

		try {
			final List<Future<List<Entry>>> written = new ArrayList<>();

			for (final long skew : skews) {
				written.add(pool.submit(() -> writeFromThread(writer, start, NOW + skew)));
			}

			for (final Future<List<Entry>> future : written) {
				entries.addAll(future.get());
			}
		} finally {
			pool.shutdownNow();
		}

		entries.sort(Comparator.comparingLong(entry -> entry.header().seq()));

		final Feed feed = new Feed(writer.writer());
		long prev = 0;

		for (int i = 0; i < entries.size(); i++) {
			assertThat(entries.get(i).header().seq(), is((long) i));
			assertThat(entries.get(i).header().prev(), is(prev));
			assertThat(feed.append(entries.get(i)), is(Link.NEXT));
			prev = entries.get(i).hash();
		}

		assertThat(entries.size(), is(skews.size() * WRITES_PER_THREAD));
	}

	@Example
	public void shouldRefuseToSignTheLastSequenceNumber() {
		final WriterKey key = ChainFixtures.key(1);
		final Head after = new Head(Long.MAX_VALUE - 2, 7);
		final FeedWriter writer = new FeedWriter(key, CHAIN, after);
		final Entry last = writer.write(NOW, Fingerprint.NONE, EntryKind.SEALED, new byte[0]);

		assertThat(last.header().seq(), is(Long.MAX_VALUE - 1));
		assertThat(last.header().prev(), is(7L));
		assertThat(new Feed(key.id(), after).append(last), is(Link.NEXT));
		Expect.assertThrows(IllegalStateException.class, () -> writer.write(NOW, Fingerprint.NONE, EntryKind.SEALED,
			new byte[0]));
		Expect.assertThrows(IllegalStateException.class, () -> writer.write(NOW, Fingerprint.NONE, EntryKind.SEALED,
			new byte[0]));
	}

	@Example
	public void shouldNotStartAfterTheLastSequenceNumber() {
		Expect.assertThrows(IllegalArgumentException.class, () -> new FeedWriter(ChainFixtures.key(1), CHAIN,
			new Head(Long.MAX_VALUE, 1)));
	}

	@Example
	public void shouldLeaveTheWriterAsItWasWhenAWriteFails() {
		final FeedWriter writer = new FeedWriter(ChainFixtures.key(1), CHAIN);

		Expect.assertThrows(NullPointerException.class, () -> writer.write(NOW + 100, Fingerprint.NONE, null,
			new byte[0]));
		Expect.assertThrows(NullPointerException.class, () -> writer.write(NOW + 100, null, EntryKind.JOIN,
			new byte[0]));
		Expect.assertThrows(NullPointerException.class, () -> writer.write(NOW + 100, Fingerprint.NONE, EntryKind.JOIN,
			null));

		final Entry first = writer.write(NOW, Fingerprint.NONE, EntryKind.JOIN, new byte[0]);

		assertThat(first.header().seq(), is(0L));
		assertThat(first.header().prev(), is(0L));
		assertThat(first.header().timestamp(), is(NOW));
	}

	@Example
	public void shouldRefuseMissingConstructorArguments() {
		final NullPointerException noKey = Expect.assertThrows(NullPointerException.class,
			() -> new FeedWriter(null, CHAIN));
		final NullPointerException noChain = Expect.assertThrows(NullPointerException.class,
			() -> new FeedWriter(ChainFixtures.key(1), null));

		assertThat(noKey.getMessage(), is("key"));
		assertThat(noChain.getMessage(), is("chainId"));
	}
}

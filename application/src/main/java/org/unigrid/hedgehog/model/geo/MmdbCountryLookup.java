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

package org.unigrid.hedgehog.model.geo;

import com.maxmind.db.Reader;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;

/* A country database in the MaxMind DB format, as GeoIP2 and GeoLite2 Country are, read into memory whole. A record is
   decoded into maps and strings, and only the code of the country the network is in counts, not the one it is
   registered in. */
@Slf4j
public final class MmdbCountryLookup implements CountryLookup {
	/* Many times the few megabytes a country database takes, and still a bound on what reading one may hold */
	public static final long MAX_BYTES = 128L * 1024 * 1024;

	private final Reader reader;

	/* A database larger than the bound, or one that runs past it, as a device that never ends would */
	public static final class TooLargeException extends IOException {
		private final long size;

		TooLargeException(long size) {
			super("A country database holds no more than " + MAX_BYTES + " bytes");
			this.size = size;
		}

		/* The size of the file, or what was read of a stream when it ran past the bound */
		public long size() {
			return size;
		}
	}

	/* Counts what it hands on and fails as soon as that runs past the bound, having asked its source for one byte
	   past it at the most */
	private static final class Bounded extends InputStream {
		private final InputStream source;
		private final long maxBytes;
		private long read;

		private Bounded(InputStream source, long maxBytes) {
			this.source = source;
			this.maxBytes = maxBytes;
		}

		@Override
		public int read() throws IOException {
			final int next = source.read();

			if (next >= 0) {
				counted(1);
			}

			return next;
		}

		@Override
		public int read(byte[] bytes, int offset, int length) throws IOException {
			final int count = source.read(bytes, offset, (int) Math.min(length, maxBytes - read + 1));

			if (count > 0) {
				counted(count);
			}

			return count;
		}

		private void counted(int count) throws TooLargeException {
			read += count;

			if (read > maxBytes) {
				throw new TooLargeException(read);
			}
		}
	}

	public MmdbCountryLookup(InputStream database) throws IOException {
		this(database, MAX_BYTES);
	}

	MmdbCountryLookup(InputStream database, long maxBytes) throws IOException {
		reader = new Reader(new Bounded(database, maxBytes));
	}

	/* A file larger than the bound is refused by its size, before any of it is read */
	public static MmdbCountryLookup read(Path database) throws IOException {
		final long size = Files.size(database);

		if (size > MAX_BYTES) {
			throw new TooLargeException(size);
		}

		try (InputStream in = Files.newInputStream(database)) {
			return new MmdbCountryLookup(in);
		}
	}

	/* A record the database cannot decode is no country, nor is an IPv6 address in a database of IPv4 alone. Only the
	   class of the failure is logged, as its message may name the address. */
	@Override
	public Optional<String> countryOf(InetAddress address) {
		try {
			return Optional.ofNullable(reader.get(address, Map.class)).map(record -> record.get("country"))
				.filter(Map.class::isInstance).map(country -> ((Map<?, ?>) country).get("iso_code"))
				.filter(String.class::isInstance).map(String.class::cast);
		} catch (IOException | RuntimeException ex) {
			log.warn("Looking up a country failed with {}", ex.getClass().getName());
			return Optional.empty();
		}
	}
}

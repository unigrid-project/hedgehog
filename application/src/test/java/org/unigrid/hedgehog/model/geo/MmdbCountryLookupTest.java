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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.IntStream;
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import org.slf4j.LoggerFactory;

public class MmdbCountryLookupTest {
	/* The fake country database of the MaxMind DB repository, whose networks are listed next to it there */
	public static final String FIXTURE = "/geoip/GeoIP2-Country-Test.mmdb";

	/* Where the data section of the fixture starts: after its search tree of 1420 nodes of two 28-bit records each,
	   and the 16 zero bytes that follow it */
	private static final int TREE_BYTES = 1420 * 7;
	private static final int SEPARATOR_BYTES = 16;

	/* What ends the data section and starts the metadata */
	private static final byte[] METADATA = { (byte) 0xAB, (byte) 0xCD, (byte) 0xEF, 'M', 'a', 'x', 'M', 'i', 'n',
		'd', '.', 'c', 'o', 'm' };

	@SneakyThrows
	public static CountryLookup fixture() {
		try (InputStream database = MmdbCountryLookupTest.class.getResourceAsStream(FIXTURE)) {
			return new MmdbCountryLookup(database);
		}
	}

	/* The fixture with every byte of its data section set, so that each record a lookup reaches fails to decode */
	@SneakyThrows
	private static CountryLookup damaged() {
		final byte[] database;

		try (InputStream fixture = MmdbCountryLookupTest.class.getResourceAsStream(FIXTURE)) {
			database = fixture.readAllBytes();
		}

		final int metadata = IntStream.iterate(database.length - METADATA.length, at -> at >= 0, at -> at - 1)
			.filter(at -> Arrays.equals(database, at, at + METADATA.length, METADATA, 0, METADATA.length))
			.findFirst().orElseThrow();

		assertThat(Arrays.copyOfRange(database, TREE_BYTES, TREE_BYTES + SEPARATOR_BYTES),
			equalTo(new byte[SEPARATOR_BYTES]));
		Arrays.fill(database, TREE_BYTES + SEPARATOR_BYTES, metadata, (byte) 0xFF);
		return new MmdbCountryLookup(new ByteArrayInputStream(database));
	}

	/* The module descriptor reads logback-classic only, so the in-memory appender of logback-core is out of reach */
	private static List<ILoggingEvent> loggedWhile(final Runnable action) {
		MmdbCountryLookupTest.class.getModule()
			.addReads(ModuleLayer.boot().findModule("ch.qos.logback.core").orElseThrow());

		final Logger logger = (Logger) LoggerFactory.getLogger(MmdbCountryLookup.class);
		final ListAppender<ILoggingEvent> appender = new ListAppender<>();

		appender.start();
		logger.addAppender(appender);

		try {
			action.run();
		} finally {
			logger.detachAppender(appender);
		}

		return List.copyOf(appender.list);
	}

	@Example
	public void readsTheIsoCode() {
		final CountryLookup lookup = fixture();

		/* The country the network is in, not the one it is registered in, which is DE */
		assertThat(lookup.countryOf(InetAddress.ofLiteral("89.160.20.112")), equalTo(Optional.of("SE")));
		assertThat(lookup.countryOf(InetAddress.ofLiteral("2001:218::1")), equalTo(Optional.of("JP")));

		/* A network the database knows only traits of, and an address it does not hold */
		assertThat(lookup.countryOf(InetAddress.ofLiteral("214.1.1.1")), equalTo(Optional.empty()));
		assertThat(lookup.countryOf(InetAddress.ofLiteral("192.0.2.1")), equalTo(Optional.empty()));
	}

	@Example
	public void readsTheCountryOfTheAddressAHostNameNames() {
		final CountryLookup lookup = fixture();

		assertThat(lookup.countryOfHost("89.160.20.112:52883"), equalTo(Optional.of("SE")));
		assertThat(lookup.countryOfHost("[2001:218::1]:52883"), equalTo(Optional.of("JP")));
		assertThat(lookup.countryOfHost("81.2.69.160"), equalTo(Optional.of("GB")));
	}

	/* A name is never resolved, so the lookup is not even asked */
	@Example
	public void hostNamesHaveNoCountry() {
		final List<InetAddress> asked = new CopyOnWriteArrayList<>();
		final CountryLookup spy = address -> {
			asked.add(address);
			return Optional.of("SE");
		};

		assertThat(spy.countryOfHost("gn.example.org:52883"), equalTo(Optional.empty()));
		assertThat(spy.countryOfHost("localhost:52883"), equalTo(Optional.empty()));
		assertThat(spy.countryOfHost("[gn.example.org]:52883"), equalTo(Optional.empty()));
		assertThat(spy.countryOfHost(""), equalTo(Optional.empty()));
		assertThat(spy.countryOfHost(null), equalTo(Optional.empty()));
		assertThat(asked, empty());
	}

	@Example
	public void noneKnowsNoCountry() {
		assertThat(CountryLookup.NONE.countryOf(InetAddress.ofLiteral("89.160.20.112")), equalTo(Optional.empty()));
		assertThat(CountryLookup.NONE.countryOfHost("89.160.20.112:52883"), equalTo(Optional.empty()));
	}

	/* A stream that never ends, as a device can be, is refused once it runs past the bound */
	@Example
	public void refusesADatabaseThatRunsPastTheBound() {
		final InputStream endless = new InputStream() {
			@Override
			public int read() {
				return 0;
			}
		};

		final MmdbCountryLookup.TooLargeException refused = assertThrows(MmdbCountryLookup.TooLargeException.class,
			() -> new MmdbCountryLookup(endless, 1024));

		assertThat(refused.size(), equalTo(1025L));
		assertThat(MmdbCountryLookup.MAX_BYTES, equalTo(64L * 1024 * 1024));
	}

	/* A damaged section fails every lookup that reaches it, which says so once and not for each of them */
	@Example
	public void saysOnceThatItsLookupsFail() {
		final CountryLookup lookup = damaged();
		final List<ILoggingEvent> logged = loggedWhile(() -> {
			assertThat(lookup.countryOf(InetAddress.ofLiteral("89.160.20.112")), equalTo(Optional.empty()));
			assertThat(lookup.countryOf(InetAddress.ofLiteral("2001:218::1")), equalTo(Optional.empty()));
			assertThat(lookup.countryOf(InetAddress.ofLiteral("81.2.69.160")), equalTo(Optional.empty()));
		});

		assertThat(logged.stream().map(ILoggingEvent::getLevel).toList(), equalTo(List.of(Level.WARN)));
		assertThat(loggedWhile(() -> damaged().countryOf(InetAddress.ofLiteral("89.160.20.112"))), hasSize(1));
	}

	@Example
	public void refusesWhatIsNoDatabase() {
		assertThrows(IOException.class, () -> new MmdbCountryLookup(new ByteArrayInputStream(new byte[64])));
	}
}

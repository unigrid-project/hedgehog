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

package org.unigrid.hedgehog.model.producer;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.math.BigDecimal;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeTry;
import net.jqwik.api.lifecycle.PropagationMode;
import org.apache.commons.lang3.reflect.FieldUtils;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import org.slf4j.LoggerFactory;
import org.unigrid.hedgehog.common.model.ApplicationDirectory;
import org.unigrid.hedgehog.jqwik.MockitHook;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.crypto.Signature;
import org.unigrid.hedgehog.model.spork.BoardKeyChain;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.KeyChange;
import org.unigrid.hedgehog.model.spork.LedgerFee;
import org.unigrid.hedgehog.model.spork.MintSupply;
import org.unigrid.hedgehog.model.spork.SporkDatabase;
import org.unigrid.hedgehog.model.util.Reflection;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.asReleased;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.buildIn;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.chainOf;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.change;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.key;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.keys;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.publicKeysOf;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.rootOf;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.signed;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.shipping;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.signedAnyway;

/* A node goes by the board keys of the database it loads before it judges the other sporks in it. The release builds
   in keys 0 and 1; the first change adds key 2, and the second switches keys 0 and 1 off and adds key 3. */
@AddLifecycleHook(value = MockitHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class BoardKeysLoadingTest {
	private static final byte[] ROOT = rootOf(keys(0, 1));
	private static final KeyChange ADDING = change(ROOT, List.of(), keys(0, 1, 2), keys(0, 1, 2));
	private static final KeyChange SWITCHING_OFF = change(ROOT, List.of(ADDING), keys(2, 3), keys(0, 2, 3));

	@BeforeTry
	public void before() {
		buildIn(keys(0, 1));
		asReleased();
	}

	@AfterTry
	public void after() {
		NetworkKey.reset();
	}

	@SneakyThrows
	private static <S extends GridSpork> S signedBy(S spork, Signature signer, Signature cosigner) {
		spork.archive();
		spork.setTimeStamp(Instant.now().minusSeconds(60));
		spork.sign(signer.getPrivateKey());
		spork.setCosignature(cosigner.sign(spork.getSignable()));
		return spork;
	}

	private static MintSupply supply() {
		final MintSupply spork = new MintSupply();

		spork.<MintSupply.SporkData>getData().setMaxSupply(BigDecimal.TEN);
		return spork;
	}

	@SneakyThrows
	private static SporkDatabase loaded(SporkDatabase stored) {
		try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
			final Path directory = Files.createDirectories(fs.getPath("/data"));
			final SporkDatabaseProducer producer = new SporkDatabaseProducer();

			SporkDatabase.persist(directory.resolve(SporkDatabase.SPORK_DB_FILE), stored);
			FieldUtils.writeField(producer, "applicationDirectory", new ApplicationDirectory("test", "test") {
				@Override
				public Path getUserDataDir() {
					return directory;
				}
			}, true);

			return Reflection.invoke(producer, "produce");
		}
	}

	@Example
	public void shouldGoByTheChainItLoadsAndKeepWhatKeysItSwitchedOffSigned() {
		final SporkDatabase stored = SporkDatabase.builder().build();

		stored.set(signed(chainOf(ADDING, SWITCHING_OFF), key(2), key(3)));
		stored.set(signedBy(supply(), key(0), key(1)));
		stored.set(signedBy(new LedgerFee(), key(2), key(3)));

		final SporkDatabase db = loaded(stored);

		assertThat(NetworkKey.getActiveKeys().active(), equalTo(Set.of(publicKeysOf(keys(2, 3)))));
		assertThat(db.getBoardKeys(), notNullValue());
		assertThat(db.getMintSupply(), notNullValue());
		assertThat(db.getMintSupply().isCounting(), is(false));
		assertThat(db.counting(GridSpork.Type.LEDGER_FEE), notNullValue());
	}

	@Example
	public void shouldLeaveOutBoardKeysThatAreRootedInOtherKeys() {
		final byte[] elsewhere = rootOf(keys(0, 5));

		assertLeftOut(signedAnyway(chainOf(change(elsewhere, List.of(), keys(0, 1, 5), keys(0, 1, 5))), key(0),
			key(1)));
	}

	/* A release that ships the first change takes no chain that goes another way from the root, and a node that starts
	   with no file goes by the shipped chain */
	@Example
	public void shouldGoByTheShippedChainAndLeaveOutBoardKeysThatDoNotHoldIt() {
		shipping(List.of(ADDING), true);
		assertThat(loaded(SporkDatabase.builder().build()).getBoardKeys(), nullValue());
		assertThat(NetworkKey.getActiveKeys().active(), equalTo(Set.of(publicKeysOf(keys(0, 1, 2)))));

		assertLeftOut(signedAnyway(chainOf(change(ROOT, List.of(), keys(0, 1, 5), keys(0, 1, 5))), key(0),
			key(1)));
	}

	private static void assertLeftOut(GridSpork boardKeys) {
		final ListAppender<ILoggingEvent> log = new ListAppender<>();
		final Logger logger = (Logger) LoggerFactory.getLogger(SporkDatabaseProducer.class);
		final SporkDatabase stored = SporkDatabase.builder().build();

		stored.set(boardKeys);
		log.start();
		logger.addAppender(log);

		try {
			assertThat(loaded(stored).getBoardKeys(), nullValue());
			assertThat(log.list.stream().map(ILoggingEvent::getFormattedMessage).toList(),
				hasItem(containsString("BOARD_KEYS")));
			assertThat(NetworkKey.getActiveKeys().length(), is(BoardKeyChain.shipped().size()));
		} finally {
			logger.detachAppender(log);
		}
	}
}

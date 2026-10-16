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

package org.unigrid.hedgehog.service.ledger;

import io.netty.channel.embedded.EmbeddedChannel;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.unigrid.hedgehog.service.ledger.LedgerStreams.sporkTypesWrittenTo;
import static org.unigrid.hedgehog.service.ledger.LedgerStreams.stream;
import org.unigrid.hedgehog.model.network.schedule.PublishAndSaveSporkSchedule;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;
import org.unigrid.hedgehog.model.spork.PendingSporks;
import org.unigrid.hedgehog.model.spork.SporkDatabase;

public class SporkRecipientsTest {
	/* The types that release 0.0.8 knows: it cannot read any other, and a frame of one wrecks its stream */
	private static final Set<Type> KNOWN_TO_RELEASE_0_0_8 = Set.of(Type.UNDEFINED, Type.MINT_STORAGE, Type.MINT_SUPPLY,
		Type.VESTING_STORAGE, Type.STORAGE, Type.STATISTICS_PUBKEY);

	private static GridSpork spork(final Type type) {
		return GridSpork.create(type);
	}

	private static SporkDatabase databaseWithEverySpork() {
		final SporkDatabase database = SporkDatabase.builder().build();

		Arrays.stream(Type.values()).filter(type -> type != Type.UNDEFINED).forEach(type -> database.set(spork(type)));
		return database;
	}

	@Example
	public void shouldNeedTheLedgerProtocolForEveryTypeThatTheEarlierReleaseCannotRead() {
		for (final Type type : Type.values()) {
			assertThat(type.toString(), SporkRecipients.needsLedgerProtocol(type),
				is(!KNOWN_TO_RELEASE_0_0_8.contains(type)));
		}
	}

	@Example
	public void shouldWriteALedgerSporkOnlyToAStreamThatSettledOnTheLedgerProtocol() {
		final EmbeddedChannel ledger = stream(true);
		final EmbeddedChannel earlier = stream(false);

		for (final Type type : List.of(Type.LEDGER_BLACKLIST, Type.LEDGER_FEE)) {
			SporkRecipients.write(ledger, spork(type));
			SporkRecipients.write(earlier, spork(type));
		}

		assertThat(sporkTypesWrittenTo(ledger), contains(Type.LEDGER_BLACKLIST, Type.LEDGER_FEE));
		assertThat(sporkTypesWrittenTo(earlier), empty());
	}

	@Example
	public void shouldWriteAnEarlierSporkToEveryStream() {
		for (final Type type : KNOWN_TO_RELEASE_0_0_8) {
			if (type != Type.UNDEFINED) {
				final EmbeddedChannel ledger = stream(true);
				final EmbeddedChannel earlier = stream(false);

				SporkRecipients.write(ledger, spork(type));
				SporkRecipients.write(earlier, spork(type));

				assertThat(sporkTypesWrittenTo(ledger), contains(type));
				assertThat(sporkTypesWrittenTo(earlier), contains(type));
			}
		}
	}

	@Example
	public void shouldWriteNothingForASporkThatIsNotSet() {
		final EmbeddedChannel ledger = stream(true);

		SporkRecipients.write(ledger, null);

		assertThat(sporkTypesWrittenTo(ledger), empty());
		assertThat(SporkRecipients.mayReceive(ledger, null), is(false));
	}

	@Example
	public void shouldGiveTheScheduleAndTheNewStreamsTheLedgerSporksOnlyOnTheLedgerProtocol() {
		final SporkDatabase database = databaseWithEverySpork();
		final EmbeddedChannel ledger = stream(true);
		final EmbeddedChannel earlier = stream(false);

		PublishAndSaveSporkSchedule.writeAndFlush(ledger, database, new PendingSporks());
		PublishAndSaveSporkSchedule.writeAndFlush(earlier, database, new PendingSporks());

		assertThat(sporkTypesWrittenTo(ledger), contains(Type.MINT_STORAGE, Type.MINT_SUPPLY, Type.VESTING_STORAGE,
			Type.STORAGE, Type.LEDGER_BLACKLIST, Type.LEDGER_FEE));
		assertThat(sporkTypesWrittenTo(earlier), contains(Type.MINT_STORAGE, Type.MINT_SUPPLY, Type.VESTING_STORAGE,
			Type.STORAGE));
	}

	@Example
	public void shouldNotWriteTheSporksThatAreNotSet() {
		final EmbeddedChannel ledger = stream(true);
		final SporkDatabase database = SporkDatabase.builder().build();

		database.set(spork(Type.MINT_SUPPLY));
		PublishAndSaveSporkSchedule.writeAndFlush(ledger, database, new PendingSporks());

		assertThat(sporkTypesWrittenTo(ledger), equalTo(List.of(Type.MINT_SUPPLY)));
	}
}

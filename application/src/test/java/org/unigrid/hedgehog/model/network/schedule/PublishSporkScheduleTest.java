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

package org.unigrid.hedgehog.model.network.schedule;

import io.netty.channel.embedded.EmbeddedChannel;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import lombok.SneakyThrows;
import mockit.Mock;
import mockit.MockUp;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.crypto.Signature;
import org.unigrid.hedgehog.model.network.packet.PublishSpork;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.LedgerBlacklist;
import org.unigrid.hedgehog.model.spork.LedgerFee;
import org.unigrid.hedgehog.model.spork.MintSupply;
import org.unigrid.hedgehog.model.spork.PendingSporks;
import org.unigrid.hedgehog.model.spork.SporkDatabase;
import org.unigrid.hedgehog.service.ledger.LedgerStreams;

public class PublishSporkScheduleTest {
	private static Signature proposer;

	@SneakyThrows
	@Example
	public void shouldPublishProposalsAlongWithStoredSporks() {
		final EmbeddedChannel channel = new EmbeddedChannel();
		final PendingSporks pendingSporks = new PendingSporks();
		final MintSupply proposal = new MintSupply();
		final MintSupply.SporkData data = new MintSupply.SporkData();

		proposer = new Signature();

		new MockUp<NetworkKey>() {
			@Mock public static String[] getPublicKeys() {
				return new String[] { proposer.getPublicKey() };
			}
		};

		data.setMaxSupply(BigDecimal.TEN);
		proposal.setData(data);
		proposal.archive();
		proposal.sign(proposer.getPrivateKey());
		pendingSporks.offer(proposal, null);

		PublishSporkSchedule.writeAndFlush(channel, SporkDatabase.builder().build(), pendingSporks);

		assertThat(published(channel), hasItem(proposal));
	}

	@Example
	public void shouldPublishTheStoredLedgerBlacklist() {
		final EmbeddedChannel channel = LedgerStreams.stream(true);
		final LedgerBlacklist blacklist = new LedgerBlacklist();

		PublishSporkSchedule.writeAndFlush(channel, SporkDatabase.builder().ledgerBlacklist(blacklist).build(),
			new PendingSporks());

		assertThat(published(channel), hasItem(sameInstance(blacklist)));
	}

	@Example
	public void shouldPublishTheStoredLedgerFee() {
		final EmbeddedChannel channel = LedgerStreams.stream(true);
		final LedgerFee fee = new LedgerFee();

		PublishSporkSchedule.writeAndFlush(channel, SporkDatabase.builder().ledgerFee(fee).build(),
			new PendingSporks());

		assertThat(published(channel), hasItem(sameInstance(fee)));
	}

	@Example
	public void shouldKeepTheStoredLedgerSporksFromAStreamThatDidNotSettleOnTheLedgerProtocol() {
		final EmbeddedChannel channel = LedgerStreams.stream(false);

		PublishSporkSchedule.writeAndFlush(channel, SporkDatabase.builder().ledgerFee(new LedgerFee())
			.ledgerBlacklist(new LedgerBlacklist()).mintSupply(new MintSupply()).build(), new PendingSporks());

		assertThat(published(channel).stream().map(GridSpork::getType).toList(),
			equalTo(List.of(GridSpork.Type.MINT_SUPPLY)));
	}

	private static List<GridSpork> published(EmbeddedChannel channel) {
		final List<GridSpork> published = new ArrayList<>();

		for (PublishSpork packet = channel.readOutbound(); Objects.nonNull(packet); packet = channel.readOutbound()) {
			published.add(packet.getGridSpork());
		}

		return published;
	}
}

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

package org.unigrid.hedgehog.model.network.codec;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import io.netty.channel.ChannelHandlerContext;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.SneakyThrows;
import mockit.Mock;
import mockit.MockUp;
import mockit.Mocked;
import net.jqwik.api.Example;
import org.apache.commons.lang3.SerializationUtils;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.crypto.Signature;
import org.unigrid.hedgehog.model.network.packet.PublishSpork;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.MintStorage;
import org.unigrid.hedgehog.model.spork.MintSupply;
import org.unigrid.hedgehog.model.spork.SporkDatabase;
import org.unigrid.hedgehog.model.spork.StatisticsPubKey;
import org.unigrid.hedgehog.model.spork.VestingStorage;

/*
 * The fixture was written by the 0.0.7 release itself: every spork type, signed once with the key below, with
 * mints sharing an Address object and vesting times carrying nanoseconds. Nodes upgrading from 0.0.7 have to
 * load it, keep its signatures valid and be able to re-sign it for the whole network to accept.
 */
public class Release007SporkDatabaseTest extends BaseCodecTest<PublishSpork> {
	private static final String FIXTURE = "/spork/spork-0.0.7.db";

	private static final String FIXTURE_PUBLIC_KEY = "1e4a2fdd31b9af5214878f81e4705d5027c3624cd776764fcb2591b67e48cb"
		+ "7f3a833c64d4d1e5251557c786a9f011d523425a4f5ef6d7e777c61ceb55924be6bbd1c4dac1c48c823038acfdbdd4e217634b"
		+ "78eacfab1577c290dce5d25514fa7c6e848732a5085e7eb5dea172e6b77e982306f2d191028ef99808b8385361ec343a77";

	/*
	 * Vesting is left out: 0.0.7 signed sporks over their Java serialization, and the vesting schedule fields added
	 * since change that of every vesting. Its sporks still load, but the board has to sign them anew.
	 */
	private static final List<GridSpork.Type> TYPES = List.of(GridSpork.Type.MINT_STORAGE, GridSpork.Type.MINT_SUPPLY,
		GridSpork.Type.STATISTICS_PUBKEY
	);

	private static String[] networkKeys;

	@SneakyThrows
	private SporkDatabase fixture() {
		return SporkDatabase.load(Path.of(getClass().getResource(FIXTURE).toURI()));
	}

	/* The 0.0.7 signer has since been retired; two fresh keys stand in for the current board */
	@SneakyThrows
	private static List<Signature> currentBoard() {
		final List<Signature> board = List.of(new Signature(), new Signature());

		networkKeys = board.stream().map(Signature::getPublicKey).toArray(String[]::new);

		new MockUp<NetworkKey>() {
			@Mock public /* static */ String[] getPublicKeys() {
				return networkKeys;
			}

			@Mock public /* static */ String[] getRetiredPublicKeys() {
				return new String[] { FIXTURE_PUBLIC_KEY };
			}
		};

		return board;
	}

	@Example
	public void shouldLoadEverySporkType() {
		final SporkDatabase database = fixture();
		final MintStorage.SporkData mints = database.getMintStorage().getData();
		final VestingStorage.SporkData vestings = database.getVestingStorage().getData();
		final VestingStorage.SporkData.Vesting vesting = vestings.getVestingAddresses().values().iterator().next();

		assertThat(mints.getMints().size(), equalTo(40));
		assertThat(database.getMintSupply().<MintSupply.SporkData>getData().getMaxSupply(),
			equalTo(new BigDecimal("1000000000"))
		);
		assertThat(vestings.getVestingAddresses().size(), equalTo(10));
		assertThat(vesting.getStart(), equalTo(Instant.parse("2023-06-01T00:00:00.123456789Z")));
		assertThat(vesting.getDuration(), equalTo(Duration.ofDays(30).plusNanos(5)));
		assertThat(database.getStatisticsPubKey().<StatisticsPubKey.SporkData>getData().getPublicKey(),
			equalTo(FIXTURE_PUBLIC_KEY)
		);
	}

	@SneakyThrows
	@Example
	public void shouldKeepTheSignaturesValidAcrossSaving() {
		final SporkDatabase database = fixture();
		final Path saved = Jimfs.newFileSystem(Configuration.unix()).getPath("/spork.db");

		SporkDatabase.persist(saved, database);

		final SporkDatabase reloaded = SporkDatabase.load(saved);

		for (GridSpork.Type type : TYPES) {
			assertThat(type.name(), Signature.verify(database.get(type), FIXTURE_PUBLIC_KEY), is(true));
			assertThat(type.name(), Signature.verify(reloaded.get(type), FIXTURE_PUBLIC_KEY), is(true));
		}
	}

	@SneakyThrows
	@Example
	public void shouldAcceptTheirRenewalOnEveryNode(@Mocked ChannelHandlerContext context) {
		final SporkDatabase database = fixture();
		final List<Signature> board = currentBoard();

		for (GridSpork.Type type : TYPES) {
			final GridSpork stored = database.get(type);
			final GridSpork renewed = SerializationUtils.clone(stored);

			renewed.renew();
			renewed.sign(board.get(0).getPrivateKey());
			renewed.cosign(board.get(1).getPrivateKey());

			final GridSpork received = encodeDecode(PublishSpork.builder().gridSpork(renewed).build(),
				new PublishSporkEncoder(), new PublishSporkDecoder(), context
			).getGridSpork();

			assertThat(type.name(), stored, is(notNullValue()));
			assertThat(type.name(), renewed.getSignatureLog().last().getSigner(), equalTo(FIXTURE_PUBLIC_KEY));
			assertThat(type.name(), renewed.canReplace(stored), is(true));
			assertThat(type.name(), received.canReplace(stored), is(true));
			assertThat(type.name(), received.canReplace(SerializationUtils.clone(stored)), is(true));
			assertThat(type.name(), received.getData(), equalTo(stored.getData()));
		}
	}
}

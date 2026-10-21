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

import jakarta.enterprise.inject.spi.CDI;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import mockit.Invocation;
import mockit.Mock;
import mockit.MockUp;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.BeforeContainer;
import static org.awaitility.Awaitility.await;
import org.jboss.weld.environment.se.WeldContainer;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.unigrid.hedgehog.service.ledger.FleetContainers.bean;
import static org.unigrid.hedgehog.service.ledger.LedgerFleet.COIN;
import org.unigrid.hedgehog.ledger.AccountKey;
import org.unigrid.hedgehog.ledger.Ballot;
import org.unigrid.hedgehog.ledger.Claim;
import org.unigrid.hedgehog.ledger.Ed25519;
import org.unigrid.hedgehog.ledger.LedgerPeer;
import org.unigrid.hedgehog.ledger.Message;
import org.unigrid.hedgehog.ledger.Proposal;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;
import org.unigrid.hedgehog.model.spork.LedgerBlacklist;
import org.unigrid.hedgehog.model.spork.LedgerFee;
import org.unigrid.hedgehog.model.spork.PendingSporks;
import org.unigrid.hedgehog.model.spork.SporkDatabase;

/* The legacy addresses that the foundation holds back, on a running network: validators and an observer that dial
   each other over QUIC, each with its own spork database and ledger service. The list is put out the way the foundation
   does it, proposed to the REST server of a node and co-signed, and the daemon spreads it. A claim for a listed address
   is refused at submit on every node, and no block comes of it. A node that has no list, or knows that a newer one is
   out and has not got it, takes no claim at all, so it cannot be the one that lets a listed address through with a
   list that is out of date. A validator whose policy says yes to such a claim proposes it, and the honest validators
   prevote nil, so the block is never decided and the chain goes on without it. A block that more than 2/3 of the
   validators signed stands whatever the list says: a node that catches up after the list names the address, and a
   node that joins after it, applies the claim. The fleet is shared by the examples, so each one has wallets and
   accounts of its own, sets the list it needs and leaves nothing waiting. */
public class LedgerBlacklistAcceptanceTest extends BaseLedgerAcceptanceTest {
	private static final int WALLETS = 10;
	private static final String BLACKLIST = "/gridspork/ledger-blacklist";

	/* The container of the validator that is made to say yes to every claim, and what each node hears of the
	   consensus */
	private static final AtomicReference<String> LIAR = new AtomicReference<>();
	private static final Queue<Heard> HEARD = new ConcurrentLinkedQueue<>();

	private record Heard(String node, Message message) {
	}

	private static String thisNode() {
		return ((WeldContainer) CDI.current()).getId();
	}

	private static boolean isTheLiar() {
		final String liar = LIAR.get();

		return Objects.nonNull(liar) && liar.equals(thisNode());
	}

	@Override
	protected LedgerFleet.Settings settings() {
		return LedgerFleet.Settings.builder().balances(IntStream.range(0, WALLETS)
			.mapToObj(n -> (40 + n) * COIN + 12_345L * (n + 1)).toList()).build();
	}

	/* One validator at a time can be a Byzantine proposer: its authority says yes to a claim whatever the list says, as
	   one does that runs another policy. The others keep the real authority. The doubles are made once, as a fake that
	   is made again over the one before it breaks the invocations it passes on. */
	@BeforeContainer
	private static void fakeTheValidatorThatLiesAndListenToTheConsensus() {
		new MockUp<SporkLedgerAuthority>() {
			@Mock
			boolean authorizes(final Invocation invocation, final Claim claim) {
				return isTheLiar() || invocation.<Boolean>proceed();
			}
		};

		new MockUp<LedgerGateway>() {
			@Mock
			void onMessage(final Invocation invocation, final LedgerPeer from, final Message message,
				final int bytes) {

				HEARD.add(new Heard(thisNode(), message));
				invocation.proceed();
			}
		};
	}

	/* The REST server of the observer, so that the foundation's node is none of the validators */
	private int restHost() {
		return fleet().validators();
	}

	private IntStream validators() {
		return IntStream.range(0, fleet().validators()).filter(fleet()::isRunning);
	}

	private AccountKey keyOf(final int validator) {
		return Ed25519.publicKey(fleet().seed(validator));
	}

	@Override
	protected void ready() {
		awaitSigning();

		if (Objects.isNull(fleet().sporkOn(0, Type.MINT_SUPPLY))) {
			fleet().publishSpork(Type.MINT_SUPPLY, ceilingOf(CEILING));
		}

		if (Objects.isNull(fleet().sporkOn(0, Type.MINT_STORAGE))) {
			fleet().publishSpork(Type.MINT_STORAGE, mints(Map.of()));
		}

		if (Objects.isNull(fleet().sporkOn(0, Type.LEDGER_FEE))) {
			fleet().publishSpork(Type.LEDGER_FEE, new LedgerFee.SporkData());
		}
	}

	private LedgerBlacklist.SporkData listing(final LegacyWallet... wallets) {
		final LedgerBlacklist.SporkData data = new LedgerBlacklist.SporkData();

		data.setLegacyAddresses(Arrays.stream(wallets).map(LegacyWallet::hash160Hex)
			.collect(Collectors.toCollection(TreeSet::new)));
		return data;
	}

	private Set<String> listedOn(final int node) {
		return Optional.ofNullable(fleet().sporkOn(node, Type.LEDGER_BLACKLIST))
			.map(spork -> spork.<LedgerBlacklist.SporkData>getData().getLegacyAddresses()).orElse(Set.of());
	}

	private void awaitListed(final LedgerBlacklist.SporkData data) {
		await().atMost(PATIENCE).until(() -> everywhere(node -> listedOn(node).equals(data.getLegacyAddresses())));
	}

	/* The list as the foundation puts it out: proposed to the REST server of a node, co-signed, and spread by it */
	private void blacklist(final LegacyWallet... wallets) {
		final LedgerBlacklist.SporkData data = listing(wallets);

		fleet().cosignByRest(restHost(), fleet().proposeByRest(restHost(), BLACKLIST, data));
		awaitListed(data);
	}

	/* The nodes pass on a spork they took, so a copy that is late would put back a list that a test has just taken from
	   a node. When the list has been quiet for a moment the copies have arrived. */
	private void awaitGossipSettled() {
		staysTrue(() -> everywhere(node -> Objects.nonNull(fleet().sporkOn(node, Type.LEDGER_BLACKLIST))));
	}

	private int proposerAt(final long height) {
		final AccountKey proposer = fleet().genesis().getValidators().proposerAt(height);

		return validators().filter(node -> keyOf(node).equals(proposer)).findFirst().orElseThrow();
	}

	private boolean heardNilPrevote(final int at, final int validator, final long height) {
		return HEARD.stream().anyMatch(heard -> heard.node().equals(containerName(at))
			&& heard.message() instanceof Ballot ballot && ballot.type() == Ballot.Type.PREVOTE && ballot.isNil()
			&& ballot.height() == height && ballot.view() == 0 && ballot.validator().equals(keyOf(validator)));
	}

	private boolean heardProposalOf(final int at, final int proposer, final long height, final LegacyWallet claimed) {
		return HEARD.stream().anyMatch(heard -> heard.node().equals(containerName(at))
			&& heard.message() instanceof Proposal proposal && proposal.sender().equals(keyOf(proposer))
			&& proposal.height() == height && proposal.view() == 0
			&& proposal.block().getTransactions().stream().anyMatch(transaction ->
				transaction instanceof Claim claim && claim.address().equals(claimed.address())));
	}

	@Example
	public void shouldRefuseAClaimOfAListedAddressOnEveryNodeAndTakeItOnceTheListLetsItGo() {
		final AccountKey owner = account();
		final AccountKey neighbour = account();

		ready();
		blacklist(fleet().wallet(0));

		assertRefusedEverywhere(claimOf(0, owner), HELD_BACK);
		staysAsItIs();
		assertThat(balances(owner), everyItem(is(0L)));

		claim(1, 1, neighbour);
		assertRefusedEverywhere(claimOf(0, owner), HELD_BACK);

		blacklist();
		claim(1, 0, owner);
		assertThat(balances(owner), everyItem(is(fleet().holding(0))));
	}

	/* A list that is only proposed waits for its co-signature and changes nothing: a single network key cannot hold
	   the claims back, and the older list stays in force until the second key has signed. */
	@Example
	public void shouldKeepTheOlderListInForceWhileANewerOneAwaitsItsCosignature() {
		final int host = 0;
		final AccountKey owner = account();
		final AccountKey neighbour = account();
		final LedgerBlacklist.SporkData newer = listing(fleet().wallet(2));

		ready();
		blacklist();
		awaitGossipSettled();

		final String digest = fleet().proposeByRest(host, BLACKLIST, newer);

		assertThat(bean(containerName(host), PendingSporks.class).proposalOf(Type.LEDGER_BLACKLIST)
			.isPresent(), is(true));
		assertThat(fleet().status(host).laggingSporks(), not(hasItem(Type.LEDGER_BLACKLIST)));
		claim(1, 3, neighbour);
		assertThat(balances(neighbour), everyItem(is(fleet().holding(3))));

		fleet().cosignByRest(host, digest);
		awaitListed(newer);
		assertRefusedEverywhere(claimOf(2, owner), HELD_BACK);
		assertThat(balances(owner), everyItem(is(0L)));
	}

	@Example
	public void shouldTakeNoClaimOnAValidatorThatHasNoListUntilItHasOne() {
		final int bare = 2;
		final AccountKey owner = account();

		ready();
		blacklist(fleet().wallet(8));
		awaitGossipSettled();

		final GridSpork list = fleet().sporkOn(0, Type.LEDGER_BLACKLIST);

		bean(containerName(bare), SporkDatabase.class).setLedgerBlacklist(null);
		assertThat(fleet().status(bare).unsetSporks(), hasItem(Type.LEDGER_BLACKLIST));
		assertThat(fleet().submit(bare, claimOf(9, owner)), equalTo(HELD_BACK));
		assertThat(fleet().submit(bare, claimOf(8, owner)), equalTo(HELD_BACK));
		staysAsItIs();

		fleet().deliverSpork(bare, list);
		assertThat(fleet().submit(bare, claimOf(8, owner)), equalTo(HELD_BACK));
		claim(bare, 9, owner);
	}

	/* The proposer of the next height is made to take the claim, so that it puts the block forward at once. One of the
	   honest validators has no list while it votes, which must not make it vote for the block either. */
	@Example
	public void shouldHaveTheHonestValidatorsPrevoteNilForAListedClaimAndGoOnWithoutIt() {
		final LegacyWallet listed = fleet().wallet(4);
		final AccountKey taker = account();
		final AccountKey neighbour = account();

		ready();
		blacklist(listed);
		awaitGossipSettled();
		fleet().awaitSameChain();

		final long height = fleet().height(0) + 1;
		final int liar = proposerAt(height);
		final List<Integer> honest = validators().filter(node -> node != liar).boxed().toList();
		final int bare = honest.getFirst();
		final GridSpork list = fleet().sporkOn(0, Type.LEDGER_BLACKLIST);

		LIAR.set(containerName(liar));

		try {
			bean(containerName(bare), SporkDatabase.class).setLedgerBlacklist(null);
			HEARD.clear();
			assertThat(fleet().submit(liar, claimOf(4, taker)), equalTo(Optional.empty()));

			await().atMost(PATIENCE).until(() -> honest.stream().allMatch(node ->
				heardNilPrevote(liar, node, height)));
			assertThat(fleet().status(bare).unsetSporks(), hasItem(Type.LEDGER_BLACKLIST));
			honest.forEach(node ->
				assertThat("node " + node, heardProposalOf(node, liar, height, listed), is(true)));
			staysAsItIs();
			assertThat(balances(taker), everyItem(is(0L)));

			fleet().deliverSpork(bare, list);
			claim(honest.getLast(), 5, neighbour);
		} finally {
			LIAR.set(null);
		}

		assertThat(fleet().height(0), is(height));
		assertThat(balances(taker), everyItem(is(0L)));
		fleet().stop(liar);
		fleet().restart(liar);
		awaitSigning();
		assertRefusedEverywhere(claimOf(4, taker), HELD_BACK);
	}

	/* The node that was away and the one that joins have the list when they come to the block, and their own answer to
	   a claim of a listed address is no, yet the block that holds the claim of one is theirs like everyone's */
	@Example
	public void shouldApplyACertifiedBlockWithAClaimTheListNowNamesOnNodesThatComeToItWithTheList() {
		final AccountKey owner = account();
		final int away = 3;

		ready();
		blacklist();
		fleet().stop(away);
		claim(1, 6, owner);
		blacklist(fleet().wallet(6), fleet().wallet(7));
		fleet().deliverSpork(away, fleet().sporkOn(0, Type.LEDGER_BLACKLIST));
		fleet().restart(away);

		final long height = fleet().height(0);
		final int joined = fleet().addObserver(fleet().sporkOn(0, Type.LEDGER_BLACKLIST));

		fleet().awaitHeight(away, height);
		fleet().awaitHeight(joined, height);
		fleet().awaitSameChain();

		List.of(away, joined).forEach(node -> {
			assertThat("node " + node, listedOn(node), hasItem(fleet().wallet(6).hash160Hex()));
			assertThat("node " + node, fleet().balanceOf(node, owner), is(fleet().holding(6)));
			assertThat("node " + node, fleet().submit(node, claimOf(7, account())), equalTo(HELD_BACK));
		});
	}
}

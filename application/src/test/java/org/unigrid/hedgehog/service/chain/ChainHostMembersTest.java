/*
    Unigrid Hedgehog
    Copyright © 2021-2025 Stiftelsen The Unigrid Foundation

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

package org.unigrid.hedgehog.service.chain;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import org.slf4j.LoggerFactory;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import org.unigrid.hedgehog.model.gridnode.Gridnode;
import org.unigrid.hedgehog.proxy.chain.Chain;
import org.unigrid.hedgehog.proxy.chain.ChainId;
import org.unigrid.hedgehog.proxy.group.Epoch;
import org.unigrid.hedgehog.proxy.group.GroupView;
import org.unigrid.hedgehog.proxy.group.ProxySettings;

public class ChainHostMembersTest {
	private static final String OWN = "gn-3";
	private static final String FAILURE_DETAIL = "10.9.8.7:52883 turned the stream down";
	private static final long EPOCH_MILLIS = 3_600_000;

	private static List<String> othersInTheGroupOf(final ChainFleet fleet, final String id) {
		final GroupView view = fleet.view();

		return view.groups().members(view.groups().groupOf(id).orElseThrow()).stream()
			.filter(member -> !member.equals(id)).toList();
	}

	private static Channel[] streams(final ChainFleet fleet, final String owner, final List<String> peers) {
		return peers.stream().map(peer -> fleet.stream(owner, peer)).toArray(Channel[]::new);
	}

	private static List<Channel> memberStreams(final ChainHost host) {
		return host.hosted(host.hostedChains().get(0)).orElseThrow().getMemberStreams();
	}

	private static List<String> without(final List<String> members, final String left) {
		return members.stream().filter(member -> !member.equals(left)).toList();
	}

	private static void assertHoldsStreamsToTheOthersOfAGroupOf(final int groupSize, final int expectedOthers) {
		final ChainFleet fleet = new ChainFleet(8);

		fleet.settings.set(Optional.of(ChainFleet.settings(groupSize, 4, 16, 30, 3)));

		final List<String> others = othersInTheGroupOf(fleet, OWN);

		others.forEach(fleet::host);
		fleet.host(OWN).refresh();

		assertThat(others, hasSize(expectedOthers));
		assertThat(memberStreams(fleet.host(OWN)), containsInAnyOrder(streams(fleet, OWN, others)));
	}

	private static void assertAsksNothingWhile(final Consumer<ChainFleet> change, final String own) {
		final ChainFleet fleet = new ChainFleet(20);
		final List<String> asked = new ArrayList<>();
		final ChainHost host = fleet.hostOver(own, member -> {
			asked.add(member);
			return Optional.empty();
		});

		change.accept(fleet);
		host.refresh();
		host.refresh();

		assertThat(host.hostedChains(), is(empty()));
		assertThat(asked, is(empty()));
	}

	private static ChainTransport failingFor(final ChainFleet fleet, final String unlucky, final AtomicBoolean failing) {
		return member -> {
			if (failing.get() && member.equals(unlucky)) {
				throw new IllegalStateException(FAILURE_DETAIL);
			}

			return Optional.of(fleet.stream(OWN, member));
		};
	}

	/* The module descriptor reads logback-classic only, so the in-memory appender of logback-core is out of reach */
	private static void readLogbackCore() {
		ChainHostMembersTest.class.getModule()
			.addReads(ModuleLayer.boot().findModule("ch.qos.logback.core").orElseThrow());
	}

	private static List<ILoggingEvent> warningsWhile(final Runnable action) {
		readLogbackCore();

		final Logger logger = (Logger) LoggerFactory.getLogger(ChainHost.class);
		final ListAppender<ILoggingEvent> appender = new ListAppender<>();

		appender.start();
		logger.addAppender(appender);

		try {
			action.run();
		} finally {
			logger.detachAppender(appender);
		}

		return appender.list.stream().filter(event -> event.getLevel().isGreaterOrEqual(Level.WARN)).toList();
	}

	@Example
	public void holdsTheStreamToEveryOtherMemberOfItsGroupOnEveryChain() {
		final ChainFleet fleet = new ChainFleet(20);
		final List<String> others = othersInTheGroupOf(fleet, OWN);

		others.forEach(fleet::host);
		fleet.host(OWN).refresh();

		assertThat(others, is(not(empty())));
		fleet.host(OWN).hostedChains().forEach(chainId -> assertThat(
			fleet.host(OWN).hosted(chainId).orElseThrow().getMemberStreams(),
			containsInAnyOrder(streams(fleet, OWN, others))));
	}

	@Example
	public void holdsNoStreamToAMemberItHasNoConnectionTo() {
		final ChainFleet fleet = new ChainFleet(20);
		final List<String> others = othersInTheGroupOf(fleet, OWN);

		others.subList(1, others.size()).forEach(fleet::host);
		fleet.host(OWN).refresh();

		assertThat(memberStreams(fleet.host(OWN)),
			containsInAnyOrder(streams(fleet, OWN, others.subList(1, others.size()))));
	}

	@Example
	public void looksTheStreamsUpAgainOnEveryRefresh() {
		final ChainFleet fleet = new ChainFleet(20);
		final List<String> others = othersInTheGroupOf(fleet, OWN);
		final ChainHost host = fleet.host(OWN);

		others.forEach(fleet::host);
		fleet.offline(others.get(0));
		host.refresh();
		assertThat(memberStreams(host), containsInAnyOrder(streams(fleet, OWN, others.subList(1, others.size()))));

		fleet.online(others.get(0));
		host.refresh();
		assertThat(memberStreams(host), containsInAnyOrder(streams(fleet, OWN, others)));
	}

	@Example
	public void holdsNoStreamToItselfWhateverTheSizeOfItsGroup() {
		assertHoldsStreamsToTheOthersOfAGroupOf(1, 0);
		assertHoldsStreamsToTheOthersOfAGroupOf(2, 1);
		assertHoldsStreamsToTheOthersOfAGroupOf(3, 3);
		assertHoldsStreamsToTheOthersOfAGroupOf(8, 7);
	}

	@Example
	public void noticesAStreamThatGoesAndOneThatComesBack() {
		final ChainFleet fleet = new ChainFleet(20);
		final List<String> others = othersInTheGroupOf(fleet, OWN);
		final ChainHost host = fleet.host(OWN);

		others.forEach(fleet::host);
		host.refresh();
		assertThat(memberStreams(host), containsInAnyOrder(streams(fleet, OWN, others)));

		fleet.offline(others.get(0));
		host.refresh();
		assertThat(memberStreams(host), containsInAnyOrder(streams(fleet, OWN, without(others, others.get(0)))));

		fleet.online(others.get(0));
		host.refresh();
		assertThat(memberStreams(host), containsInAnyOrder(streams(fleet, OWN, others)));
	}

	@Example
	public void holdsNoStreamThatHasBeenClosed() {
		final ChainFleet fleet = new ChainFleet(20);
		final List<String> others = othersInTheGroupOf(fleet, OWN);
		final ChainHost host = fleet.host(OWN);

		others.forEach(fleet::host);
		fleet.stream(OWN, others.get(0)).close();
		host.refresh();
		assertThat(memberStreams(host), containsInAnyOrder(streams(fleet, OWN, without(others, others.get(0)))));

		fleet.stream(OWN, others.get(1)).close();
		host.refresh();
		assertThat(memberStreams(host),
			containsInAnyOrder(streams(fleet, OWN, without(without(others, others.get(0)), others.get(1)))));
	}

	@Example
	public void holdsTheStreamsOfTheNewGroupWhenTheEpochChanges() {
		final ChainFleet fleet = new ChainFleet(20);
		final List<String> before = othersInTheGroupOf(fleet, OWN);

		fleet.clock.addAndGet(EPOCH_MILLIS);

		final List<String> after = othersInTheGroupOf(fleet, OWN);

		fleet.clock.addAndGet(-EPOCH_MILLIS);
		fleet.activeIds().forEach(fleet::host);
		fleet.host(OWN).refresh();
		assertThat(memberStreams(fleet.host(OWN)), containsInAnyOrder(streams(fleet, OWN, before)));

		fleet.clock.addAndGet(EPOCH_MILLIS);
		fleet.host(OWN).refresh();

		assertThat(new HashSet<>(after), is(not(equalTo(new HashSet<>(before)))));
		assertThat(memberStreams(fleet.host(OWN)), containsInAnyOrder(streams(fleet, OWN, after)));
	}

	@Example
	public void keepsTheChainsItHostsWhileTheStreamsChange() {
		final ChainFleet fleet = new ChainFleet(20);
		final List<String> others = othersInTheGroupOf(fleet, OWN);
		final ChainHost host = fleet.host(OWN);

		others.forEach(fleet::host);
		host.refresh();

		final ChainId first = host.hostedChains().get(0);
		final HostedChain before = host.hosted(first).orElseThrow();

		fleet.offline(others.get(0));
		host.refresh();

		assertThat(host.hosted(first).orElseThrow(), sameInstance(before));
		assertThat(before.getMemberStreams(), hasSize(others.size() - 1));
	}

	@Example
	public void asksOnceForEveryOtherMemberOfItsGroupOnEveryRefresh() {
		final ChainFleet fleet = new ChainFleet(20);
		final List<String> others = othersInTheGroupOf(fleet, OWN);
		final List<String> asked = new ArrayList<>();
		final ChainHost host = fleet.hostOver(OWN, member -> {
			asked.add(member);
			return Optional.empty();
		});

		host.refresh();

		assertThat(host.hostedChains().size(), is(greaterThan(1)));
		assertThat(asked, containsInAnyOrder(others.toArray(String[]::new)));

		asked.clear();
		host.refresh();

		assertThat(asked, containsInAnyOrder(others.toArray(String[]::new)));
	}

	@Example
	public void asksNobodyWhenTheStreamsAreRead() {
		final ChainFleet fleet = new ChainFleet(20);
		final List<String> asked = new ArrayList<>();
		final ChainHost host = fleet.hostOver(OWN, member -> {
			asked.add(member);
			return Optional.empty();
		});

		host.refresh();
		asked.clear();
		host.hostedChains().forEach(chainId -> host.hosted(chainId).orElseThrow().getMemberStreams());

		assertThat(asked, is(empty()));
	}

	@Example
	public void asksNobodyWhileItHostsNothing() {
		assertAsksNothingWhile(fleet -> fleet.settings.set(Optional.empty()), OWN);
		assertAsksNothingWhile(fleet -> fleet.clock.set(-1), OWN);
		assertAsksNothingWhile(fleet -> fleet.gridnodes.get(3).setStatus(Gridnode.Status.INACTIVE), OWN);
		assertAsksNothingWhile(fleet -> { }, "stranger");
		assertAsksNothingWhile(fleet -> { }, null);
	}

	@Example
	public void goesOnWithTheOtherMembersWhenALookupThrows() {
		final ChainFleet fleet = new ChainFleet(20);
		final List<String> others = othersInTheGroupOf(fleet, OWN);
		final ChainHost host = fleet.hostOver(OWN, failingFor(fleet, others.get(0), new AtomicBoolean(true)));

		host.refresh();

		assertThat(memberStreams(host), containsInAnyOrder(streams(fleet, OWN, without(others, others.get(0)))));
	}

	@Example
	public void goesOnWithTheOtherMembersWhenALookupAnswersNothingAtAll() {
		final ChainFleet fleet = new ChainFleet(20);
		final List<String> others = othersInTheGroupOf(fleet, OWN);
		final ChainHost host = fleet.hostOver(OWN, member -> member.equals(others.get(0)) ? null
			: Optional.of(fleet.stream(OWN, member)));

		host.refresh();

		assertThat(memberStreams(host), containsInAnyOrder(streams(fleet, OWN, without(others, others.get(0)))));
	}

	@Example
	public void recoversWhenALookupThrowsAfterTheHostingWasBuilt() {
		final ChainFleet fleet = new ChainFleet(20);
		final List<String> others = othersInTheGroupOf(fleet, OWN);
		final AtomicBoolean failing = new AtomicBoolean();
		final ChainHost host = fleet.hostOver(OWN, failingFor(fleet, others.get(1), failing));

		host.refresh();
		assertThat(memberStreams(host), containsInAnyOrder(streams(fleet, OWN, others)));

		failing.set(true);
		host.refresh();
		assertThat(memberStreams(host), containsInAnyOrder(streams(fleet, OWN, without(others, others.get(1)))));

		failing.set(false);
		host.refresh();
		assertThat(memberStreams(host), containsInAnyOrder(streams(fleet, OWN, others)));
	}

	@Example
	public void logsTheClassOfAFailedLookupAndNothingMoreOfIt() {
		final ChainFleet fleet = new ChainFleet(20);
		final ChainHost host = fleet.hostOver(OWN,
			failingFor(fleet, othersInTheGroupOf(fleet, OWN).get(0), new AtomicBoolean(true)));

		final List<ILoggingEvent> warnings = warningsWhile(host::refresh);

		assertThat(warnings, hasSize(1));
		assertThat(warnings.get(0).getFormattedMessage(), containsString(IllegalStateException.class.getName()));
		assertThat(warnings.get(0).getFormattedMessage(), not(containsString(FAILURE_DETAIL)));
		assertThat(warnings.get(0).getThrowableProxy(), is(nullValue()));
	}

	@Example
	public void handsEveryChainAListNobodyCanChange() {
		final ChainFleet fleet = new ChainFleet(20);
		final ChainHost host = fleet.host(OWN);

		othersInTheGroupOf(fleet, OWN).forEach(fleet::host);
		host.refresh();

		host.hostedChains().forEach(chainId -> assertThrows(UnsupportedOperationException.class,
			() -> host.hosted(chainId).orElseThrow().getMemberStreams().add(new EmbeddedChannel())));
	}

	@Example
	public void leavesAListAReaderHoldsAsItWasWhenTheStreamsChange() {
		final ChainFleet fleet = new ChainFleet(20);
		final List<String> others = othersInTheGroupOf(fleet, OWN);
		final ChainHost host = fleet.host(OWN);

		others.forEach(fleet::host);
		host.refresh();

		final List<Channel> held = memberStreams(host);

		fleet.offline(others.get(0));
		host.refresh();

		assertThat(held, containsInAnyOrder(streams(fleet, OWN, others)));
		assertThat(memberStreams(host), hasSize(others.size() - 1));
	}

	@Example
	public void publishesTheListThroughAVolatileField() throws NoSuchFieldException {
		assertThat(Modifier.isVolatile(HostedChain.class.getDeclaredField("memberStreams").getModifiers()), is(true));
	}

	@Example
	public void startsWithoutAnyStream() {
		final Chain chain = new Chain(new ChainId(new byte[ChainId.SIZE]), ProxySettings.DEFAULTS.chainLimits(), () -> 0L);

		assertThat(new HostedChain(chain, new Epoch(0)).getMemberStreams(), is(empty()));
	}

	@Example
	public void publishesAHostingOnlyOnceItsChainsHoldTheirStreams() {
		final ChainFleet fleet = new ChainFleet(20);
		final AtomicReference<ChainHost> host = new AtomicReference<>();
		final List<List<ChainId>> seenWhileAsking = new ArrayList<>();

		host.set(fleet.hostOver(OWN, member -> {
			seenWhileAsking.add(host.get().hostedChains());
			return Optional.of(fleet.stream(OWN, member));
		}));

		host.get().refresh();

		final List<ChainId> first = host.get().hostedChains();

		assertThat(first, is(not(empty())));
		assertThat(seenWhileAsking, is(not(empty())));
		seenWhileAsking.forEach(seen -> assertThat(seen, is(empty())));

		seenWhileAsking.clear();
		fleet.clock.addAndGet(EPOCH_MILLIS);
		host.get().refresh();

		assertThat(host.get().hostedChains(), is(not(equalTo(first))));
		assertThat(seenWhileAsking, is(not(empty())));
		seenWhileAsking.forEach(seen -> assertThat(seen, equalTo(first)));
	}
}

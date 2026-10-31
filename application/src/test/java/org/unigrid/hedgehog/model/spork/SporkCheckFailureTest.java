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

package org.unigrid.hedgehog.model.spork;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import mockit.Invocation;
import mockit.Mock;
import mockit.MockUp;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeTry;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import org.slf4j.LoggerFactory;
import org.unigrid.hedgehog.model.crypto.ChecksSpentException;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.buildIn;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.key;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.keys;
import static org.unigrid.hedgehog.model.spork.SporkCountingTest.supply;

/* A spork whose signatures cannot be checked is none the network took. What a peer or a file can send is no reason to
   warn, while any other failure of the checks is told of by its class alone. */
public class SporkCheckFailureTest {
	private static final String DETAIL = "detail that stays out of the log";

	@BeforeTry
	public void before() {
		buildIn(keys(0, 1));
	}

	@AfterTry
	public void after() {
		NetworkKey.reset();
	}

	/* A fake outlasts the try that makes it, so the checks of the spork given fail, while those of any other go on */
	private static void failSignerOf(GridSpork spork, Supplier<RuntimeException> failure) {
		final byte[] signature = spork.getSignature();

		new MockUp<NetworkKey>() {
			@Mock public Optional<String> signerOf(Invocation invocation, byte[] digest, byte[] headSignature,
				Collection<String> keys) {

				if (headSignature == signature) {
					throw failure.get();
				}

				return invocation.proceed();
			}
		};
	}

	private static void failLogOf(GridSpork spork, Supplier<RuntimeException> failure) {
		final SignatureLog signatureLog = spork.getSignatureLog();

		new MockUp<SignatureLog>() {
			@Mock public boolean isValidFrom(Invocation invocation, int index, Set<String> keys) {
				if (invocation.getInvokedInstance() == signatureLog) {
					throw failure.get();
				}

				return invocation.proceed();
			}
		};
	}

	@Example
	public void shouldTakeNoSporkThatCannotBeEncodedWithoutAWarning() {
		final GridSpork unknown = supply(key(0), key(1));

		unknown.setType((short) 0);

		assertThat(warningsWhile(() -> {
			assertThat(unknown.isTakenByTheNetwork(), is(false));
			assertThat(unknown.wasTakenByTheNetwork(), is(false));
		}), empty());
	}

	@Example
	public void shouldTakeNoSporkOverAMalformedKeyWithoutAWarning() {
		final GridSpork spork = supply(key(0), key(1));

		NetworkKey.install(NetworkKey.getActiveKeys().after(List.of("not a key")));
		assertThat(warningsWhile(() -> assertThat(spork.isTakenByTheNetwork(), is(false))), empty());
	}

	@Example
	public void shouldTakeNoSporkWhoseChecksAreSpentWithoutAWarning() {
		final GridSpork spork = supply(key(0), key(1));

		failSignerOf(spork, ChecksSpentException::new);
		assertThat(warningsWhile(() -> assertThat(spork.isTakenByTheNetwork(), is(false))), empty());
	}

	@Example
	public void shouldTakeNoSporkWhoseChecksFailedOtherwiseAndWarnOfTheClass() {
		final GridSpork spork = supply(key(0), key(1));

		failLogOf(spork, () -> new IllegalStateException(DETAIL));

		final List<String> warnings = messagesOf(warningsWhile(() ->
			assertThat(spork.isTakenByTheNetwork(), is(false))
		));

		assertThat(warnings, contains(containsString(IllegalStateException.class.getName())));
		assertThat(warnings, contains(not(containsString(DETAIL))));
	}

	private static List<String> messagesOf(List<ILoggingEvent> warnings) {
		return warnings.stream().map(ILoggingEvent::getFormattedMessage).toList();
	}

	/* What the spork warns about while the action runs, read as ChainOutboxTest#warningsWhile reads the outbox's */
	private static List<ILoggingEvent> warningsWhile(Runnable action) {
		SporkCheckFailureTest.class.getModule().addReads(ModuleLayer.boot().findModule("ch.qos.logback.core")
			.orElseThrow());

		final Logger logger = (Logger) LoggerFactory.getLogger(GridSpork.class);
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
}

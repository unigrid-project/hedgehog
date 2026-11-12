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

package org.unigrid.hedgehog.model.cdi;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.ConsoleAppender;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import lombok.extern.slf4j.Slf4j;
import net.jqwik.api.Example;
import org.jboss.weld.environment.se.Weld;
import org.jboss.weld.environment.se.WeldContainer;
import org.jboss.weld.environment.se.events.ContainerInitialized;
import org.slf4j.LoggerFactory;

/* The callbacks that tear the node down run while the container shuts down, after the shutdown event has been fired,
   and what they log to the console is what an operator has left to learn how the stop went. Real container here and
   not the fake of CDIContextTest, as the order of the shutdown is what is under test. */
public class CDIContextShutdownTest {
	private static final String CONTAINER_ID = "cdi-context-shutdown";
	private static final String PRE_DESTROY_LINE = "The last save of the database failed on the stop";
	private static final String HOOK_LINE = "Weld SE container " + CONTAINER_ID + " shut down by shutdown hook";

	@ApplicationScoped
	public static class ShutdownContext extends CDIContext {
		@Override
		protected void start(@Observes ContainerInitialized event) { /* Nothing to start */ }
	}

	@Slf4j
	@ApplicationScoped
	public static class FailingSave {
		public void touch() { /* A bean is only destroyed once something has created it */ }

		@PreDestroy
		private void destroy() {
			log.atError().log(PRE_DESTROY_LINE);
		}
	}

	/* The console appender of logback-core is out of reach of the module descriptor, which reads logback-classic only */
	private static ConsoleAppender<ILoggingEvent> consoleAppender() {
		CDIContextShutdownTest.class.getModule()
			.addReads(ModuleLayer.boot().findModule("ch.qos.logback.core").orElseThrow());

		final Logger logger = (Logger) LoggerFactory.getLogger(FailingSave.class);
		final PatternLayoutEncoder encoder = new PatternLayoutEncoder();

		encoder.setContext(logger.getLoggerContext());
		encoder.setPattern("%msg%n");
		encoder.start();

		final ConsoleAppender<ILoggingEvent> appender = new ConsoleAppender<>();
		appender.setContext(logger.getLoggerContext());
		appender.setEncoder(encoder);
		appender.setTarget("System.out");
		appender.start();

		return appender;
	}

	/* Runs a container with the node and the bean up to its shutdown, and returns what the console got by then and
	   by what the test printed after it, as Weld prints its own line once the container is down */
	private static String consoleOfAShutdown(boolean printWeldLineAfterwards) {
		LoggerFactory.getILoggerFactory(); /* The first use of logging prints its start-up to the console */

		final PrintStream console = System.out;
		final ByteArrayOutputStream captured = new ByteArrayOutputStream();
		final Logger logger = (Logger) LoggerFactory.getLogger(FailingSave.class);
		final ConsoleAppender<ILoggingEvent> appender = consoleAppender();

		System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
		logger.addAppender(appender);

		try {
			final WeldContainer container = new Weld(CONTAINER_ID).disableDiscovery().skipShutdownHook()
				.beanClasses(ShutdownContext.class, FailingSave.class).initialize();

			container.select(FailingSave.class).get().touch();
			container.shutdown();

			if (printWeldLineAfterwards) {
				System.out.println(HOOK_LINE);
			}
		} finally {
			logger.detachAppender(appender);
			System.setOut(console);
		}

		return captured.toString(StandardCharsets.UTF_8);
	}

	@Example
	public void shouldLogWhatTheBeansDoWhenTheyAreDestroyed() {
		assertThat("The console got the line of the callback that ran on the stop", consoleOfAShutdown(false),
			containsString(PRE_DESTROY_LINE));
	}

	/* The line is printed by Weld on the console and not logged, and it is all that the shutdown event is for */
	@Example
	public void shouldNotShowTheLineWeldPrintsWhenTheShutdownHookHasShutTheContainer() {
		assertThat(consoleOfAShutdown(true), not(containsString(HOOK_LINE)));
	}
}

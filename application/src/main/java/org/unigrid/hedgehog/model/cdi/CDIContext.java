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

import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import java.io.PrintStream;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.jboss.weld.environment.se.events.ContainerShutdown;
import org.jboss.weld.environment.se.events.ContainerInitialized;

@Slf4j
public abstract class CDIContext implements Runnable {
	private static final Object MONITOR = new Object();
	private static final String WELD_PREFIX = "Weld SE container ";
	private static final String WELD_SUFFIX = " shut down by shutdown hook";

	/* Guarded by MONITOR. A stop is remembered, as the REST server answers one before run() gets to wait for it. */
	private static boolean stopped;

	/* Gets rid of "Weld SE container {uuId} shut down by shutdown hook", which Weld prints on the console once the
	   container is down. The callbacks that tear the node down run after this, and what they log goes to the console
	   as well, so every other line is passed on. */
	private void shutdown(@Observes ContainerShutdown event) {
		final PrintStream console = System.out;

		System.setOut(new PrintStream(console, true, console.charset()) {
			@Override
			public void println(String line) {
				if (!isShutDownByHook(line)) {
					super.println(line);
				}
			}
		});
	}

	private static boolean isShutDownByHook(String line) {
		return Objects.nonNull(line) && line.startsWith(WELD_PREFIX) && line.endsWith(WELD_SUFFIX);
	}

	@Override
	public void run() {
		synchronized (MONITOR) {
			stopped = false;
		}

		SeContainerInitializer.newInstance().addExtensions(new EagerExtension()).initialize();

		synchronized (MONITOR) {
			try {
				/* A wakeup is no stop unless one was asked for */
				while (!stopped) {
					MONITOR.wait();
				}
			} catch (InterruptedException e) {
				log.atDebug().log("Received singal to exit");
			}
		}
	}

	public static void stop() {
		synchronized (MONITOR) {
			stopped = true;
			MONITOR.notifyAll();
		}
	}

	protected abstract void start(@Observes ContainerInitialized event);
}

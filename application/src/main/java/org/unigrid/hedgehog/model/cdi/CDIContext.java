/*
    Unigrid Hedgehog
    Copyright © 2021-2023 Stiftelsen The Unigrid Foundation, UGD Software AB

    Stiftelsen The Unigrid Foundation (org. nr: 802482-2408)
    UGD Software AB (org. nr: 559339-5824)

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
import java.io.OutputStream;
import java.io.PrintStream;
import lombok.extern.slf4j.Slf4j;
import org.jboss.weld.environment.se.events.ContainerShutdown;
import org.jboss.weld.environment.se.events.ContainerInitialized;

@Slf4j
public abstract class CDIContext implements Runnable {
	private static final Object MONITOR = new Object();

	private void shutdown(@Observes ContainerShutdown event) {
		/* Gets rid of "Weld SE container {uuId} shut down by shutdown hook" */
		System.setOut(new PrintStream(new OutputStream() {
			@Override
			public void write(int b) { /* Do nothing  */ }
		}));
	}

	@Override
	public void run() {
		SeContainerInitializer.newInstance().addExtensions(new EagerExtension()).initialize();

		synchronized (MONITOR) {
			try {
				MONITOR.wait();
			} catch (InterruptedException e) {
				log.atDebug().log("Received singal to exit");
			}
		}
	}

	public static void stop() {
		synchronized (MONITOR) {
			MONITOR.notifyAll();
		}
	}

	protected abstract void start(@Observes ContainerInitialized event);
}

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
	import org.jboss.weld.environment.se.events.ContainerInitialized;
	import org.jboss.weld.environment.se.events.ContainerShutdown;
	
	/**
	 * Abstrakt klass som hanterar CDI-container.
	 * 
	 * Innehåller metod för att starta container och observera ContainerInitialized.
	 */
	public abstract class CDIContext implements Runnable {
	
		private static final Object MONITOR = new Object();
	
		@Override
		public void run() {
			// Starta Weld SE container med EagerExtension
			SeContainerInitializer.newInstance()
					.addExtensions(new EagerExtension())
					.initialize();
	
			// Vänta tills stop() anropas
			synchronized (MONITOR) {
				try {
					MONITOR.wait();
				} catch (InterruptedException e) {
					System.out.println("Received signal to exit");
				}
			}
		}
	
		/**
		 * Observer-metod som måste implementeras av subklass
		 * körs när container initierats
		 */
		protected abstract void onStart(@Observes ContainerInitialized event);
	
		/**
		 * Stoppar programmet / notifies run()
		 */
		public static void stop() {
			synchronized (MONITOR) {
				MONITOR.notifyAll();
			}
		}
	
		/**
		 * För att tysta Weld shutdown-meddelanden
		 */
		protected void shutdown(@Observes ContainerShutdown event) {
			System.setOut(new java.io.PrintStream(new java.io.OutputStream() {
				@Override
				public void write(int b) { /* Do nothing */ }
			}));
		}
	}
	
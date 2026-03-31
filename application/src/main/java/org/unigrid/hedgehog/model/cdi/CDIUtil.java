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

	import jakarta.enterprise.inject.Instance;
	import jakarta.enterprise.inject.spi.CDI;
	import org.jboss.weld.interceptor.util.proxy.TargetInstanceProxy;
	
	import java.util.function.Consumer;
	import java.util.logging.Level;
	import java.util.logging.Logger;
	
	/**
	 * Hjälpmetoder för CDI i CLI / Netty / Weld SE-miljö.
	 */
	public final class CDIUtil {
	
		private static final Logger LOG = Logger.getLogger(CDIUtil.class.getName());
	
		private CDIUtil() {
			/* Utility class */
		}
	
		/**
		 * Tvingar CDI att instansiera en proxad instans.
		 * Används främst för eager-init i Weld SE.
		 */
		public static void instantiate(Object proxy) {
			if (proxy != null) {
				proxy.toString(); // Triggar proxy-instansiering
			}
		}
	
		/**
		 * Tar bort Weld-proxy och returnerar den faktiska instansen.
		 *
		 * @throws IllegalArgumentException om objektet inte är en Weld-proxy
		 */
		@SuppressWarnings("unchecked")
		public static <T> T unproxy(T proxy) {
			if (proxy instanceof TargetInstanceProxy tip) {
				return (T) tip.weld_getTargetInstance();
			}
	
			throw new IllegalArgumentException(
					"Object is not a Weld proxy: " + proxy.getClass()
			);
		}
	
		/**
		 * Resolvar en CDI-bean och kör consumer om den finns.
		 */
		public static <T> void resolveAndRun(Class<T> clazz, Consumer<T> consumer) {
			CDI<Object> cdi;
	
			try {
				cdi = CDI.current();
			} catch (IllegalStateException e) {
				LOG.log(Level.WARNING, "CDI container not available");
				return;
			}
	
			Instance<T> instance = cdi.select(clazz);
	
			if (instance.isResolvable()) {
				consumer.accept(instance.get());
			} else {
				LOG.log(Level.WARNING, "Unable to resolve CDI bean: {0}", clazz.getName());
			}
		}
	}
	
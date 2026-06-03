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

package org.unigrid.hedgehog.model.util;

import lombok.extern.slf4j.Slf4j;
import org.unigrid.hedgehog.model.function.VoidFunctionE;

@Slf4j
public class ExceptionUtil {
	/**
	* Executes a functional callback (VoidFunctionE) and swallows the exceptions. This allows us to execute
	* functional callbacks that throw checked exceptions (something Java does not allow by default). Any
	* swallowed exceptions are logged at trace level.
	*
	* @param  function   A functional callback of type VoidFunctionE (takes no arguments and returns void)
	* @param  exceptions A varags list of all the exceptions that we should swallow. Any thrown checked exceptions not
	*                    swallowed will be rethrown as runtime exceptions.
	*/
	public static <E extends Exception> void swallow(VoidFunctionE function,
		Class<? extends Exception>... exceptions) throws E {

		try {
			function.apply();
		} catch (Exception ex) {
			for (Class<?> e : exceptions) {
				if (e.isInstance(ex)) {
					log.atTrace().log("Swallowed exception {}", ex.getMessage());
					return;
				}
			}

			throw (E) ex;
		}
	}
}

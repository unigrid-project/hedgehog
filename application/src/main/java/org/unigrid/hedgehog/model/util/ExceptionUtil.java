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

import java.util.logging.Level;
import java.util.logging.Logger;
import org.unigrid.hedgehog.model.function.VoidFunctionE;

public class ExceptionUtil {

    private static final Logger log = Logger.getLogger(ExceptionUtil.class.getName());

    public static <E extends Exception> void swallow(VoidFunctionE function,
                                                     Class<? extends Exception>... exceptions) throws E {
        try {
            function.apply();
        } catch (Exception ex) {
            for (Class<?> e : exceptions) {
                if (e.isInstance(ex)) {
                    log.log(Level.FINE, "Swallowed exception: {0}", ex.getMessage());
                    return;
                }
            }
            throw (E) ex;
        }
    }
}
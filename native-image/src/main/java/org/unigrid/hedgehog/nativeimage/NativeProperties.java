/*
    Unigrid Hedgehog
    Copyright © 2021-2023 The Unigrid Foundation, UGD Software AB

    This program is free software: you can redistribute it and/or modify it under the terms of the
    addended GNU Affero General Public License as published by the The Unigrid Foundation and
    the Free Software Foundation, version 3 of the License (see COPYING and COPYING.addendum).

    This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
    even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU Affero General Public License and the addendum for more details.

    You should have received an addended copy of the GNU Affero General Public License with this program.
    If not, see <http://www.gnu.org/licenses/> and <https://github.com/unigrid-project/hedgehog>.
 */

package org.unigrid.hedgehog.nativeimage;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class NativeProperties {
    public static final String BIN_DIRECTORY = "bin";
    private static String hash;

    public static String getHash() {
        if (hash == null) {
            try (InputStream is = NativeProperties.class.getClassLoader().getResourceAsStream("hash.txt")) {
                hash = (is != null) ? new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8)).readLine() : "default-hash";
            } catch (Exception e) { hash = "default-hash"; }
        }
        return hash;
    }

    public static String getRunScript() { 
        return System.getProperty("os.name").toLowerCase().contains("win") ? "run.cmd" : "run.sh"; 
    }
}








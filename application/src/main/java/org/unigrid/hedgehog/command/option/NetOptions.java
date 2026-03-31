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
 // ======================================
// File: org/unigrid/hedgehog/command/option/NetOptions.java
// ======================================

package org.unigrid.hedgehog.command.option;

import picocli.CommandLine;
import picocli.CommandLine.Option;

/**
 * CLI-options för P2P-servern (statisk access för Network/Topology)
 */
public class NetOptions {

    private static final String DEFAULT_PORT_STR = "52883";

    private static String host = "localhost";
    private static int port = Integer.parseInt(DEFAULT_PORT_STR);
    private static boolean seeds = true;

    // ===== Picocli setter options =====
    @Option(
        names = {"-H", "--nethost"},
        scope = CommandLine.ScopeType.INHERIT,
        description = "Hostname or IP to bind to (default: ${DEFAULT-VALUE})",
        defaultValue = "localhost"
    )
    public void setHost(String value) {
        host = value;
    }

    @Option(
        names = {"-p", "--netport"},
        scope = CommandLine.ScopeType.INHERIT,
        description = "Network port (default: ${DEFAULT-VALUE})",
        defaultValue = DEFAULT_PORT_STR
    )
    public void setPort(int value) {
        port = value;
    }

    @Option(
        names = "--no-seeds",
        scope = CommandLine.ScopeType.INHERIT,
        description = "Enable/disable seed nodes (enabled by default)",
        negatable = true,
        defaultValue = "true"
    )
    public void setSeeds(boolean value) {
        seeds = value;
    }

    // ===== Statisk getters och setters =====
    public static String getHost() {
        return host;
    }

    public static void setHostStatic(String h) {
        host = h;
    }

    public static int getPort() {
        return port;
    }

    public static void setPortStatic(int p) {
        port = p;
    }

    public static boolean isSeeds() {
        return seeds;
    }

    public static void setSeedsStatic(boolean s) {
        seeds = s;
    }
}
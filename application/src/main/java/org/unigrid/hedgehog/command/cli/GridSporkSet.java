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

 package org.unigrid.hedgehog.command.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * CLI-kommando för att uppdatera mint-supply-parametrar.
 * Data och privateKey hålls som statiska fält så de kan nås från MintSupply.
 */
@Command(
        name = "spork-set",
        description = "Set grid spork data and private key"
)
public class GridSporkSet implements Runnable {

    /* ====== CLI OPTIONS ====== */

    @Option(
            names = {"-d", "--data"},
            required = true,
            description = "Spork data payload"
    )
    private String data;

    @Option(
            names = {"-k", "--key"},
            required = true,
            description = "Private key used for signing"
    )
    private String key;

    /* ====== STATIC STORAGE ====== */

    private static String storedData;
    private static String storedKey;

    @Override
    public void run() {
        storedData = data;
        storedKey = key;

        System.out.println("Spork data and key stored successfully.");
    }

    /* ====== STATIC ACCESSORS ====== */

    public static String getData() {
        return storedData;
    }

    public static String getKey() {
        return storedKey;
    }
}
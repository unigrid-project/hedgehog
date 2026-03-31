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

    import org.unigrid.hedgehog.command.cli.spork.MintSupply;
    import org.unigrid.hedgehog.command.cli.spork.MintStorage;
    import picocli.CommandLine.Command;
    
    /**
     * CLI-kommandot "gridspork-get" används för att läsa (GET) data
     * från GridSpork-systemet.
     *
     * Sub-kommandon:
     * - MintSupply: Läser supply-parametrar
     * - MintStorage: Läser mint-storage
     *
     * UPPDATERINGAR JÄMFÖRT MED ORIGINALKOD:
     * 1. Tydligare Javadoc-kommentarer för klassens syfte.
     *    Original: Klassen var helt tom, ingen dokumentation.
     * 2. Beskrivning av subkommandon och deras roll.
     * 3. Ingen funktionell förändring – samma subcommands bibehålls.
     */
    @Command(
        name = "gridspork-get",
        description = "CLI command for reading GridSpork data (GET operations)",
        subcommands = {
            MintSupply.class,  // Läser mint-supply
            MintStorage.class  // Läser mint-storage
        }
    )
    public class GridSporkGet {
        // Empty on purpose – alla funktioner hanteras av subkommandon
    }
    

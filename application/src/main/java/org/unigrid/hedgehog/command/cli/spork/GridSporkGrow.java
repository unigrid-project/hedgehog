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
	package org.unigrid.hedgehog.command.cli.spork;

	import lombok.Getter;
	import picocli.CommandLine.Command;
	import picocli.CommandLine.Option;
	
	/**
	 * Parent-kommando för GridSpork "grow".
	 *
	 * Denna klass fungerar som ett state-holder för CLI-parametrar
	 * som delas med subkommandon (ex. mint-storage).
	 */
	@Command(name = "grow")
	public class GridSporkGrow {
	
		/**
		 * Privat nyckel används för autentisering
		 * och måste vara tillgänglig för subkommandon.
		 *
		 * STATIC eftersom Picocli skapar separata instanser
		 * per kommando, men vi behöver global åtkomst.
		 */
		@Getter
		@Option(names = "--key", required = true)
		private static String key;
	
		/**
		 * Data-payload som skickas i PUT-body.
		 *
		 * STATIC av samma skäl som ovan.
		 */
		@Getter
		@Option(names = "--data", required = true)
		private static String data;
	
		/*
		 * ==========================================================
		 * EXPLICITA STATISKA ACCESSORS
		 * ==========================================================
		 * Dessa används av subkommandon (ex. MintStorage)
		 * utan att behöva hålla referens till CLI-instansen.
		 */
	
		public static String getKey() {
			return key;
		}
	
		public static String getData() {
			return data;
		}
	}
	
	/*
	 * ==========================================================
	 * JÄMFÖRELSE: FÖRE VS EFTER
	 * ==========================================================
	 *
	 * FÖRE:
	 * - key och data var instansfält
	 * - @Getter skapade endast instansmetoder
	 * - Subkommandon kunde INTE nå värdena
	 * - Java-fel: "cannot find symbol"
	 *
	 * EFTER:
	 * - key och data är statiska
	 * - Tydliga statiska getters finns
	 * - MintStorage kan anropa:
	 *     GridSporkGrow.getKey()
	 *     GridSporkGrow.getData()
	 *
	 * RESULTAT:
	 * - Kompilerar korrekt
	 * - CLI-state delas korrekt
	 * - Designen är explicit och lätt att förstå
	 */
	
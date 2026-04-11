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

import java.nio.file.Path;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class NativeProperties {

	public static final String PROP_KEY = "hedgehog.native";

	public static final String BIN_DIRECTORY = "bin";

	@Getter @Setter
	private static Path bundledJlinkZip;

	@Getter @Setter
	private static String hash;

	private static boolean initialized = false;

	static {
		try {
			String val = System.getProperty(PROP_KEY);
			initialized = (val != null);
		} catch (Exception e) {
			log.error("Error initializing native properties", e);
		}
	}

	public static boolean isInitialized() {
		return initialized;
	}
}

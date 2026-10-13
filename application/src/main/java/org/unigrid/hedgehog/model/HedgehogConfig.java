/*
    Unigrid Hedgehog
    Copyright © 2021-2026 Stiftelsen The Unigrid Foundation

    Stiftelsen The Unigrid Foundation (org. nr: 802482-2408)

    This program is free software: you can redistribute it and/or modify it under the terms of the
    addended GNU Affero General Public License as published by the The Unigrid Foundation and
    the Free Software Foundation, version 3 of the License (see COPYING and COPYING.addendum).

    This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
    even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU Affero General Public License and the addendum for more details.

    You should have received an addended copy of the GNU Affero General Public License with this program.
    If not, see <http://www.gnu.org/licenses/> and <https://github.com/unigrid-project/hedgehog>.
 */

package org.unigrid.hedgehog.model;

import com.sun.jna.platform.win32.KnownFolders;
import com.sun.jna.platform.win32.Shell32Util;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.Properties;
import lombok.Data;
import lombok.SneakyThrows;
import org.apache.commons.lang3.SystemUtils;

@Data
public class HedgehogConfig {

	private static final String CONFIG_FILE = "hedgehog.conf";
	private static final String APPLICATION_NAME = "UNIGRID";
	private static final String OSX_SUPPORT_DIR = "Library/Application Support";
	private static final String ALPHANUMERIC_CHARACTERS =
		"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

	private String username;
	private String password;

	public HedgehogConfig() {
		getConfig();
	}

	@SneakyThrows
	private void createConfig(File file) {
		OutputStream fileOutput = new FileOutputStream(file);

		Properties prop = new Properties();
		prop.put("username", generateRandomString());
		prop.put("password", generateRandomString());
		prop.store(fileOutput, CONFIG_FILE);
	}

	@SneakyThrows
	private void getConfig() {
		File file = getConfigFile();

		if (!file.exists()) {
			createConfig(file);
		}

		FileInputStream propsInput = new FileInputStream(file.getAbsolutePath());
		Properties prop = new Properties();
		prop.load(propsInput);
		setUsername(prop.getProperty("username"));
		setPassword(prop.getProperty("password"));
	}

	private String getConfigPath() {
		String head;
		String tail;
		if (SystemUtils.IS_OS_WINDOWS) {
			head = Shell32Util.getKnownFolderPath(KnownFolders.FOLDERID_RoamingAppData);
			tail = APPLICATION_NAME;
			System.out.println("OS is windows");
		} else {
			head = SystemUtils.getUserHome().getAbsolutePath();

			if (SystemUtils.IS_OS_MAC_OSX) {
				head = Paths.get(head, OSX_SUPPORT_DIR).toString();
				tail = APPLICATION_NAME;
			} else {
				tail = Paths.get(".".concat(APPLICATION_NAME).toLowerCase()).toString();
			}
		}

		return Paths.get(head, tail).toString();
	}

	private File getConfigFile() {
		return Paths.get(getConfigPath(), CONFIG_FILE).toFile();
	}

	private String generateRandomString() {
		int passwordLength = 15;
		StringBuilder sb = new StringBuilder(passwordLength);
		SecureRandom random = new SecureRandom();
		for (int i = 0; i < passwordLength; i++) {
			int index = random.nextInt(ALPHANUMERIC_CHARACTERS.length());
			sb.append(ALPHANUMERIC_CHARACTERS.charAt(index));
		}
		return sb.toString();
	}
}

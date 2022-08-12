/*
    Unigrid Hedgehog
    Copyright © 2021-2022 The Unigrid Foundation, UGD Software AB

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

public class Network {
	public static final int VERSION = 100100;

	public static final String[] PROTOCOLS = {
		"hedgehog/0.0.1",
		"gridspork/0.0.1"
	};

	public static final String[] SEEDS = {
		"localhost"
	};

	public static final int MAX_DATA_SIZE = 1024 * 1024 * 8;
	public static final int MAX_STREAMS = 64;

	public static final String[] KEYS = {
		"e95e2cc06797f92078786706855f7c9ef3004078289d74abe25acca7acfc6871f2b60c452ae74d7eda8113e2"
		+ "9dad86b1766e3eb04d49b39a87b3188e0738df00f41fcedee7231a2f4dd8235fdfd460c983e796ea6e3849"
		+ "a2c7830aac39252700f5f05c7d85bb746bd5f14862eb3056cedddafa446a50fcd8e1e3087e4a65b6c6f82bb"
	};
}

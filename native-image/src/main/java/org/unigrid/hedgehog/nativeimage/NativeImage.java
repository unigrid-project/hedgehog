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

import java.io.File;
import org.unigrid.hedgehog.common.model.Version;

public class NativeImage {
	public static void main(String[] args) throws Exception {
		System.out.println(NativeImage.class.getResource("/"));
		System.out.println(NativeImage.class.getResource("hedgehog-native-0.0.1-SNAPSHOT-jlink.zip"));
		System.out.println(Thread.currentThread().getContextClassLoader().getResource("/"));
		System.out.println(Thread.currentThread().getContextClassLoader().getResource("."));
		System.out.println(Thread.currentThread().getContextClassLoader().getResource("hedgehog-native-0.0.1-SNAPSHOT-jlink.zip"));
		System.out.println(NativeProperties.BUNDLED_JLINK_ZIP);
		System.out.println(NativeProperties.HASH);

		System.out.println(new File("resource:" + NativeProperties.BUNDLED_JLINK_ZIP).exists());
		System.out.println(new File("" + NativeProperties.BUNDLED_JLINK_ZIP).exists());

		System.out.println("name: " + Version.getName());
		System.out.println("author: " + Version.getAuthor());
	}
}

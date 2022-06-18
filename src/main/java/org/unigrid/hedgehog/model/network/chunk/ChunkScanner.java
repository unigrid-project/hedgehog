/*
    Unigrid Hedgehog 
    Copyright © 2021-2022 The Unigrid Foundation

    This program is free software: you can redistribute it and/or modify it under the terms of the
    addended GNU Affero General Public License as published by the The Unigrid Foundation and
    the Free Software Foundation, version 3 of the License (see COPYING and COPYING.addendum).

    This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
    even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU Affero General Public License and the addendum for more details.

    You should have received an addended copy of the GNU Affero General Public License with this program.
    If not, see <http://www.gnu.org/licenses/> and <https://github.com/unigrid-project/hedgehog>.
 */

package org.unigrid.hedgehog.model.network.chunk;

import java.util.Set;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.reflections.Reflections;
import org.unigrid.hedgehog.model.network.codec.chunk.GridSporkDecoder;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class ChunkScanner {
	public static void scan() {
		final String packageName = GridSporkDecoder.class.getPackageName();
		final Set<Class<?>> classes = new Reflections(packageName).getTypesAnnotatedWith(Chunk.class);

		classes.forEach((clazz) -> {
			System.out.println(clazz);
		});
	}
}

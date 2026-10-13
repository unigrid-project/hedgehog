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
package org.unigrid.hedgehog.model.spork;

import com.fasterxml.jackson.core.JsonProcessingException;
import java.util.logging.Level;
import java.util.logging.Logger;
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import org.apache.commons.lang3.RandomUtils;
import org.unigrid.hedgehog.jqwik.TestFileOutput;

public class MinimumVersionTest {

	@Example
	//@SneakyThrows
	public void shouldSerialize() {
		MinimumVersionSpork minVerSpork = new MinimumVersionSpork();
		MinimumVersionSpork.SporkData data = new MinimumVersionSpork.SporkData();

		minVerSpork.setSignature(RandomUtils.nextBytes(16));
		minVerSpork.setData(data);
		data.setMinimumVersion("1.1.1");
		data.setMinimumHedgehogProtocoll("1.1.1");
		data.setMinimumGridsporkProtocoll("1.1.1");
		
		try {
			TestFileOutput.outputJson(minVerSpork);
		} catch (JsonProcessingException ex) {
			System.out.println(ex.getMessage());
			Logger.getLogger(MinimumVersionTest.class.getName()).log(Level.SEVERE, null, ex);
		}
	}
}

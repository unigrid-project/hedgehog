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

package org.unigrid.hedgehog.model.spork;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import org.unigrid.hedgehog.model.network.util.*;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import lombok.SneakyThrows;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.NotBlank;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import org.unigrid.hedgehog.model.Address;
import net.jqwik.api.Example;

public class MintStorageTest {
	
	@Example
	@SneakyThrows
	public void shouldSerialize() {
		MintStorage mint = new MintStorage();
		
		MintStorage.SporkData data = new MintStorage.SporkData();
		
		mint.setData(data);
		
		Map<MintStorage.SporkData.Location, BigDecimal> mints = new HashMap<>();
		
		data.setMints(mints);
		
		for (int i = 0; i < 10; i++) {
			mints.put(new MintStorage.SporkData.Location(new Address("osidwahfoöuesih" + i), 1000 * i * 42)
				, new BigDecimal(i));
	
		}
		
		ObjectMapper mapper = new ObjectMapper();
		System.out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(mint));
		
		//System.out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(mints));
	}
}

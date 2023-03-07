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

package org.unigrid.hedgehog.model.spork;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import lombok.SneakyThrows;
import org.unigrid.hedgehog.model.Address;
import net.jqwik.api.Example;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.RandomUtils;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import org.unigrid.hedgehog.jqwik.TestFileOutput;

public class MintStorageTest {
	@Example
	@SneakyThrows
	public void shouldSerialize() {
		final MintStorage storage = new MintStorage();
		final MintStorage.SporkData data = new MintStorage.SporkData();
		final Map<MintStorage.SporkData.Location, BigDecimal> mints = new HashMap<>();

		storage.setSignature(RandomUtils.nextBytes(16));
		storage.setData(data);
		data.setMints(mints);

		for (int i = 0; i < 10; i++) {
			final Address address = new Address(RandomStringUtils.randomAlphabetic(32) + i);
			mints.put(new MintStorage.SporkData.Location(address, 1000 * i * 42), new BigDecimal(i));
		}

		final ObjectMapper mapper = new ObjectMapper();
		final String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(storage);

		/* An assertion failure down here should not really be able to happen. When JSON processing fails, it will
		   usually output a JsonProcessingException rather than returning null. */

		assertThat(json, notNullValue());
		TestFileOutput.output(json);
	}
}

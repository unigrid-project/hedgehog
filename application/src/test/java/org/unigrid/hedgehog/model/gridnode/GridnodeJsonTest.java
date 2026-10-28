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

package org.unigrid.hedgehog.model.gridnode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HexFormat;
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import org.unigrid.hedgehog.model.crypto.Signature;

public class GridnodeJsonTest {
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static Gridnode.GridnodeBuilder gridnode() {
		return Gridnode.builder().id("ab").status(Gridnode.Status.ACTIVE).hostName("h:1").timestamp(7);
	}

	@Example
	@SneakyThrows
	public void writesTheSealingKeyAsHexadecimalCharacters() {
		final JsonNode json = MAPPER.readTree(MAPPER.writeValueAsString(
			gridnode().sealingKey(GridnodeFixtures.sealingKey()).build()));

		assertThat(json.get("sealingKey").asText(), equalTo(HexFormat.of().formatHex(GridnodeFixtures.sealingKey())));
	}

	@Example
	@SneakyThrows
	public void readsTheSealingKeyBackFromItsHexadecimalCharacters() {
		final Gridnode read = MAPPER.readValue(MAPPER.writeValueAsString(
			gridnode().sealingKey(GridnodeFixtures.sealingKey()).build()), Gridnode.class);

		assertThat(read.getSealingKey(), is(GridnodeFixtures.sealingKey()));
		assertThat(read.getId(), equalTo("ab"));
	}

	@Example
	@SneakyThrows
	public void keepsAnEntryWithoutASealingKeyWithout() {
		final Gridnode read = MAPPER.readValue(MAPPER.writeValueAsString(gridnode().build()), Gridnode.class);

		assertThat(read.getSealingKey(), is(nullValue()));
	}

	@Example
	@SneakyThrows
	public void neverWritesTheSignature() {
		final Gridnode signed = GridnodeFixtures.signed(new Signature(), Gridnode.Status.ACTIVE, "h:1", 7);

		assertThat(MAPPER.readTree(MAPPER.writeValueAsString(signed)).has("signature"), is(false));
	}
}

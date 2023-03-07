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

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.KeyDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdKeySerializers;
import java.io.IOException;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.unigrid.hedgehog.model.Address;
import org.unigrid.hedgehog.model.network.chunk.ChunkData;

@Data @ToString(callSuper = true)
@EqualsAndHashCode(callSuper = false)
public class MintStorage extends GridSpork implements Serializable {
	public MintStorage() {
		setType(Type.MINT_STORAGE);
	}

	@Data
	public static class SporkData implements ChunkData {
		@JsonSerialize(keyUsing = Location.Serializer.class)
		@JsonDeserialize(keyUsing = Location.Deserializer.class)
		private Map<Location, BigDecimal> mints;

		@Data @Builder @AllArgsConstructor @NoArgsConstructor
		public static class Location implements Serializable {
			private Address address;
			private int height;

			public static class Deserializer extends KeyDeserializer {
				@Override
				public Location deserializeKey(String key, DeserializationContext ctxt) throws IOException {
					final String[] compoundKey = key.split("/");

					return new Location(new Address(compoundKey[0]),
						Integer.parseInt(compoundKey[1])
					);
				}
			}

			public static class Serializer extends StdKeySerializers.StringKeySerializer {
				@Override
				public void serialize(Object value, JsonGenerator generator, SerializerProvider provider)
					throws IOException {

					final Location location = (Location) value;
					generator.writeFieldName(location.address.getWif() + "/" + location.height);
				}
			}
		}
	}
}

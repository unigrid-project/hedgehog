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

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.KeyDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdKeySerializers;
import java.io.IOException;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.apache.commons.lang3.Validate;
import org.unigrid.hedgehog.model.Address;

@Data @ToString(callSuper = true)
@EqualsAndHashCode(callSuper = false)
public class MintStorage extends GridSpork implements Serializable {
	private static final long serialVersionUID = 3170149889466677164L;

	public MintStorage() {
		setType(Type.MINT_STORAGE);

		final MintStorage.SporkData data = new MintStorage.SporkData();
		data.setMints(new HashMap<>());
		setData(data);
	}

	@Data
	public static class SporkData implements ChunkData {
		private static final long serialVersionUID = 4256094579914357173L;

		/* Far above the few hundred mints of the network, and far below what the three-byte count on the wire
		   allows, so a peer cannot make a node hold millions of entries before a signature is checked */
		public static final int MAX_ENTRIES = 16384;

		@JsonSerialize(keyUsing = Location.Serializer.class)
		@JsonDeserialize(keyUsing = Location.Deserializer.class)
		private Map<Location, BigDecimal> mints;

		@Data @Builder @AllArgsConstructor @NoArgsConstructor
		public static class Location implements Serializable, Comparable<Location> {
			public static final long MAXIMUM_NUMBER = 0xFFFF_FFFFL;
			private static final long serialVersionUID = -727370077924808425L;
			private static final Comparator<Location> ORDER = Comparator.comparing(Location::getAddress,
				Comparator.nullsFirst(Comparator.naturalOrder())).thenComparingInt(Location::getHeight);

			private Address address;

			/* Thirty-two bits read unsigned, as a height or a time (see MintEntry) */
			private int height;

			@Override
			public int compareTo(Location other) {
				return ORDER.compare(this, other);
			}

			public static class Deserializer extends KeyDeserializer {
				private static final Pattern NUMBER = Pattern.compile("[0-9]{1,10}");
				private static final String REASON = "it has to be an address, a slash and a number from 0 "
					+ "to " + MAXIMUM_NUMBER + " without a sign";

				@Override
				public Location deserializeKey(String key, DeserializationContext ctxt) throws IOException {
					final int slash = key.lastIndexOf('/');

					if (slash < 0 || !NUMBER.matcher(key.substring(slash + 1)).matches()) {
						throw refused(key, ctxt);
					}

					try {
						return new Location(new Address(key.substring(0, slash)),
							Integer.parseUnsignedInt(key.substring(slash + 1)));
					} catch (NumberFormatException ex) {
						throw refused(key, ctxt);
					}
				}

				private static JsonMappingException refused(String key, DeserializationContext ctxt) {
					return ctxt.weirdKeyException(Location.class, key, REASON);
				}
			}

			public static class Serializer extends StdKeySerializers.StringKeySerializer {
				@Override
				public void serialize(Object value, JsonGenerator generator, SerializerProvider provider)
					throws IOException {

					final Location location = (Location) value;
					generator.writeFieldName(location.address.getWif() + "/"
						+ Integer.toUnsignedString(location.height));
				}
			}
		}

		public SporkData empty() {
			final SporkData data = new SporkData();

			data.setMints(new HashMap<>());
			return data;
		}

		public void validate() {
			Validate.isTrue(Objects.nonNull(mints), "mints is required");
			Validate.isTrue(mints.size() <= MAX_ENTRIES, "mints holds at most %d entries", MAX_ENTRIES);

			Validate.isTrue(mints.keySet().stream().map(Location::getAddress).allMatch(Address::isWithinBounds),
				"Every address of mints has to be at most %d bytes", Address.MAX_LENGTH);
		}
	}
}

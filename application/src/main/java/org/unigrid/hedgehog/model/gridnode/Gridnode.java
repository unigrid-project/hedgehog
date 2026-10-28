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

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.util.StdConverter;
import java.util.HexFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Gridnode {

	@AllArgsConstructor
	public enum Status {
		ACTIVE((byte) 1), INACTIVE((byte) 2);

		@Getter
		private final byte value;

		public static Status get(byte value) {
			switch (value) {
				case 1:
					return ACTIVE;
				case 2:
					return INACTIVE;
				default:
					return INACTIVE;
			}
		}
	}

	/* The same hexadecimal characters as the key files hold, instead of the base64 that Jackson makes of bytes */
	public static class HexWriter extends StdConverter<byte[], String> {
		@Override
		public String convert(byte[] bytes) {
			return HexFormat.of().formatHex(bytes);
		}
	}

	public static class HexReader extends StdConverter<String, byte[]> {
		@Override
		public byte[] convert(String hex) {
			return HexFormat.of().parseHex(hex);
		}
	}

	@Builder.Default
	private Status status = Status.INACTIVE;
	@EqualsAndHashCode.Include
	private String id;
	private String hostName;
	/* The public key of X25519 that a client seals a session to this gridnode with, signed with the rest */
	@JsonSerialize(converter = HexWriter.class)
	@JsonDeserialize(converter = HexReader.class)
	@ToString.Exclude
	private byte[] sealingKey;
	private long timestamp;
	@JsonIgnore
	@ToString.Exclude
	private byte[] signature;
}

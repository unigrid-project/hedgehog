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

import com.fasterxml.jackson.annotation.JsonFormat;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Date;
import java.util.HashMap;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.unigrid.hedgehog.model.network.chunk.ChunkData;

@Data @ToString(callSuper = true)
@EqualsAndHashCode(callSuper = false)
public class Cosmos extends GridSpork implements Serializable {
	public Cosmos() {
		setType(Type.COSMOS);
		final Cosmos.SporkData data = new Cosmos.SporkData();

		data.setParameters(new HashMap<>());
		setData(data);
	}

	@Data
	public static class Coins {
		private Coin[] coin;
	}

	@Data
	public static class Coin {
		private String denom;
		private int amount;
	}

	@Data @Builder @AllArgsConstructor @NoArgsConstructor
	public static class SporkData implements ChunkData {
		private HashMap<String, Object> parameters;

		public SporkData empty() {
			final SporkData data = new SporkData();

			data.setParameters(new HashMap<>());
			return data;
		}

		@Data
		public static class Genesis {
			@JsonFormat(shape = JsonFormat.Shape.STRING)
			private Date genesisTime;

			private String chainId;
			private int initialHeight;
			private ConsensusParams consensusParams;
			private GenesisValidator[] validators;
			private byte[] appHash;
			private AppState appState;

			@Data
			public static class ConsensusParams {
				private Block block;
				private Evidence evidence;
				private Validator validator;
				private Version version;

				@Data
				public static class Block {
					private int maxBytes;
					private int maxGas;
					// timeIotaMs has no value anymore in CometBFT
				}

				@Data
				public static class Evidence {
					private int maxAgeNumBlocks;
					private Duration maxAgeDuration;
					private int maxBytes;
				}

				@Data
				public static class Validator {
					private String[] pubKeyTypes;
				}

				@Data
				public static class Version {
					private int app;
				}
			}

			@Data
			public static class GenesisValidator {
				private byte[] address;
				private PubKey pubKey;
				private int power;
				private String name;

				@Data
				public static class PubKey {
					// TODO:
				}
			}

			@Data
			public static class AppState {
				private Bank bank;
				private Mint mint;

				@Data
				public static class Bank {
					private Params params;
					private Balance[] balances;
					private Coins supply;
					private Metadata[] denomMetadata;
					private SendEnabled[] sendEnabled;

					@Data
					public static class Params {
						// private SendEnabled sendEnabled;
						// Deprecated: Use of SendEnabled in params is deprecated.
						private boolean defaultSendEnabled;
					}

					@Data
					public static class Balance {
						private String address;
						private Coins coins;
					}

					@Data
					public static class Metadata {
						private String description;
						private DenomUnit[] denomUnits;
						private String base;
						private String display;
						private String name;
						private String symbol;
						private String uri;
						private String uriHash;

						@Data
						public static class DenomUnit {
							private String denom;
							private int exponent;
							private String[] aliases;
						}
					}

					@Data
					public static class SendEnabled {
						private String denom;
						private boolean enabled;
					}
				}

				@Data
				public static class Mint {
					private Minter minter;
					private Params params;

					@Data
					public static class Minter {
						private BigDecimal inflation;
						private BigDecimal annualProvisions;
					}

					@Data
					public static class Params {
						private String mintDenom;
						private BigDecimal inflationRateChange;
						private BigDecimal inflationMax;
						private BigDecimal inflationMin;
						private BigDecimal goalBonded;
						private int blocksPerYear;
					}
				}

			}

		}
	}
}

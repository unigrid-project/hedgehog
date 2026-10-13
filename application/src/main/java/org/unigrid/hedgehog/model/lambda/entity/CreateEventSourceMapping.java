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

package org.unigrid.hedgehog.model.lambda.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateEventSourceMapping {

	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	private class AmazoneManageKafkaEventSourceConfig {
		@JsonProperty(value = "ConsumerGroupId")
		private String consumerGroupId;
	}

	@JsonProperty(value = "BatchSize")
	private int batchSize;

	@JsonProperty(value = "BisectBatchOnFuntionError")
	private boolean bisectBatchOnFuntionError;

	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	private class DestinationConfig {

		@Data
		@NoArgsConstructor
		@AllArgsConstructor
		private class OnFailure{
			@JsonProperty(value = "Destination")
			private String destination;
		}

		@Data
		@NoArgsConstructor
		@AllArgsConstructor
		private class OnSuccess {
			@JsonProperty(value = "Destination")
			private String destination;
		}
	}

	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	private class DocumentDBEnventSourceConfig {
		@JsonProperty(value = "CollectionName")
		private String collectionName;

		@JsonProperty(value = "DatabaseName")
		private String databaseName;

		@JsonProperty(value = "FullDocument")
		private String fullDocument;
	}

	@JsonProperty(value = "Enabled")
	private boolean enabled;

	@JsonProperty(value = "EventSourceArn")
	private String eventSourceArn;

	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	private class FilterCriteria {
		
	}
}

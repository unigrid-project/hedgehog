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

package org.unigrid.hedgehog.s3.entity;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.adapters.XmlJavaTypeAdapter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@XmlAccessorType(XmlAccessType.FIELD)
public class Version {
	@XmlElement(name = "Key")
	private String key;

	@XmlElement(name = "VersionId")
	private String versionId;

	@XmlElement(name = "IsLatest")
	private Boolean isLatest;

	@XmlElement(name = "LastModified")
	@XmlJavaTypeAdapter(TimestampAdapter.class)
	private Instant lastModified;

	@XmlElement(name = "ETag")
	private String eTag;

	@XmlElement(name = "Size")
	private Long size;

	@XmlElement(name = "StorageClass")
	private String storageClass;

	@XmlElement(name = "Owner")
	private Owner owner;

	@XmlElement(name = "ChecksumAlgorithm")
	private List<String> checksumAlgorithms = new ArrayList<>();
}

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
import jakarta.xml.bind.annotation.XmlElements;
import jakarta.xml.bind.annotation.XmlRootElement;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@XmlRootElement(name = "ListVersionsResult")
@XmlAccessorType(XmlAccessType.FIELD)
public class ListVersionsResult {
	@XmlElement(name = "Name")
	private String name;

	@XmlElement(name = "Prefix")
	private String prefix;

	@XmlElement(name = "KeyMarker")
	private String keyMarker;

	@XmlElement(name = "VersionIdMarker")
	private String versionIdMarker;

	@XmlElement(name = "NextKeyMarker")
	private String nextKeyMarker;

	@XmlElement(name = "NextVersionIdMarker")
	private String nextVersionIdMarker;

	@XmlElement(name = "MaxKeys")
	private Integer maxKeys;

	@XmlElement(name = "Delimiter")
	private String delimiter;

	@XmlElement(name = "IsTruncated")
	private Boolean isTruncated;

	@XmlElement(name = "EncodingType")
	private String encodingType;

	@XmlElements({
		@XmlElement(name = "Version", type = Version.class),
		@XmlElement(name = "DeleteMarker", type = DeleteMarker.class)
	})
	private List<Object> entries = new ArrayList<>();

	@XmlElement(name = "CommonPrefixes")
	private List<CommonPrefix> commonPrefixes = new ArrayList<>();
}

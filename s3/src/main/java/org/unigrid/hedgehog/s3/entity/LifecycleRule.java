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
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@XmlAccessorType(XmlAccessType.FIELD)
public class LifecycleRule {
	@XmlElement(name = "ID")
	private String id;

	@XmlElement(name = "Filter")
	private LifecycleFilter filter;

	@XmlElement(name = "Prefix")
	private String prefix;

	@XmlElement(name = "Status")
	private String status;

	@XmlElement(name = "Expiration")
	private LifecycleExpiration expiration;

	@XmlElement(name = "NoncurrentVersionExpiration")
	private NoncurrentVersionExpiration noncurrentVersionExpiration;

	@XmlElement(name = "AbortIncompleteMultipartUpload")
	private AbortIncompleteMultipartUpload abortIncompleteMultipartUpload;
}

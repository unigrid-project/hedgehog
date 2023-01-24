/*
    Unigrid Hedgehog
    Copyright © 2021-2022 The Unigrid Foundation, UGD Software AB

    This program is free software: you can redistribute it and/or modify it under the terms of the
    addended GNU Affero General Public License as published by the The Unigrid Foundation and
    the Free Software Foundation, version 3 of the License (see COPYING and COPYING.addendum).

    This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
    even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU Affero General Public License and the addendum for more details.

    You should have received an addended copy of the GNU Affero General Public License with this program.
    If not, see <http://www.gnu.org/licenses/> and <https://github.com/unigrid-project/hedgehog>.
 */

package org.unigrid.hedgehog.model.s3.entity;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlElementWrapper;
import jakarta.xml.bind.annotation.XmlRootElement;
import java.io.Serializable;
import java.util.List;
import lombok.Data;

@Data()
@XmlRootElement(name = "ListAllMyBucketsResult", namespace = "http://s3.amazonaws.com/doc/2006-03-01")
@XmlAccessorType(XmlAccessType.FIELD)
public class ListAllMyBucketsResult implements Serializable {
	@XmlElementWrapper(name = "Buckets")
	@XmlElement(name = "Bucket")
	private List<Bucket> buckets;

	@XmlElement(name = "Owner")
	private Owner owner;

	public ListAllMyBucketsResult() {
	}

	public ListAllMyBucketsResult(List<Bucket> buckets, Owner owner) {
		this.buckets = buckets;
		this.owner = owner;
	}
}

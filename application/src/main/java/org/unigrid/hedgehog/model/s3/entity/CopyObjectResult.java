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

import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;
import java.security.Timestamp;

@XmlRootElement
public class CopyObjectResult {
	@XmlElement
	private String eTag;

	@XmlElement
	private String lastModified;

	@XmlElement
	private String checksumCRC32;

	@XmlElement
	private String checksumCRC32C;

	@XmlElement
	private String checksumSHA1;

	@XmlElement
	private String checksumSHA256;

	public CopyObjectResult() {
	}
	
	public CopyObjectResult(String eTag, String lastModified, String checksumCRC32, String checksumCRC32C,
		String checksumSHA1, String checksumSHA256) {
		this.eTag = eTag;
		this.lastModified = lastModified;
		this.checksumCRC32 = checksumCRC32;
		this.checksumCRC32C = checksumCRC32C;
		this.checksumSHA1 = checksumSHA1;
		this.checksumSHA256 = checksumSHA256;
	}

}

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
 package org.unigrid.hedgehog.model.s3.entity;

import jakarta.xml.bind.annotation.*;
import jakarta.xml.bind.annotation.adapters.XmlJavaTypeAdapter;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

@XmlRootElement
@XmlAccessorType(XmlAccessType.FIELD)
public class CopyObjectResult implements Serializable {

    @XmlElement(name = "ETag")
    private String eTag;

    @XmlElement(name = "LastModified")
    @XmlJavaTypeAdapter(InstantAdapter.class)
    private Instant lastModified;

    @XmlElement(name = "ChecksumCRC32")
    private String checksumCRC32;

    @XmlElement(name = "ChecksumCRC32C")
    private String checksumCRC32C;

    @XmlElement(name = "ChecksumSHA1")
    private String checksumSHA1;

    @XmlElement(name = "ChecksumSHA256")
    private String checksumSHA256;

    public CopyObjectResult() { }

    public String getETag() { return eTag; }
    public void setETag(String eTag) { this.eTag = eTag; }

    public Instant getLastModified() { return lastModified; }
    public void setLastModified(Instant lastModified) { this.lastModified = lastModified; }

    public String getChecksumCRC32() { return checksumCRC32; }
    public void setChecksumCRC32(String checksumCRC32) { this.checksumCRC32 = checksumCRC32; }

    public String getChecksumCRC32C() { return checksumCRC32C; }
    public void setChecksumCRC32C(String checksumCRC32C) { this.checksumCRC32C = checksumCRC32C; }

    public String getChecksumSHA1() { return checksumSHA1; }
    public void setChecksumSHA1(String checksumSHA1) { this.checksumSHA1 = checksumSHA1; }

    public String getChecksumSHA256() { return checksumSHA256; }
    public void setChecksumSHA256(String checksumSHA256) { this.checksumSHA256 = checksumSHA256; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CopyObjectResult)) return false;
        CopyObjectResult that = (CopyObjectResult) o;
        return Objects.equals(eTag, that.eTag) &&
               Objects.equals(lastModified, that.lastModified);
    }

    @Override
    public int hashCode() {
        return Objects.hash(eTag, lastModified);
    }
}
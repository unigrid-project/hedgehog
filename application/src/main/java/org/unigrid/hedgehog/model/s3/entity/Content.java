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
public class Content implements Serializable {

    @XmlElement(name = "Key")
    private String key;

    @XmlElement(name = "LastModified")
    @XmlJavaTypeAdapter(InstantAdapter.class)
    private Instant lastModified;

    @XmlElement(name = "ETag")
    private String eTag;

    @XmlElement(name = "Size")
    private long size;

    @XmlElement(name = "StorageClass")
    private String storageClass;

    public Content() {
    }

    public Content(String key, Instant lastModified, String eTag, long size, String storageClass) {
        this.key = key;
        this.lastModified = lastModified;
        this.eTag = eTag;
        this.size = size;
        this.storageClass = storageClass;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public Instant getLastModified() {
        return lastModified;
    }

    public void setLastModified(Instant lastModified) {
        this.lastModified = lastModified;
    }

    public String getETag() {
        return eTag;
    }

    public void setETag(String eTag) {
        this.eTag = eTag;
    }

    public long getSize() {
        return size;
    }

    public void setSize(long size) {
        this.size = size;
    }

    public String getStorageClass() {
        return storageClass;
    }

    public void setStorageClass(String storageClass) {
        this.storageClass = storageClass;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Content)) return false;
        Content content = (Content) o;
        return size == content.size &&
                Objects.equals(key, content.key) &&
                Objects.equals(lastModified, content.lastModified) &&
                Objects.equals(eTag, content.eTag) &&
                Objects.equals(storageClass, content.storageClass);
    }

    @Override
    public int hashCode() {
        return Objects.hash(key, lastModified, eTag, size, storageClass);
    }

    @Override
    public String toString() {
        return "Content{" +
                "key='" + key + '\'' +
                ", lastModified=" + lastModified +
                ", eTag='" + eTag + '\'' +
                ", size=" + size +
                ", storageClass='" + storageClass + '\'' +
                '}';
    }
}
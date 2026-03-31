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
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Representerar resultatet av att lista objekt i en specifik bucket.
 */
@XmlRootElement(name = "ListBucketResult")
@XmlAccessorType(XmlAccessType.FIELD)
public class ListBucketResult implements Serializable {

    @XmlElement(name = "Name")
    private String name;

    @XmlElement(name = "Prefix")
    private String prefix;

    @XmlElement(name = "Delimiter")
    private String delimiter;

    @XmlElement(name = "MaxKeys")
    private int maxKeys;

    @XmlElement(name = "IsTruncated")
    private boolean isTruncated;

    @XmlElementWrapper(name = "Contents")
    @XmlElement(name = "Content")
    private List<Content> contents = new ArrayList<>();

    public ListBucketResult() {
    }

    public ListBucketResult(String name, String prefix, String delimiter, int maxKeys, boolean isTruncated, List<Content> contents) {
        this.name = name;
        this.prefix = prefix;
        this.delimiter = delimiter;
        this.maxKeys = maxKeys;
        this.isTruncated = isTruncated;
        this.contents = contents;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPrefix() {
        return prefix;
    }

    public void setPrefix(String prefix) {
        this.prefix = prefix;
    }

    public String getDelimiter() {
        return delimiter;
    }

    public void setDelimiter(String delimiter) {
        this.delimiter = delimiter;
    }

    public int getMaxKeys() {
        return maxKeys;
    }

    public void setMaxKeys(int maxKeys) {
        this.maxKeys = maxKeys;
    }

    public boolean isTruncated() {
        return isTruncated;
    }

    public void setIsTruncated(boolean truncated) {
        this.isTruncated = truncated;
    }

    public List<Content> getContents() {
        return contents;
    }

    public void setContents(List<Content> contents) {
        this.contents = contents;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ListBucketResult)) return false;
        ListBucketResult that = (ListBucketResult) o;
        return maxKeys == that.maxKeys &&
                isTruncated == that.isTruncated &&
                Objects.equals(name, that.name) &&
                Objects.equals(prefix, that.prefix) &&
                Objects.equals(delimiter, that.delimiter) &&
                Objects.equals(contents, that.contents);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, prefix, delimiter, maxKeys, isTruncated, contents);
    }

    @Override
    public String toString() {
        return "ListBucketResult{" +
                "name='" + name + '\'' +
                ", prefix='" + prefix + '\'' +
                ", delimiter='" + delimiter + '\'' +
                ", maxKeys=" + maxKeys +
                ", isTruncated=" + isTruncated +
                ", contents=" + contents +
                '}';
    }
}
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
 package org.unigrid.hedgehog.server.rest.entity;

import org.unigrid.hedgehog.common.model.Version;
import org.unigrid.hedgehog.model.Network;

import java.io.Serializable;

public class VersionResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    private String version;
    private String[] protocols;

    public VersionResponse() {
    }

    public VersionResponse(String version, String[] protocols) {
        this.version = version;
        this.protocols = protocols;
    }

    public String getVersion() {
        return version;
    }

    public String[] getProtocols() {
        return protocols;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public void setProtocols(String[] protocols) {
        this.protocols = protocols;
    }

    public static VersionResponse create() {
        return new VersionResponse(
                Version.getVersionNumber(),
                Network.getProtocols()
        );
    }
}
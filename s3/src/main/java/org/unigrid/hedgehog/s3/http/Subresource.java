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

package org.unigrid.hedgehog.s3.http;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.unigrid.hedgehog.s3.error.S3ErrorCode;
import org.unigrid.hedgehog.s3.error.S3Exception;

/* S3 names most operations by a query parameter on the bucket or object path rather than by a path of their own */
@Getter
@RequiredArgsConstructor
public enum Subresource {
	NONE(""),
	CORS("cors"),
	DELETE("delete"),
	LEGAL_HOLD("legal-hold"),
	LIFECYCLE("lifecycle"),
	LIST_TYPE("list-type"),
	LOCATION("location"),
	OBJECT_LOCK("object-lock"),
	RETENTION("retention"),
	TAGGING("tagging"),
	UPLOAD_ID("uploadId"),
	UPLOADS("uploads"),
	VERSIONING("versioning"),
	VERSIONS("versions");

	private static final Set<String> UNSUPPORTED = Set.of("accelerate", "acl", "analytics", "attributes",
		"encryption", "intelligent-tiering", "inventory", "logging", "metadataTable", "metrics", "notification",
		"ownershipControls", "policy", "policyStatus", "publicAccessBlock", "replication", "requestPayment",
		"restore", "select", "session", "torrent", "website"
	);

	private final String parameter;

	public static Subresource of(final Map<String, ?> query) {
		if (UNSUPPORTED.stream().anyMatch(query::containsKey)) {
			throw new S3Exception(S3ErrorCode.NotImplemented);
		}

		return Arrays.stream(values()).filter(subresource -> subresource != NONE)
			.filter(subresource -> query.containsKey(subresource.parameter)).findFirst().orElse(NONE);
	}
}

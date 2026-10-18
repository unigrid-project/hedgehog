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

package org.unigrid.hedgehog.s3.index;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import lombok.Builder;

/* The configurations hold the documents of the bucket subresources the index does not interpret, keyed by the
   subresource, such as the CORS rules or the lifecycle configuration as the client sent them */
@Builder(toBuilder = true)
public record Bucket(String name, Instant created, String location, Versioning versioning, boolean objectLock,
	Map<String, String> tags, Map<String, String> configurations) {

	public static final String CORS = "cors";
	public static final String LIFECYCLE = "lifecycle";
	public static final String OBJECT_LOCK = "object-lock";

	public Bucket {
		Objects.requireNonNull(name);
		Objects.requireNonNull(created);
		versioning = Objects.requireNonNullElse(versioning, Versioning.UNVERSIONED);
		tags = Immutable.map(tags);
		configurations = Immutable.map(configurations);
	}
}

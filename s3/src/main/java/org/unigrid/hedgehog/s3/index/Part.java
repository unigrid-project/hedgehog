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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.Builder;
import org.unigrid.hedgehog.s3.spi.BlobId;

@Builder(toBuilder = true)
public record Part(int number, List<BlobRef> slices, String etag, Map<String, String> checksums, Instant lastModified) {
	public Part {
		slices = Immutable.list(slices);
		checksums = Immutable.map(checksums);
	}

	public long size() {
		return slices.stream().mapToLong(BlobRef::length).sum();
	}

	Set<BlobId> blobs() {
		return slices.stream().map(BlobRef::id).collect(Collectors.toSet());
	}
}

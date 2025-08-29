/*
    Unigrid Hedgehog
    Copyright © 2021-2025 Stiftelsen The Unigrid Foundation

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

package org.unigrid.hedgehog.proxy.group;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/* Rendezvous ranking as in the storage placement: the highest SHA-256(seed || id) first, equal scores by id */
final class Rendezvous {
	private static final Comparator<Scored> HIGHEST_SCORE_FIRST = Comparator
		.<Scored, byte[]>comparing(Scored::score, Arrays::compareUnsigned).reversed().thenComparing(Scored::id);

	private record Scored(byte[] score, String id) {
		/* Pairs a gridnode id with its score for one seed */
	}

	private Rendezvous() {
		/* Static helpers only */
	}

	static List<String> rank(byte[] seed, Collection<String> ids) {
		return ids.stream().distinct().map(id -> new Scored(score(seed, id), id)).sorted(HIGHEST_SCORE_FIRST)
			.map(Scored::id).toList();
	}

	private static byte[] score(byte[] seed, String id) {
		return Sha256.digest(seed, id.getBytes(StandardCharsets.UTF_8));
	}
}

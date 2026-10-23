/*
    Unigrid Hedgehog
    Copyright © 2021-2026 Stiftelsen The Unigrid Foundation

    Stiftelsen The Unigrid Foundation (org. nr: 802482-2408)

    This program is free software: you can redistribute it and/or modify it under the terms of the
    addended GNU Affero General Public License as published by the The Unigrid Foundation and
    the Free Software Foundation, version 3 of the License (see COPYING and COPYING.addendum).

    This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even
    the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU Affero General Public License and the addendum for more details.

    You should have received an addended copy of the GNU Affero General Public License with this program.
    If not, see <http://www.gnu.org/licenses/> and <https://github.com/unigrid-project/hedgehog>.
 */

package org.unigrid.hedgehog.model.spork;

import java.io.ObjectInputFilter;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.unigrid.hedgehog.model.Address;

/* Java serialization makes whatever class a stream names, and the readObject of some classes runs what a crafted
   stream chooses, so a spork database is read as the classes that one holds and nothing else: a board member signs
   files handed to them on the machine that holds their key. A class is refused before anything of it is made. The
   collections are those the sporks, their decoders and the JSON of earlier builds make, and the arrays are the ones
   ArrayList and HashMap ask about before they allocate. The limits sit far above a database at the bounds of a spork
   type, whose mints alone come to about 10 MB, 225 000 references and a depth of 8, while the files are tens of kB. */
final class SporkDatabaseFilter implements ObjectInputFilter {
	static final long MAX_DEPTH = 32;
	static final long MAX_REFERENCES = 1 << 21;
	static final long MAX_BYTES = 1 << 26;
	static final long MAX_ARRAY_LENGTH = 1 << 17;

	private static final Set<Class<?>> CLASSES = Set.of(SporkDatabase.class, GridSpork.class, GridSpork.Type.class,
		SignatureLog.class, SignatureLogEntry.class, Address.class,
		MintStorage.class, MintStorage.SporkData.class, MintStorage.SporkData.Location.class,
		MintSupply.class, MintSupply.SporkData.class,
		VestingStorage.class, VestingStorage.SporkData.class, VestingStorage.SporkData.Vesting.class,
		StorageSpork.class, StorageSpork.SporkData.class,
		LedgerBlacklist.class, LedgerBlacklist.SporkData.class,
		LedgerFee.class, LedgerFee.SporkData.class,
		StatisticsPubKey.class, StatisticsPubKey.SporkData.class,
		BoardKeys.class, BoardKeys.SporkData.class, KeyChange.class, KeyChange.Approval.class
	);

	/* By name, as the form a time travels in is not public */
	private static final Set<String> JAVA_CLASSES = Set.of("java.lang.Enum", "java.lang.Number",
		"java.math.BigDecimal", "java.math.BigInteger", "java.time.Duration", "java.time.Instant", "java.time.Ser",
		"java.util.ArrayList", "java.util.HashMap", "java.util.HashSet", "java.util.LinkedHashMap",
		"java.util.LinkedHashSet", "java.util.TreeMap", "java.util.TreeSet"
	);

	private static final Set<Class<?>> ARRAYS = Set.of(byte[].class, Object[].class, Map.Entry[].class);

	private String refusal;

	@Override
	public Status checkInput(FilterInfo info) {
		if (isBeyondTheLimits(info)) {
			refusal = "it is larger or deeper than a spork database is";
			return Status.REJECTED;
		}

		final Class<?> type = info.serialClass();

		if (Objects.isNull(type) || isOfASporkDatabase(type)) {
			return Status.ALLOWED;
		}

		refusal = type.getName() + " is no class of a spork database";
		return Status.REJECTED;
	}

	private static boolean isBeyondTheLimits(FilterInfo info) {
		return info.depth() > MAX_DEPTH || info.references() > MAX_REFERENCES || info.streamBytes() > MAX_BYTES
			|| info.arrayLength() > MAX_ARRAY_LENGTH;
	}

	private static boolean isOfASporkDatabase(Class<?> type) {
		return type.isArray() ? ARRAYS.contains(type)
			: CLASSES.contains(type) || JAVA_CLASSES.contains(type.getName());
	}

	/* Why the stream was refused, when this filter refused it */
	Optional<String> refusal() {
		return Optional.ofNullable(refusal);
	}
}

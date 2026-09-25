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

package org.unigrid.hedgehog.model.spork;

import java.io.Serializable;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.apache.commons.codec.digest.DigestUtils;

@EqualsAndHashCode
@NoArgsConstructor
@ToString
public class SignatureLog implements Serializable {
	private static final long serialVersionUID = 1L;

	private final List<SignatureLogEntry> entries = new ArrayList<>();

	public SignatureLog(List<SignatureLogEntry> entries) {
		this.entries.addAll(entries);
	}

	public List<SignatureLogEntry> getEntries() {
		return Collections.unmodifiableList(entries);
	}

	public int size() {
		return entries.size();
	}

	public boolean isEmpty() {
		return entries.isEmpty();
	}

	public void append(SignatureLogEntry entry) {
		entries.add(entry);
	}

	public byte[] headHash() {
		return headHash(entries.size());
	}

	public byte[] headHash(int count) {
		final MessageDigest digest = DigestUtils.getSha512Digest();
		byte[] hash = new byte[0];

		for (SignatureLogEntry entry : entries.subList(0, count)) {
			digest.update(hash);
			hash = digest.digest(entry.toBytes());
		}

		return hash;
	}

	public boolean isPrefixOf(SignatureLog other) {
		return size() <= other.size() && Arrays.equals(headHash(), other.headHash(size()));
	}

	public boolean isValidFrom(int index, Set<String> keys) {
		return IntStream.range(index, entries.size()).allMatch(i -> {
			final SignatureLogEntry entry = entries.get(i);
			final boolean isAfterPrevious = i == 0
				|| entry.getTimeStamp().isAfter(entries.get(i - 1).getTimeStamp());

			return isAfterPrevious && keys.contains(entry.getSigner()) && entry.isValid();
		});
	}

	public SignatureLogEntry last() {
		return entries.getLast();
	}
}

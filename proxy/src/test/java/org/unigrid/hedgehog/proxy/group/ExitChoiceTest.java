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

package org.unigrid.hedgehog.proxy.group;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import org.unigrid.hedgehog.proxy.Expect;
import org.unigrid.hedgehog.proxy.chain.Fingerprint;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;

public class ExitChoiceTest {
	private static final List<String> MEMBERS = List.of("alpha", "bravo", "charlie", "delta");
	private static final Fingerprint COUNTING = fingerprintOf("0102030405060708090a0b0c0d0e0f10");

	@Provide
	public Arbitrary<Set<String>> members() {
		return Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(10).set().ofMinSize(1).ofMaxSize(12);
	}

	@Provide
	public Arbitrary<Fingerprint> fingerprints() {
		return Arbitraries.bytes().array(byte[].class).ofSize(Fingerprint.SIZE).map(Fingerprint::new);
	}

	private static Fingerprint filled(int value) {
		final byte[] bytes = new byte[Fingerprint.SIZE];

		Arrays.fill(bytes, (byte) value);
		return new Fingerprint(bytes);
	}

	private static Fingerprint fingerprintOf(String hex) {
		return new Fingerprint(HexFormat.of().parseHex(hex));
	}

	private static byte[] score(Fingerprint fingerprint, String member) throws Exception {
		final MessageDigest digest = MessageDigest.getInstance("SHA-256");

		digest.update(fingerprint.bytes());
		return digest.digest(member.getBytes(StandardCharsets.UTF_8));
	}

	private static List<String> retries(Fingerprint fingerprint, Collection<String> members) {
		final List<String> exits = new ArrayList<>();

		for (int attempt = 0; attempt <= members.size(); attempt++) {
			ExitChoice.exit(fingerprint, members, new HashSet<>(exits)).ifPresent(exits::add);
		}

		return exits;
	}

	@Property(tries = 200)
	public void shouldChooseTheHighestScore(@ForAll("fingerprints") Fingerprint fingerprint,
		@ForAll("members") Set<String> members) throws Exception {
		final String exit = ExitChoice.exit(fingerprint, members, Set.of()).orElseThrow();

		for (String member : members) {
			assertThat(Arrays.compareUnsigned(score(fingerprint, exit), score(fingerprint, member)),
				greaterThanOrEqualTo(0));
		}
	}

	@Property(tries = 100)
	public void shouldNotDependOnTheOrderOfTheMembers(@ForAll("fingerprints") Fingerprint fingerprint,
		@ForAll("members") Set<String> members, @ForAll Random random) {
		final List<String> shuffled = new ArrayList<>(members);

		Collections.shuffle(shuffled, random);
		assertThat(ExitChoice.exit(fingerprint, shuffled, Set.of()),
			equalTo(ExitChoice.exit(fingerprint, members, Set.of())));
	}

	@Property(tries = 100)
	public void shouldPassOverTheExcludedMembers(@ForAll("fingerprints") Fingerprint fingerprint,
		@ForAll("members") Set<String> members, @ForAll @Size(max = 3) Set<Integer> picks) {
		final List<String> listed = new ArrayList<>(members);
		final Set<String> excluded = new HashSet<>();

		picks.forEach(pick -> excluded.add(listed.get(Math.floorMod(pick, listed.size()))));

		final Optional<String> exit = ExitChoice.exit(fingerprint, members, excluded);

		assertThat(exit.isPresent(), is(!excluded.containsAll(members)));
		exit.ifPresent(chosen -> assertThat(excluded.contains(chosen), is(false)));
	}

	@Property(tries = 200)
	public void shouldChooseTheHighestScoreOfTheMembersLeft(@ForAll("fingerprints") Fingerprint fingerprint,
		@ForAll("members") Set<String> members, @ForAll @IntRange(min = 0, max = 12) int excludedCount)
		throws Exception {
		final Set<String> excluded = members.stream().sorted().limit(excludedCount).collect(Collectors.toSet());
		final Set<String> left = new HashSet<>(members);
		final Optional<String> exit = ExitChoice.exit(fingerprint, members, excluded);

		left.removeAll(excluded);
		assertThat(exit.isPresent(), is(!left.isEmpty()));

		for (String member : left) {
			assertThat(Arrays.compareUnsigned(score(fingerprint, exit.get()), score(fingerprint, member)),
				greaterThanOrEqualTo(0));
		}
	}

	/* Expected values computed independently with Python's hashlib over the same byte layout */
	@Example
	public void shouldMatchTheKnownChoices() {
		final byte[] counting = new byte[Fingerprint.SIZE];

		for (int i = 0; i < counting.length; i++) {
			counting[i] = (byte) (i + 1);
		}

		assertThat(ExitChoice.exit(new Fingerprint(counting), MEMBERS, Set.of()), equalTo(Optional.of("delta")));
		assertThat(ExitChoice.exit(new Fingerprint(counting), MEMBERS, Set.of("delta")),
			equalTo(Optional.of("alpha")));
		assertThat(ExitChoice.exit(filled(0xab), MEMBERS, Set.of()), equalTo(Optional.of("charlie")));
	}

	@Example
	public void shouldSpreadSessionsOverTheMembers() {
		final Set<String> chosen = new HashSet<>();

		for (int i = 0; i < 64; i++) {
			chosen.add(ExitChoice.exit(filled(i), MEMBERS, Set.of()).orElseThrow());
		}

		assertThat(chosen, equalTo(new HashSet<>(MEMBERS)));
	}

	@Example
	public void shouldFindNoExitInAnEmptyGroup() {
		assertThat(ExitChoice.exit(filled(1), List.of(), Set.of()), equalTo(Optional.empty()));
		assertThat(ExitChoice.exit(filled(1), MEMBERS, new HashSet<>(MEMBERS)), equalTo(Optional.empty()));
	}

	@Example
	public void shouldRetryDownTheWholeRankingOfTheKnownChoices() {
		assertThat(retries(COUNTING, MEMBERS), equalTo(List.of("delta", "alpha", "bravo", "charlie")));
		assertThat(retries(filled(0xab), MEMBERS), equalTo(List.of("charlie", "alpha", "delta", "bravo")));
		assertThat(retries(COUNTING, List.of("alpha", "bravo", "charlie", "delta", "echo")),
			equalTo(List.of("echo", "delta", "alpha", "bravo", "charlie")));
	}

	@Example
	public void shouldChooseAnotherExitForAnotherFingerprint() {
		assertThat(retries(fingerprintOf("fe02030405060708090a0b0c0d0e0f10"), MEMBERS),
			equalTo(List.of("charlie", "delta", "alpha", "bravo")));
		assertThat(retries(fingerprintOf("0102030405060708090a0b0c0d0e0fef"), MEMBERS),
			equalTo(List.of("delta", "charlie", "bravo", "alpha")));
	}

	@Example
	public void shouldKeepTheWinnerWhileOnlyOthersAreExcluded() {
		assertThat(ExitChoice.exit(COUNTING, MEMBERS, Set.of("alpha")), equalTo(Optional.of("delta")));
		assertThat(ExitChoice.exit(COUNTING, MEMBERS, Set.of("alpha", "bravo", "charlie")),
			equalTo(Optional.of("delta")));
	}

	@Example
	public void shouldFallToTheNextHighestScoreWhenTheWinnerIsExcluded() {
		assertThat(ExitChoice.exit(COUNTING, MEMBERS, Set.of("delta", "bravo")), equalTo(Optional.of("alpha")));
		assertThat(ExitChoice.exit(COUNTING, MEMBERS, Set.of("delta", "alpha")), equalTo(Optional.of("bravo")));
		assertThat(ExitChoice.exit(COUNTING, MEMBERS, Set.of("delta", "alpha", "charlie")),
			equalTo(Optional.of("bravo")));
		assertThat(ExitChoice.exit(COUNTING, MEMBERS, Set.of("delta", "alpha", "bravo")),
			equalTo(Optional.of("charlie")));
	}

	@Example
	public void shouldFindNoExitOnceEveryMemberIsExcluded() {
		assertThat(ExitChoice.exit(COUNTING, MEMBERS, Set.of("delta", "alpha", "bravo", "charlie")),
			equalTo(Optional.empty()));
		assertThat(ExitChoice.exit(COUNTING, MEMBERS, Set.of("delta", "alpha", "bravo", "charlie", "echo")),
			equalTo(Optional.empty()));
	}

	@Example
	public void shouldIgnoreExcludedIdsThatAreNoMembers() {
		assertThat(ExitChoice.exit(COUNTING, MEMBERS, Set.of("foxtrot", "DELTA", "")),
			equalTo(Optional.of("delta")));
		assertThat(ExitChoice.exit(COUNTING, MEMBERS, Set.of("foxtrot", "delta")), equalTo(Optional.of("alpha")));
	}

	@Example
	public void shouldChooseTheOnlyMemberUnlessItIsExcluded() {
		assertThat(ExitChoice.exit(COUNTING, List.of("solo"), Set.of()), equalTo(Optional.of("solo")));
		assertThat(ExitChoice.exit(COUNTING, List.of("solo"), Set.of("other")), equalTo(Optional.of("solo")));
		assertThat(ExitChoice.exit(COUNTING, List.of("solo"), Set.of("solo")), equalTo(Optional.empty()));
	}

	/* A lone surrogate encodes to the same UTF-8 bytes as "?", the only way for two ids to score alike */
	@Example
	public void shouldBreakEqualScoresByTheIdAndExcludeByTheId() {
		final List<String> alike = List.of("?", "\uD800");

		assertThat(ExitChoice.exit(COUNTING, alike, Set.of()), equalTo(Optional.of("?")));
		assertThat(ExitChoice.exit(COUNTING, List.of("\uD800", "?"), Set.of()), equalTo(Optional.of("?")));
		assertThat(ExitChoice.exit(COUNTING, alike, Set.of("?")), equalTo(Optional.of("\uD800")));
		assertThat(ExitChoice.exit(COUNTING, alike, Set.of("\uD800")), equalTo(Optional.of("?")));
		assertThat(ExitChoice.exit(COUNTING, alike, Set.of("?", "\uD800")), equalTo(Optional.empty()));
	}

	@Example
	public void shouldChooseTheSameExitWhateverTheOrderOrRepeatsOfTheMembers() {
		final List<String> expected = List.of("delta", "alpha", "bravo", "charlie");

		assertThat(retries(COUNTING, List.of("delta", "charlie", "bravo", "alpha")), equalTo(expected));
		assertThat(retries(COUNTING, List.of("bravo", "delta", "alpha", "charlie")), equalTo(expected));
		assertThat(retries(COUNTING, List.of("alpha", "alpha", "bravo", "charlie", "delta", "delta", "alpha")),
			equalTo(expected));
		assertThat(ExitChoice.exit(COUNTING, List.of("delta", "alpha", "delta"), Set.of("delta")),
			equalTo(Optional.of("alpha")));
		assertThat(ExitChoice.exit(COUNTING, List.of("delta", "delta"), Set.of("delta")), equalTo(Optional.empty()));
	}

	@Example
	public void shouldTreatTheAllZeroFingerprintAsAnyOther() {
		assertThat(retries(Fingerprint.NONE, MEMBERS), equalTo(List.of("delta", "bravo", "charlie", "alpha")));
	}

	@Example
	public void shouldRefuseAMissingFingerprint() {
		Expect.assertThrows(NullPointerException.class, () -> ExitChoice.exit(null, MEMBERS, Set.of()));
		Expect.assertThrows(NullPointerException.class, () -> ExitChoice.exit(null, List.of(), Set.of()));
	}

	@Example
	public void shouldRefuseMissingMembersAndAMissingExcludedSet() {
		Expect.assertThrows(NullPointerException.class, () -> ExitChoice.exit(COUNTING, null, Set.of()));
		Expect.assertThrows(NullPointerException.class, () -> ExitChoice.exit(COUNTING, MEMBERS, null));
	}
}

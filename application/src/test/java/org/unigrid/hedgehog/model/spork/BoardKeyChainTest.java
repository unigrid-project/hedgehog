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

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.security.AlgorithmParameters;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.SerializationUtils;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import org.unigrid.hedgehog.model.crypto.ActiveKeys;
import org.unigrid.hedgehog.model.crypto.Signature;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;

public class BoardKeyChainTest {
	private static final long EPOCH = 1;
	private static final Instant MADE_AT = Instant.parse("2026-10-23T12:00:00Z");
	private static final List<Signature> KEYS = keys(20);
	private static final List<Signature> BUILT_IN = KEYS.subList(0, 4);
	private static final byte[] ROOT = BoardKeyChain.root(EPOCH, publicKeysOf(BUILT_IN));

	@SneakyThrows
	private static List<Signature> keys(int count) {
		final List<Signature> keys = new ArrayList<>();

		for (int i = 0; i < count; i++) {
			keys.add(new Signature());
		}

		keys.sort(Comparator.comparing(Signature::getPublicKey));
		return Collections.unmodifiableList(keys);
	}

	private static List<String> publicKeysOf(Collection<Signature> keys) {
		return keys.stream().map(Signature::getPublicKey).sorted().toList();
	}

	private static List<Signature> keysOf(Signature... keys) {
		return List.of(keys);
	}

	private static List<Signature> builtInAnd(Signature... more) {
		return Stream.concat(BUILT_IN.stream(), Stream.of(more)).toList();
	}

	private static KeyChange change(int sequence, byte[] previous, Collection<Signature> active,
		Collection<Signature> approvers) {

		final KeyChange change = new KeyChange(sequence, MADE_AT.plusSeconds(sequence), previous,
			new ArrayList<>(publicKeysOf(active)), new ArrayList<>());

		approve(change, approvers);
		return change;
	}

	@SneakyThrows
	private static void approve(KeyChange change, Collection<Signature> approvers) {
		for (Signature approver : approvers.stream().sorted(Comparator.comparing(Signature::getPublicKey)).toList()) {
			change.getApprovals().add(new KeyChange.Approval(approver.getPublicKey(),
				approver.sign(change.getSignable())));
		}
	}

	/* The fifth key comes in, approved by three of the four built-in keys and by the fifth itself */
	private static KeyChange addingTheFifthKey() {
		return change(0, ROOT, builtInAnd(KEYS.get(4)), keysOf(KEYS.get(0), KEYS.get(1), KEYS.get(2), KEYS.get(4)));
	}

	private static ActiveKeys verify(KeyChange... changes) {
		return BoardKeyChain.verify(ROOT, publicKeysOf(BUILT_IN), List.of(changes));
	}

	private static String refusal(KeyChange... changes) {
		return assertThrows(IllegalArgumentException.class, () -> verify(changes)).getMessage();
	}

	@Property
	public void shouldNeedAMajorityOfTheKeysAndNeverFewerThanTwo(@ForAll @IntRange(min = 2, max = 16) int keys) {
		assertThat(BoardKeyChain.quorumOf(keys), is(Math.max(2, keys / 2 + 1)));
		assertThat(List.of(BoardKeyChain.quorumOf(2), BoardKeyChain.quorumOf(3), BoardKeyChain.quorumOf(4)),
			equalTo(List.of(2, 2, 3)));
	}

	@Property(tries = 15)
	public void shouldTakeAChangeThatAQuorumOfTheActiveKeysApprovedAndNoFewer(
		@ForAll @IntRange(min = 2, max = 16) int count) {

		final List<Signature> active = KEYS.subList(0, count);
		final byte[] root = BoardKeyChain.root(EPOCH, publicKeysOf(active));
		final Signature added = KEYS.get(count);
		final List<Signature> next = Stream.concat(active.subList(1, count).stream(), Stream.of(added)).toList();
		final int quorum = BoardKeyChain.quorumOf(count);
		final List<Signature> approvers = Stream.concat(active.subList(0, quorum).stream(), Stream.of(added)).toList();
		final List<Signature> tooFew = Stream.concat(active.subList(0, quorum - 1).stream(), Stream.of(added))
			.toList();

		assertThat(BoardKeyChain.verify(root, publicKeysOf(active), List.of(change(0, root, next, approvers)))
			.active(), equalTo(Set.copyOf(publicKeysOf(next))));
		assertThrows(IllegalArgumentException.class, () -> BoardKeyChain.verify(root, publicKeysOf(active),
			List.of(change(0, root, next, tooFew))));
	}

	@Example
	public void shouldStartFromTheBuiltInKeysWithNoChange() {
		final ActiveKeys keys = verify();

		assertThat(keys.active(), equalTo(Set.copyOf(publicKeysOf(BUILT_IN))));
		assertThat(keys.known(), equalTo(keys.active()));
		assertThat(keys.length(), is(0));
	}

	@Example
	public void shouldRootTheChainInTheEpochAndTheBuiltInKeysWhateverTheirOrder() {
		final List<String> reversed = new ArrayList<>(publicKeysOf(BUILT_IN));

		Collections.reverse(reversed);
		assertThat(BoardKeyChain.root(EPOCH, reversed), equalTo(ROOT));
		assertThat(BoardKeyChain.root(EPOCH + 1, publicKeysOf(BUILT_IN)), not(equalTo(ROOT)));
		assertThat(BoardKeyChain.root(EPOCH, publicKeysOf(builtInAnd(KEYS.get(4)))), not(equalTo(ROOT)));
	}

	@Example
	public void shouldRefuseAChainRootedElsewhere() {
		final byte[] elsewhere = BoardKeyChain.root(EPOCH + 1, publicKeysOf(BUILT_IN));

		assertThat(refusal(change(0, elsewhere, builtInAnd(KEYS.get(4)), keysOf(KEYS.get(0), KEYS.get(1),
			KEYS.get(2), KEYS.get(4)))), containsString("does not follow"));
	}

	@Example
	public void shouldFollowEachChangeOnTheOneBeforeAndKnowEveryKeyThatWasEverActive() {
		final KeyChange first = addingTheFifthKey();
		final List<Signature> withoutTheFirst = builtInAnd(KEYS.get(4)).subList(1, 5);
		final KeyChange second = change(1, BoardKeyChain.digestOf(first), withoutTheFirst,
			keysOf(KEYS.get(1), KEYS.get(2), KEYS.get(4)));
		final ActiveKeys keys = verify(first, second);

		assertThat(keys.active(), equalTo(Set.copyOf(publicKeysOf(withoutTheFirst))));
		assertThat(keys.known(), equalTo(Set.copyOf(publicKeysOf(builtInAnd(KEYS.get(4))))));
		assertThat(keys.inactive(), equalTo(Set.of(KEYS.get(0).getPublicKey())));
		assertThat(keys.length(), is(2));
		assertThat(BoardKeyChain.headOf(ROOT, List.of(first, second)), equalTo(BoardKeyChain.digestOf(second)));
		assertThat(BoardKeyChain.headOf(ROOT, List.of()), equalTo(ROOT));
	}

	@Example
	public void shouldRefuseAChangeOutOfSequenceOrOverAnotherChange() {
		final KeyChange first = addingTheFifthKey();
		final List<Signature> withoutTheFirst = builtInAnd(KEYS.get(4)).subList(1, 5);
		final List<Signature> approvers = keysOf(KEYS.get(1), KEYS.get(2), KEYS.get(4));

		assertThat(refusal(first, change(2, BoardKeyChain.digestOf(first), withoutTheFirst, approvers)),
			containsString("sequence"));
		assertThat(refusal(first, change(1, ROOT, withoutTheFirst, approvers)), containsString("does not follow"));
		assertThat(refusal(change(1, ROOT, builtInAnd(KEYS.get(4)), keysOf(KEYS.get(0), KEYS.get(1), KEYS.get(2),
			KEYS.get(4)))), containsString("sequence"));
	}

	/* Which keys approved a change, and in which of its two valid forms each signature is, decide whether it holds and
	   not which change it is: whoever holds it can drop an approval beyond the quorum or turn a signature s into n - s,
	   and the change that follows still follows on it */
	@Example
	public void shouldLinkTheNextChangeToWhatAChangeDecidesWhateverItsApprovals() {
		final KeyChange first = change(0, ROOT, builtInAnd(KEYS.get(4)), builtInAnd(KEYS.get(4)));
		final KeyChange dropped = SerializationUtils.clone(first);
		final KeyChange malleated = SerializationUtils.clone(first);
		final KeyChange.Approval approval = malleated.getApprovals().get(0);
		final KeyChange second = change(1, BoardKeyChain.digestOf(first), builtInAnd(KEYS.get(4)).subList(1, 5),
			keysOf(KEYS.get(1), KEYS.get(2), KEYS.get(4)));

		dropped.getApprovals().remove(0);
		approval.setSignature(malleated(approval.getSignature()));
		assertThat(approval.getSignature(), not(equalTo(first.getApprovals().get(0).getSignature())));
		assertThat(Signature.isSignedBy(approval.getKey(), first.getSignable(), approval.getSignature()), is(true));

		for (KeyChange copy : List.of(first, dropped, malleated)) {
			assertThat(BoardKeyChain.digestOf(copy), equalTo(BoardKeyChain.digestOf(first)));
			assertThat(verify(copy, second).length(), is(2));
		}
	}

	@Example
	public void shouldRefuseAfterAChangeOneThatDecidesOtherwise() {
		final KeyChange first = addingTheFifthKey();
		final KeyChange other = change(0, ROOT, builtInAnd(KEYS.get(5)), keysOf(KEYS.get(0), KEYS.get(1),
			KEYS.get(2), KEYS.get(5)));
		final KeyChange second = change(1, BoardKeyChain.digestOf(first), builtInAnd(KEYS.get(4)).subList(1, 5),
			keysOf(KEYS.get(1), KEYS.get(2), KEYS.get(4)));

		assertThat(verify(first, second).length(), is(2));
		assertThat(refusal(other, second), containsString("does not follow"));
	}

	/* Two copies of a change with the same decision are one change to the chain, but not to what was verified: a copy
	   with an approval that does not verify is checked again and refused */
	@Example
	public void shouldCheckTheApprovalsOfACopyOfAVerifiedChangeAgain() {
		final KeyChange first = addingTheFifthKey();
		final KeyChange corrupted = SerializationUtils.clone(first);

		verify(first);
		corrupted.getApprovals().get(0).getSignature()[10] ^= 1;
		assertThat(refusal(corrupted), containsString("does not verify"));
	}

	/* The other valid form of an ECDSA signature in DER: (r, n - s) for (r, s) */
	@SneakyThrows
	private static byte[] malleated(byte[] der) {
		final AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");

		parameters.init(new ECGenParameterSpec("secp521r1"));

		final BigInteger order = parameters.getParameterSpec(ECParameterSpec.class).getOrder();
		final int rAt = der[1] == (byte) 0x81 ? 3 : 2;
		final int sAt = rAt + 2 + der[rAt + 1];
		final byte[] r = Arrays.copyOfRange(der, rAt + 2, sAt);
		final BigInteger s = new BigInteger(1, Arrays.copyOfRange(der, sAt + 2, sAt + 2 + der[sAt + 1]));

		return derOf(r, order.subtract(s).toByteArray());
	}

	private static byte[] derOf(byte[] r, byte[] s) {
		final ByteArrayOutputStream body = new ByteArrayOutputStream();
		final ByteArrayOutputStream der = new ByteArrayOutputStream();

		body.write(0x02);
		body.write(r.length);
		body.writeBytes(r);
		body.write(0x02);
		body.write(s.length);
		body.writeBytes(s);
		der.write(0x30);

		if (body.size() > Byte.MAX_VALUE) {
			der.write(0x81);
		}

		der.write(body.size());
		der.writeBytes(body.toByteArray());
		return der.toByteArray();
	}

	/* A change verified once is known by its digest, which covers the change before it, but where it stands in the
	   chain is checked every time */
	@Example
	public void shouldCheckWhereAChangeVerifiedBeforeStands() {
		final KeyChange first = addingTheFifthKey();

		verify(first);
		assertThat(refusal(first, first), containsString("sequence"));
	}

	@Example
	public void shouldRefuseKeysOutOfOrderRepeatedOrOffTheCurve() {
		final KeyChange unordered = addingTheFifthKey();
		final KeyChange repeated = addingTheFifthKey();
		final KeyChange offTheCurve = addingTheFifthKey();
		final StringBuilder key = new StringBuilder(KEYS.get(4).getPublicKey());

		Collections.reverse(unordered.getActive());
		repeated.getActive().add(repeated.getActive().getLast());
		key.setCharAt(key.length() - 1, key.charAt(key.length() - 1) == '0' ? '1' : '0');
		offTheCurve.getActive().set(offTheCurve.getActive().indexOf(KEYS.get(4).getPublicKey()), key.toString());
		offTheCurve.getActive().sort(Comparator.naturalOrder());

		assertThat(refusal(unordered), containsString("ascending"));
		assertThat(refusal(repeated), containsString("ascending"));
		assertThat(refusal(offTheCurve), containsString("P-521"));
	}

	@Example
	public void shouldKeepTwoToSixteenKeysActive() {
		final List<Signature> approvers = keysOf(KEYS.get(0), KEYS.get(1), KEYS.get(2));
		final List<Signature> seventeen = KEYS.subList(0, 17);

		assertThat(refusal(change(0, ROOT, keysOf(KEYS.get(0)), approvers)), containsString("2 to 16"));
		assertThat(assertThrows(IllegalArgumentException.class, () -> change(0, ROOT, seventeen, approvers))
			.getMessage(), containsString("at most 16"));
	}

	@Example
	public void shouldRefuseAChangeThatLeavesTheKeysAsTheyAre() {
		assertThat(refusal(change(0, ROOT, BUILT_IN, BUILT_IN)), containsString("as they are"));
	}

	@Example
	public void shouldRefuseAChangeThatAnAddedKeyDidNotApprove() {
		assertThat(refusal(change(0, ROOT, builtInAnd(KEYS.get(4)), BUILT_IN)), containsString("did not approve"));
	}

	@Example
	public void shouldRefuseAnApprovalOfAKeyThatIsNeitherActiveNorAdded() {
		assertThat(refusal(change(0, ROOT, builtInAnd(KEYS.get(4)), keysOf(KEYS.get(0), KEYS.get(1), KEYS.get(2),
			KEYS.get(4), KEYS.get(5)))), containsString("neither"));
	}

	@Example
	public void shouldCountEachApproverOnce() {
		final KeyChange twice = change(0, ROOT, builtInAnd(KEYS.get(4)), keysOf(KEYS.get(0), KEYS.get(1),
			KEYS.get(4)));

		twice.getApprovals().add(1, twice.getApprovals().get(0));
		assertThat(refusal(twice), containsString("ascending"));
	}

	@Example
	@SneakyThrows
	public void shouldRefuseAnApprovalThatDoesNotVerify() {
		final KeyChange forged = addingTheFifthKey();
		final KeyChange.Approval approval = forged.getApprovals().get(0);

		approval.setSignature(new Signature().sign(forged.getSignable()));
		assertThat(refusal(forged), containsString("does not verify"));
	}

	/* An approval covers the change as a whole and is tagged, so no signature of other bytes passes for one: not of
	   the change as it travels, nor of the same change made at another time */
	@Example
	@SneakyThrows
	public void shouldTakeAnApprovalOverTheTaggedChangeOnly() {
		final KeyChange untagged = addingTheFifthKey();
		final KeyChange other = addingTheFifthKey();
		final KeyChange later = addingTheFifthKey();

		untagged.getApprovals().get(0).setSignature(KEYS.get(0).sign(ArrayUtils.subarray(untagged.getSignable(),
			KeyChange.TAG.length, untagged.getSignable().length)));
		other.getApprovals().get(0).setSignature(KEYS.get(0).sign(other.toBytes()));
		later.setMadeAt(later.getMadeAt().plusMillis(1));

		assertThat(refusal(untagged), containsString("does not verify"));
		assertThat(refusal(other), containsString("does not verify"));
		assertThat(refusal(later), containsString("does not verify"));
		assertThat(ArrayUtils.subarray(untagged.getSignable(), 0, KeyChange.TAG.length), equalTo(KeyChange.TAG));
	}

	@Example
	public void shouldRefuseMoreChangesThanTheCap() {
		final KeyChange first = addingTheFifthKey();

		assertThat(refusal(IntStream.rangeClosed(0, BoardKeyChain.MAX_CHANGES).mapToObj(i -> first)
			.toArray(KeyChange[]::new)), containsString("at most " + BoardKeyChain.MAX_CHANGES));
	}

	@Example
	public void shouldAnswerNothingForAChainThatDoesNotVerify() {
		assertThat(BoardKeyChain.verified(ROOT, publicKeysOf(BUILT_IN), List.of(change(0, ROOT, BUILT_IN, BUILT_IN)))
			.isPresent(), is(false));
		assertThat(BoardKeyChain.verified(ROOT, publicKeysOf(BUILT_IN), List.of(addingTheFifthKey())).isPresent(),
			is(true));
	}
}

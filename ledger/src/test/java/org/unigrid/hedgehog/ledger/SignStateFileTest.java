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

package org.unigrid.hedgehog.ledger;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.unigrid.hedgehog.ledger.Ballot.Type.PRECOMMIT;
import static org.unigrid.hedgehog.ledger.Ballot.Type.PREVOTE;
import static org.unigrid.hedgehog.ledger.Expect.assertThrows;

public class SignStateFileTest {
	private static final int LARGEST_PROOF = 4;
	private static final int SELF = 4;
	private static final int PROOF = 3;
	private static final int LAST_VIEW = 3;
	private static final byte[] MAGIC = "UGSS".getBytes(StandardCharsets.US_ASCII);
	private static final byte VERSION = 2;
	private static final int HEADER_SIZE = 13;
	private static final int VALIDATORS_OFFSET = 5;
	private static final int HEAD_SIZE = 18;
	private static final int COUNT_SIZE = 4;
	private static final int BALLOT_SIZE = 174;
	private static final int CHECKSUM_SIZE = 64;

	private static Block block(int n) {
		return CodecFixtures.block(new Random(n), 2, 3).toBuilder().height(1).build();
	}

	private static Path fileIn(FileSystem fileSystem) {
		return fileSystem.getPath("/data/sign/state");
	}

	private static Path temporaryOf(Path file) {
		return file.resolveSibling("state.tmp");
	}

	private static FileSystem newFileSystem() {
		return Jimfs.newFileSystem(Configuration.unix());
	}

	private static SignState small() {
		return SignState.empty(1);
	}

	private static SignState signed() {
		final Block block = block(1);
		final List<Ballot> ballots = List.of(ConsensusFixtures.ballot(PREVOTE, SELF, 0, block),
			ConsensusFixtures.ballot(PRECOMMIT, SELF, 0, null));

		return new SignState(1, true, 1, Consensus.Step.PRECOMMIT, 2, block, 0, block, 1, List.of(), ballots);
	}

	private static Proposal signedProposal(int view, int validRound, Block block, List<Ballot> polka) {
		return Proposal.signed(LedgerFixtures.seed(SELF), ConsensusFixtures.CHAIN, 1, view, validRound, block,
			polka);
	}

	/* A block of the longest kind of transaction and two full certificates, so as long as a block can be */
	private static Block fullBlock() {
		final Random random = new Random(7);
		final List<Transaction> transactions = IntStream.range(0, Block.MAX_TRANSACTIONS)
			.mapToObj(i -> (Transaction) CodecFixtures.claim(random, false)).toList();
		final Block block = CodecFixtures.block(random, 0, 0).toBuilder().height(1).transactions(transactions)
			.certificate(fullCertificate(random)).lastCommit(fullCertificate(random)).build();

		assertThat(BlockCodec.encode(block).length, equalTo(BlockCodec.MAX_ENCODED_SIZE));
		return block;
	}

	private static Certificate fullCertificate(Random random) {
		return new Certificate(0, IntStream.range(0, ValidatorSet.MAX_SIZE).mapToObj(i -> new Endorsement(
			CodecFixtures.account(random), CodecFixtures.random(random, Ed25519.SIGNATURE_SIZE))).toList());
	}

	/* Two re-proposals with a full proof (a validator of a set of PROOF proposes in two of four views at most), two
	   ballots in each of the views up to LAST_VIEW and a full block both locked and valid: as much as a validator
	   of that set can have signed, and exactly what the largest file is sized for */
	private static SignState fullestState(List<Proposal> extra) {
		final Block block = fullBlock();
		final List<Ballot> polka = IntStream.rangeClosed(1, PROOF)
			.mapToObj(validator -> ConsensusFixtures.ballot(PREVOTE, validator, 0, block)).toList();
		final List<Proposal> proposals = Stream.concat(Stream.of(1, LAST_VIEW)
			.map(view -> signedProposal(view, 0, block, polka)), extra.stream()).toList();
		final List<Ballot> ballots = IntStream.rangeClosed(0, LAST_VIEW).boxed()
			.flatMap(view -> Stream.of(ConsensusFixtures.ballot(PREVOTE, SELF, view, block),
				ConsensusFixtures.ballot(PRECOMMIT, SELF, view, block))).toList();

		return new SignState(1, true, LAST_VIEW, Consensus.Step.PRECOMMIT, LAST_VIEW + 1, block, LAST_VIEW, block,
			LAST_VIEW, proposals, ballots);
	}

	private static byte[] framed(byte[] magic, byte version, int validators, int length, byte[] payload) {
		final byte[] body = Bytes.concat(magic, new byte[] { version }, Bytes.intBytes(validators),
			Bytes.intBytes(length), payload);

		return Bytes.concat(body, Digests.sha512(body));
	}

	private static byte[] framed(int validators, byte[] payload) {
		return framed(MAGIC, VERSION, validators, payload.length, payload);
	}

	private static byte[] framed(byte[] payload) {
		return framed(LARGEST_PROOF, payload);
	}

	private static String refusalOf(Path file, int maxView) {
		return assertThrows(CorruptSignStateException.class, () -> new SignStateFile(file, maxView).load())
			.getMessage();
	}

	private static String refusalOf(Path file) {
		return refusalOf(file, SignStateFile.MAX_VIEW);
	}

	/* A valid header of a file of that size, which holds nothing behind it */
	private static byte[] headerOnly(int validators, int size) {
		return Bytes.concat(MAGIC, new byte[] { VERSION }, Bytes.intBytes(validators), Bytes.intBytes(0),
			new byte[size - HEADER_SIZE]);
	}

	private static void assertCorrupt(Path file) {
		refusalOf(file);
	}

	private static Optional<SignState> load(Path file) throws IOException {
		return new SignStateFile(file).load();
	}

	@Example
	public void shouldFindNothingWhereNothingWasSaved() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			assertThat(load(fileIn(fileSystem)), equalTo(Optional.empty()));
		}
	}

	@Example
	public void shouldLoadWhatWasSaved() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final Path file = fileIn(fileSystem);

			new SignStateFile(file).save(signed(), LARGEST_PROOF);
			assertThat(load(file), equalTo(Optional.of(signed())));
		}
	}

	@Example
	public void shouldLoadWhatAnotherInstanceSaved() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final Path file = fileIn(fileSystem);

			new SignStateFile(file).save(signed(), LARGEST_PROOF);
			assertThat(new SignStateFile(file).load(), equalTo(Optional.of(signed())));
		}
	}

	@Example
	public void shouldReplaceTheFirstStateWithTheSecond() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final Path file = fileIn(fileSystem);
			final SignStateFile store = new SignStateFile(file);

			store.save(small(), LARGEST_PROOF);
			store.save(signed(), LARGEST_PROOF);
			assertThat(load(file), equalTo(Optional.of(signed())));

			store.save(small(), LARGEST_PROOF);
			assertThat(load(file), equalTo(Optional.of(small())));
		}
	}

	@Example
	public void shouldCreateItsDirectory() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final Path file = fileSystem.getPath("/not/yet/there/state");

			new SignStateFile(file).save(small(), LARGEST_PROOF);
			assertThat(load(file), equalTo(Optional.of(small())));
		}
	}

	@Example
	public void shouldWriteTheMagicTheVersionTheSetSizeTheLengthThePayloadAndTheChecksum() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final Path file = fileIn(fileSystem);

			new SignStateFile(file).save(signed(), LARGEST_PROOF);

			final byte[] written = Files.readAllBytes(file);
			final byte[] payload = signed().encode();
			final int checksumAt = written.length - CHECKSUM_SIZE;

			assertThat(Bytes.hex(Arrays.copyOf(written, HEADER_SIZE)),
				equalTo("55475353" + "02" + "00000004" + Bytes.hex(Bytes.intBytes(payload.length))));
			assertThat(Arrays.copyOfRange(written, HEADER_SIZE, checksumAt), equalTo(payload));
			assertThat(Arrays.copyOfRange(written, checksumAt, written.length),
				equalTo(Digests.sha512(Arrays.copyOf(written, checksumAt))));
		}
	}

	@Example
	public void shouldRefuseAFileWithAnyOneByteChangedAnywhere() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final Path file = fileIn(fileSystem);

			new SignStateFile(file).save(small(), LARGEST_PROOF);

			final byte[] good = Files.readAllBytes(file);

			for (int position = 0; position < good.length; position++) {
				final byte[] damaged = good.clone();

				damaged[position] ^= 1;
				Files.write(file, damaged);
				assertCorrupt(file);
			}

			Files.write(file, good);
			assertThat(load(file), equalTo(Optional.of(small())));
		}
	}

	@Example
	public void shouldRefuseAFileOfASignedStateWithAnyOneByteChanged() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final Path file = fileIn(fileSystem);

			new SignStateFile(file).save(signed(), LARGEST_PROOF);

			final byte[] good = Files.readAllBytes(file);

			for (int position = 0; position < good.length; position++) {
				final byte[] damaged = good.clone();

				damaged[position] ^= (byte) 0x80;
				Files.write(file, damaged);
				assertCorrupt(file);
			}
		}
	}

	@Example
	public void shouldRefuseAFileCutShortAtEveryLength() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final Path file = fileIn(fileSystem);

			new SignStateFile(file).save(signed(), LARGEST_PROOF);

			final byte[] good = Files.readAllBytes(file);

			for (int length = 0; length < good.length; length++) {
				Files.write(file, Arrays.copyOf(good, length));
				assertCorrupt(file);
			}
		}
	}

	@Example
	public void shouldRefuseAFileWithBytesAfterTheChecksum() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final Path file = fileIn(fileSystem);

			new SignStateFile(file).save(small(), LARGEST_PROOF);
			Files.write(file, Bytes.concat(Files.readAllBytes(file), new byte[] { 0 }));
			assertCorrupt(file);
		}
	}

	@Example
	public void shouldLoadAFrameBuiltByHand() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final Path file = fileIn(fileSystem);

			Files.createDirectories(file.getParent());
			Files.write(file, framed(signed().encode()));
			assertThat(load(file), equalTo(Optional.of(signed())));
		}
	}

	@Example
	public void shouldRefuseAFileWithTheWrongMagicEvenIfItsChecksumIsRight() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final Path file = fileIn(fileSystem);
			final byte[] payload = small().encode();
			final byte[] foreign = "UGSX".getBytes(StandardCharsets.US_ASCII);

			Files.createDirectories(file.getParent());
			Files.write(file, framed(foreign, VERSION, LARGEST_PROOF, payload.length, payload));
			assertThat(refusalOf(file), containsString("magic"));
		}
	}

	@Example
	public void shouldRefuseAFileOfAnotherVersionEvenIfItsChecksumIsRight() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final Path file = fileIn(fileSystem);
			final byte[] payload = small().encode();

			Files.createDirectories(file.getParent());
			Files.write(file, framed(MAGIC, (byte) (VERSION + 1), LARGEST_PROOF, payload.length, payload));
			assertThat(refusalOf(file), containsString("version"));
		}
	}

	@Example
	public void shouldRefuseALengthThatDoesNotMatchTheFileEvenIfItsChecksumIsRight() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final Path file = fileIn(fileSystem);
			final byte[] payload = small().encode();

			Files.createDirectories(file.getParent());
			Files.write(file, framed(MAGIC, VERSION, LARGEST_PROOF, payload.length + 1, payload));
			assertThat(refusalOf(file), containsString("length"));
			Files.write(file, framed(MAGIC, VERSION, LARGEST_PROOF, payload.length - 1, payload));
			assertThat(refusalOf(file), containsString("length"));
			Files.write(file, framed(MAGIC, VERSION, LARGEST_PROOF, -1, payload));
			assertThat(refusalOf(file), containsString("length"));
		}
	}

	@Example
	public void shouldRefuseAPayloadThatIsNoStateEvenIfItsChecksumIsRight() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final Path file = fileIn(fileSystem);
			final byte[] trailing = Bytes.concat(small().encode(), new byte[] { 0 });

			Files.createDirectories(file.getParent());
			Files.write(file, framed(trailing));
			assertCorrupt(file);
			Files.write(file, framed(new byte[0]));
			assertCorrupt(file);
			Files.write(file, framed(Arrays.copyOf(small().encode(), 20)));
			assertCorrupt(file);
		}
	}

	private static SignState withProofOf(int ballots, long height) {
		final Block block = block(1).toBuilder().height(height).build();
		final List<Ballot> polka = IntStream.rangeClosed(1, ballots).mapToObj(validator -> Ballot.signed(PREVOTE,
			LedgerFixtures.seed(validator), ConsensusFixtures.CHAIN, height, 1, block.hash())).toList();
		final Proposal reProposal = Proposal.signed(LedgerFixtures.seed(SELF), ConsensusFixtures.CHAIN, height, 3, 1,
			block, polka);

		return new SignState(height, true, 3, Consensus.Step.PREVOTE, 4, null, -1, null, -1,
			List.of(reProposal), List.of());
	}

	@Example
	public void shouldLoadAProofAsLongAsTheSetItWasSavedUnderWithoutBeingToldTheSetSize() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final Path file = fileIn(fileSystem);
			final SignState state = withProofOf(LARGEST_PROOF, 1);

			new SignStateFile(file).save(state, LARGEST_PROOF);
			assertThat(load(file), equalTo(Optional.of(state)));
		}
	}

	@Example
	public void shouldLoadTheStateOfAnEarlierHeightWhoseSetHasShrunkSince() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final Path file = fileIn(fileSystem);
			final SignState stale = withProofOf(LARGEST_PROOF, 5);

			new SignStateFile(file).save(stale, LARGEST_PROOF);
			assertThat(new SignStateFile(file).load(), equalTo(Optional.of(stale)));
		}
	}

	@Example
	public void shouldReadAProofUnderTheSetSizeThatTheFileRecords() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final Path file = fileIn(fileSystem);
			final byte[] payload = withProofOf(3, 1).encode();

			Files.createDirectories(file.getParent());
			Files.write(file, framed(3, payload));
			assertThat(load(file), equalTo(Optional.of(withProofOf(3, 1))));
			Files.write(file, framed(2, payload));
			assertCorrupt(file);
		}
	}

	@Example
	public void shouldRefuseASetSizeThatNoSetCanHaveEvenIfTheChecksumIsRight() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final Path file = fileIn(fileSystem);
			final byte[] payload = small().encode();

			Files.createDirectories(file.getParent());

			for (final int validators : new int[] { 0, -1, ValidatorSet.MAX_SIZE + 1, Integer.MAX_VALUE }) {
				Files.write(file, framed(validators, payload));
				assertThat(refusalOf(file), containsString("validators"));
			}

			Files.write(file, framed(ValidatorSet.MAX_SIZE, payload));
			assertThat(load(file), equalTo(Optional.of(small())));
			Files.write(file, framed(1, payload));
			assertThat(load(file), equalTo(Optional.of(small())));
		}
	}

	@Example
	public void shouldNoticeAChangedSetSizeThatStillIsPossible() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final Path file = fileIn(fileSystem);

			new SignStateFile(file).save(small(), LARGEST_PROOF);

			final byte[] written = Files.readAllBytes(file);

			written[VALIDATORS_OFFSET + Integer.BYTES - 1] ^= 1;
			Files.write(file, written);
			assertThat(refusalOf(file), containsString("checksum"));
		}
	}

	@Example
	public void shouldRefuseToSaveForASetSizeThatNoSetCanHave() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final Path file = fileIn(fileSystem);

			assertThrows(IllegalArgumentException.class, () -> new SignStateFile(file).save(small(), 0));
			assertThrows(IllegalArgumentException.class,
				() -> new SignStateFile(file).save(small(), ValidatorSet.MAX_SIZE + 1));
			assertThat(Files.exists(file), equalTo(false));
		}
	}

	@Example
	public void shouldIgnoreAStaleTemporaryFileNextToAGoodOne() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final Path file = fileIn(fileSystem);

			new SignStateFile(file).save(small(), LARGEST_PROOF);
			Files.write(temporaryOf(file), new byte[] { 1, 2, 3 });
			assertThat(load(file), equalTo(Optional.of(small())));

			Files.write(temporaryOf(file), framed(signed().encode()));
			assertThat(load(file), equalTo(Optional.of(small())));
		}
	}

	@Example
	public void shouldReplaceAStaleTemporaryFileAtTheNextSave() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final Path file = fileIn(fileSystem);

			new SignStateFile(file).save(small(), LARGEST_PROOF);
			Files.write(temporaryOf(file), Bytes.concat(framed(signed().encode()), new byte[1000]));
			new SignStateFile(file).save(signed(), LARGEST_PROOF);

			assertThat(load(file), equalTo(Optional.of(signed())));
			assertThat(Files.exists(temporaryOf(file)), equalTo(false));
		}
	}

	@Example
	public void shouldFindNothingIfOnlyATemporaryFileIsThere() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final Path file = fileIn(fileSystem);

			Files.createDirectories(file.getParent());
			Files.write(temporaryOf(file), framed(signed().encode()));
			assertThat(load(file), equalTo(Optional.empty()));
		}
	}

	@Example
	public void shouldLeaveNoTemporaryFileBehindAfterASave() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final Path file = fileIn(fileSystem);
			final SignStateFile store = new SignStateFile(file);

			store.save(small(), LARGEST_PROOF);
			store.save(signed(), LARGEST_PROOF);

			try (Stream<Path> entries = Files.list(file.getParent())) {
				assertThat(entries.toList(), equalTo(List.of(file)));
			}
		}
	}

	@Example
	public void shouldKeepTheOldStateIfTheNewOneCannotBeWritten() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final Path file = fileIn(fileSystem);

			new SignStateFile(file).save(small(), LARGEST_PROOF);
			Files.createDirectory(temporaryOf(file));

			assertThrows(IOException.class, () -> new SignStateFile(file).save(signed(), LARGEST_PROOF));
			assertThat(load(file), equalTo(Optional.of(small())));
		}
	}

	@Example
	public void shouldNotTakeAnUnreadablePathForAMissingFile() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final Path file = fileIn(fileSystem);

			Files.createDirectories(file);
			assertThrows(IOException.class, () -> new SignStateFile(file).load());
		}
	}

	@Example
	public void shouldLoadAStateOfAnyHeightAndLeaveItToTheCallerToJudgeIt() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final Path file = fileIn(fileSystem);

			new SignStateFile(file).save(SignState.empty(5), LARGEST_PROOF);
			assertThat(load(file).orElseThrow().height(), equalTo(5L));
		}
	}

	@Example
	public void shouldNotLetAFailingMoveGoUnnoticed() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final Path file = fileIn(fileSystem);
			final Path child = file.resolve("child");

			Files.createDirectories(file);
			Files.write(child, new byte[] { 7 });

			assertThrows(IOException.class, () -> new SignStateFile(file).save(signed(), LARGEST_PROOF));
			assertThat(Files.readAllBytes(child), equalTo(new byte[] { 7 }));
		}
	}

	@Example
	public void shouldForceTheDirectoryOnlyWhereThereIsADiskAndTheSystemAllowsIt() throws IOException {
		assertThat(SignStateFile.canForceDirectories(FileSystems.getDefault(), "Linux"), equalTo(true));
		assertThat(SignStateFile.canForceDirectories(FileSystems.getDefault(), "Mac OS X"), equalTo(true));
		assertThat(SignStateFile.canForceDirectories(FileSystems.getDefault(), "Windows 11"), equalTo(false));

		try (FileSystem fileSystem = newFileSystem()) {
			assertThat(SignStateFile.canForceDirectories(fileSystem, "Linux"), equalTo(false));
		}
	}

	@Example
	public void shouldSizeTheLargestFileFromWhatOneValidatorCanHold() {
		for (int proof = 1; proof <= 5; proof++) {
			final long views = LAST_VIEW + 1L;
			final long proposals = Math.ceilDiv(views, proof);
			final long expected = HEADER_SIZE + HEAD_SIZE + 2L * (1 + 4 + BlockCodec.MAX_ENCODED_SIZE + 4)
				+ COUNT_SIZE + proposals * ConsensusCodec.maxEncodedSize(proof) + COUNT_SIZE
				+ 2 * views * BALLOT_SIZE + CHECKSUM_SIZE;

			assertThat("validators " + proof, SignStateFile.largestFile(proof, LAST_VIEW), equalTo(expected));
		}
	}

	@Example
	public void shouldSizeTheLargestFileForAtLeastOneValidator() {
		assertThrows(IllegalArgumentException.class, () -> SignStateFile.largestFile(0, LAST_VIEW));
	}

	@Example
	public void shouldRefuseAFileOneByteOverTheLargestSizeBeforeReadingIt() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final Path file = fileIn(fileSystem);
			final long limit = SignStateFile.largestFile(PROOF, LAST_VIEW);

			Files.createDirectories(file.getParent());
			Files.write(file, headerOnly(PROOF, (int) limit + 1));

			final String refusal = assertThrows(CorruptSignStateException.class,
				() -> new SignStateFile(file, LAST_VIEW).load()).getMessage();

			assertThat(refusal, containsString(String.valueOf(limit + 1)));
			assertThat(refusal, not(containsString("length")));
			assertThat(refusal, not(containsString("checksum")));
		}
	}

	@Example
	public void shouldReadAFileOfExactlyTheLargestSizeForTheSetItRecords() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final Path file = fileIn(fileSystem);

			Files.createDirectories(file.getParent());
			Files.write(file, headerOnly(PROOF, (int) SignStateFile.largestFile(PROOF, LAST_VIEW)));

			final String refusal = assertThrows(CorruptSignStateException.class,
				() -> new SignStateFile(file, LAST_VIEW).load()).getMessage();

			assertThat(refusal, containsString("length"));
		}
	}

	@Example
	public void shouldJudgeTheSizeOfAFileBySetSizeItRecordsAndNotByAnotherOne() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final Path file = fileIn(fileSystem);
			final int size = (int) SignStateFile.largestFile(PROOF, LAST_VIEW);

			Files.createDirectories(file.getParent());
			Files.write(file, headerOnly(1, size));
			assertThat(refusalOf(file, LAST_VIEW), containsString("length"));
			Files.write(file, headerOnly(ValidatorSet.MAX_SIZE, size));
			assertThat(refusalOf(file, LAST_VIEW), containsString("more than"));
		}
	}

	@Example
	public void shouldSaveAndLoadTheFullestStateTheSizeAllowsAtItsExactSize() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final Path file = fileIn(fileSystem);
			final SignStateFile store = new SignStateFile(file, LAST_VIEW);
			final SignState fullest = fullestState(List.of());

			store.save(fullest, PROOF);
			assertThat(Files.size(file), equalTo(SignStateFile.largestFile(PROOF, LAST_VIEW)));
			assertThat(store.load(), equalTo(Optional.of(fullest)));
		}
	}

	@Example
	public void shouldRefuseToSaveWhatIsOneProposalPastTheLargestSize() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final Path file = fileIn(fileSystem);
			final SignStateFile store = new SignStateFile(file, LAST_VIEW);
			final SignState past = fullestState(List.of(signedProposal(0, -1, fullBlock(), List.of())));

			store.save(SignState.empty(1), PROOF);

			final String refusal = assertThrows(IllegalArgumentException.class, () -> store.save(past, PROOF))
				.getMessage();

			assertThat(refusal, containsString(String.valueOf(SignStateFile.largestFile(PROOF, LAST_VIEW))));
			assertThat(store.load(), equalTo(Optional.of(SignState.empty(1))));
			assertThat(Files.exists(temporaryOf(file)), equalTo(false));
		}
	}

	@Example
	public void shouldKeepTheStateOfTheFirstTwoViewsThroughThePublicConstructor() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final Path file = fileIn(fileSystem);
			final List<Proposal> proposals = List.of(signedProposal(0, -1, block(1), List.of()),
				signedProposal(1, -1, block(2), List.of()));
			final List<Ballot> ballots = List.of(ConsensusFixtures.ballot(PREVOTE, SELF, 0, null),
				ConsensusFixtures.ballot(PRECOMMIT, SELF, 0, null),
				ConsensusFixtures.ballot(PREVOTE, SELF, 1, null),
				ConsensusFixtures.ballot(PRECOMMIT, SELF, 1, null));
			final SignState state = new SignState(1, true, 1, Consensus.Step.PRECOMMIT, 2, null, -1, null, -1,
				proposals, ballots);

			new SignStateFile(file).save(state, LARGEST_PROOF);
			assertThat(new SignStateFile(file).load(), equalTo(Optional.of(state)));
			assertThat(SignStateFile.MAX_VIEW, equalTo(4096));
		}
	}

	@Example
	public void shouldSizeTheFileByTheNumberOfValidatorsItIsSavedFor() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final Path file = fileIn(fileSystem);
			final SignStateFile store = new SignStateFile(file, LAST_VIEW);
			final SignState fullest = fullestState(List.of());

			assertThrows(IllegalArgumentException.class, () -> store.save(fullest, LARGEST_PROOF));
			assertThat(Files.exists(file), equalTo(false));
		}
	}
}

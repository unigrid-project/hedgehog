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
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.not;
import static org.unigrid.hedgehog.ledger.Ballot.Type.PRECOMMIT;
import static org.unigrid.hedgehog.ledger.Ballot.Type.PREVOTE;
import static org.unigrid.hedgehog.ledger.Expect.assertThrows;

public class SignStateFileTest {
	private static final int LARGEST_PROOF = 4;
	private static final int SELF = 4;
	private static final int VIEWS = 4;
	private static final byte[] MAGIC = "UGSS".getBytes(StandardCharsets.US_ASCII);
	private static final byte VERSION = 1;
	private static final int HEADER_SIZE = 9;
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

	private static byte[] framed(byte[] magic, byte version, int length, byte[] payload) {
		final byte[] body = Bytes.concat(magic, new byte[] { version }, Bytes.intBytes(length), payload);

		return Bytes.concat(body, Digests.sha512(body));
	}

	private static byte[] framed(byte[] payload) {
		return framed(MAGIC, VERSION, payload.length, payload);
	}

	private static String refusalOf(Path file) {
		return assertThrows(CorruptSignStateException.class, () -> new SignStateFile(file).load(LARGEST_PROOF))
			.getMessage();
	}

	private static void assertCorrupt(Path file) {
		refusalOf(file);
	}

	private static Optional<SignState> load(Path file) throws IOException {
		return new SignStateFile(file).load(LARGEST_PROOF);
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

			new SignStateFile(file).save(signed());
			assertThat(load(file), equalTo(Optional.of(signed())));
		}
	}

	@Example
	public void shouldLoadWhatAnotherInstanceSaved() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final Path file = fileIn(fileSystem);

			new SignStateFile(file).save(signed());
			assertThat(new SignStateFile(file).load(LARGEST_PROOF), equalTo(Optional.of(signed())));
		}
	}

	@Example
	public void shouldReplaceTheFirstStateWithTheSecond() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final Path file = fileIn(fileSystem);
			final SignStateFile store = new SignStateFile(file);

			store.save(small());
			store.save(signed());
			assertThat(load(file), equalTo(Optional.of(signed())));

			store.save(small());
			assertThat(load(file), equalTo(Optional.of(small())));
		}
	}

	@Example
	public void shouldCreateItsDirectory() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final Path file = fileSystem.getPath("/not/yet/there/state");

			new SignStateFile(file).save(small());
			assertThat(load(file), equalTo(Optional.of(small())));
		}
	}

	@Example
	public void shouldWriteTheMagicTheVersionTheLengthThePayloadAndTheChecksum() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final Path file = fileIn(fileSystem);

			new SignStateFile(file).save(signed());

			final byte[] written = Files.readAllBytes(file);
			final byte[] payload = signed().encode();

			assertThat(Arrays.copyOf(written, 4), equalTo(new byte[] { 'U', 'G', 'S', 'S' }));
			assertThat(written[4], equalTo((byte) 1));
			assertThat(ByteBuffer.wrap(written).getInt(5), equalTo(payload.length));
			final int checksumAt = written.length - CHECKSUM_SIZE;

			assertThat(Arrays.copyOfRange(written, HEADER_SIZE, checksumAt), equalTo(payload));
			assertThat(Arrays.copyOfRange(written, checksumAt, written.length),
				equalTo(Digests.sha512(Arrays.copyOf(written, checksumAt))));
		}
	}

	@Example
	public void shouldRefuseAFileWithAnyOneByteChangedAnywhere() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final Path file = fileIn(fileSystem);

			new SignStateFile(file).save(small());

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

			new SignStateFile(file).save(signed());

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

			new SignStateFile(file).save(signed());

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

			new SignStateFile(file).save(small());
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
			Files.write(file, framed(foreign, VERSION, payload.length, payload));
			assertThat(refusalOf(file), containsString("magic"));
		}
	}

	@Example
	public void shouldRefuseAFileOfAnotherVersionEvenIfItsChecksumIsRight() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final Path file = fileIn(fileSystem);
			final byte[] payload = small().encode();

			Files.createDirectories(file.getParent());
			Files.write(file, framed(MAGIC, (byte) (VERSION + 1), payload.length, payload));
			assertThat(refusalOf(file), containsString("version"));
		}
	}

	@Example
	public void shouldRefuseALengthThatDoesNotMatchTheFileEvenIfItsChecksumIsRight() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final Path file = fileIn(fileSystem);
			final byte[] payload = small().encode();

			Files.createDirectories(file.getParent());
			Files.write(file, framed(MAGIC, VERSION, payload.length + 1, payload));
			assertThat(refusalOf(file), containsString("length"));
			Files.write(file, framed(MAGIC, VERSION, payload.length - 1, payload));
			assertThat(refusalOf(file), containsString("length"));
			Files.write(file, framed(MAGIC, VERSION, -1, payload));
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

	@Example
	public void shouldRefuseAProofLongerThanTheLargestOnLoad() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final Path file = fileIn(fileSystem);
			final Block block = block(1);
			final List<Ballot> polka = IntStream.rangeClosed(1, 3)
				.mapToObj(validator -> ConsensusFixtures.ballot(PREVOTE, validator, 1, block)).toList();
			final Proposal reProposal = signedProposal(3, 1, block, polka);

			new SignStateFile(file).save(new SignState(1, true, 3, Consensus.Step.PREVOTE, 4, null, -1, null, -1,
				List.of(reProposal), List.of()));
			assertThat(new SignStateFile(file).load(3).isPresent(), equalTo(true));
			assertThrows(CorruptSignStateException.class, () -> new SignStateFile(file).load(2));
		}
	}

	@Example
	public void shouldIgnoreAStaleTemporaryFileNextToAGoodOne() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final Path file = fileIn(fileSystem);

			new SignStateFile(file).save(small());
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

			new SignStateFile(file).save(small());
			Files.write(temporaryOf(file), Bytes.concat(framed(signed().encode()), new byte[1000]));
			new SignStateFile(file).save(signed());

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

			store.save(small());
			store.save(signed());

			try (Stream<Path> entries = Files.list(file.getParent())) {
				assertThat(entries.toList(), equalTo(List.of(file)));
			}
		}
	}

	@Example
	public void shouldKeepTheOldStateIfTheNewOneCannotBeWritten() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final Path file = fileIn(fileSystem);

			new SignStateFile(file).save(small());
			Files.createDirectory(temporaryOf(file));

			assertThrows(IOException.class, () -> new SignStateFile(file).save(signed()));
			assertThat(load(file), equalTo(Optional.of(small())));
		}
	}

	@Example
	public void shouldNotTakeAnUnreadablePathForAMissingFile() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final Path file = fileIn(fileSystem);

			Files.createDirectories(file);
			assertThrows(IOException.class, () -> new SignStateFile(file).load(LARGEST_PROOF));
		}
	}

	@Example
	public void shouldLoadAStateOfAnyHeightAndLeaveItToTheCallerToJudgeIt() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final Path file = fileIn(fileSystem);

			new SignStateFile(file).save(SignState.empty(5));
			assertThat(load(file).orElseThrow().height(), equalTo(5L));
		}
	}

	@Example
	public void shouldRefuseAFileLargerThanTheLargestPossibleStateBeforeReadingIt() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final Path file = fileIn(fileSystem);
			final long limit = SignStateFile.largestFile(LARGEST_PROOF, 0);

			Files.createDirectories(file.getParent());
			Files.write(file, new byte[(int) limit + 1]);

			final String refusal = assertThrows(CorruptSignStateException.class,
				() -> new SignStateFile(file, 0).load(LARGEST_PROOF)).getMessage();

			assertThat(refusal, containsString(String.valueOf(limit + 1)));
			assertThat(refusal, not(containsString("magic")));
		}
	}

	@Example
	public void shouldReadAFileOfExactlyTheLargestPossibleStateSize() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final Path file = fileIn(fileSystem);
			final long limit = SignStateFile.largestFile(LARGEST_PROOF, 0);

			Files.createDirectories(file.getParent());
			Files.write(file, new byte[(int) limit]);

			final String refusal = assertThrows(CorruptSignStateException.class,
				() -> new SignStateFile(file, 0).load(LARGEST_PROOF)).getMessage();

			assertThat(refusal, containsString("magic"));
		}
	}

	@Example
	public void shouldGrowTheLargestFileByAProposalAndTwoBallotsForEveryView() {
		final int ballot = ConsensusCodec.encode(ConsensusFixtures.ballot(PREVOTE, SELF, 0, null)).length;

		final long entries = (long) ConsensusCodec.maxEncodedSize(LARGEST_PROOF) + 2 * ballot;

		for (int views = 0; views < 3; views++) {
			final long growth = SignStateFile.largestFile(LARGEST_PROOF, views + 1)
				- SignStateFile.largestFile(LARGEST_PROOF, views);

			assertThat(growth, greaterThanOrEqualTo(entries));
		}
	}

	@Example
	public void shouldFitTheFullestStateOfTheViewsItIsSizedFor() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final Path file = fileIn(fileSystem);
			final Block block = block(1);
			final List<Ballot> polka = IntStream.rangeClosed(1, LARGEST_PROOF)
				.mapToObj(validator -> ConsensusFixtures.ballot(PREVOTE, validator, 0, block)).toList();
			final List<Proposal> proposals = IntStream.range(0, VIEWS)
				.mapToObj(view -> view == 0 ? signedProposal(view, -1, block, List.of())
					: signedProposal(view, 0, block, polka)).toList();
			final List<Ballot> ballots = IntStream.range(0, VIEWS).boxed()
				.flatMap(view -> Stream.of(ConsensusFixtures.ballot(PREVOTE, SELF, view, block),
					ConsensusFixtures.ballot(PRECOMMIT, SELF, view, block))).toList();
			final int last = VIEWS - 1;

			new SignStateFile(file).save(new SignState(1, true, last, Consensus.Step.PRECOMMIT, VIEWS, block,
				last, block, last, proposals, ballots));
			assertThat(Files.size(file), lessThanOrEqualTo(SignStateFile.largestFile(LARGEST_PROOF, VIEWS)));
		}
	}
}

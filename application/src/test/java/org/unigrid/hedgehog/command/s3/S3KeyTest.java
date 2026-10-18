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

package org.unigrid.hedgehog.command.s3;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.lifecycle.BeforeTry;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import org.unigrid.hedgehog.command.HedgehogCli;
import org.unigrid.hedgehog.model.InMemoryDataDirectoryMockUp;
import org.unigrid.hedgehog.server.s3.S3Credentials;

public class S3KeyTest {
	private static final Pattern CREATED = Pattern.compile("Access key id:\\s+(HK[A-Z0-9]{18})\\R"
		+ "Secret access key:\\s+([A-Za-z0-9+/]{40})\\R");

	private Path file;

	@BeforeTry
	public void keepKeysInMemory() {
		new InMemoryDataDirectoryMockUp();
		file = S3Credentials.getFile();
	}

	private static HedgehogCli.Result key(final String... args) {
		final String[] command = new String[args.length + 2];

		command[0] = "s3";
		command[1] = "key";
		System.arraycopy(args, 0, command, 2, args.length);
		return HedgehogCli.run(command);
	}

	private static Matcher create() {
		final HedgehogCli.Result result = key("create");
		final Matcher created = CREATED.matcher(result.out());

		assertThat(result.exitCode(), equalTo(0));
		assertThat(result.out(), created.matches(), equalTo(true));
		return created;
	}

	@Example
	public void printsTheSecretOfANewKeyOnce() {
		final Matcher created = create();

		assertThat(new S3Credentials(file).secretKey(created.group(1)), equalTo(Optional.of(created.group(2))));
		assertThat(key("list").out(), equalTo(created.group(1) + System.lineSeparator()));
	}

	@Property(tries = 10)
	public void listsEveryKeyWithoutItsSecret(@ForAll @IntRange(min = 1, max = 5) int count) {
		final List<Matcher> keys = IntStream.range(0, count).mapToObj(i -> create()).toList();
		final HedgehogCli.Result listed = key("list");

		assertThat(listed.exitCode(), equalTo(0));
		assertThat(listed.out().lines().sorted().toList(), equalTo(keys.stream().map(key -> key.group(1)).sorted()
			.toList()
		));
		keys.forEach(key -> assertThat(listed.out().contains(key.group(2)), equalTo(false)));
	}

	@Example
	public void deletesAKey() throws Exception {
		final Matcher deleted = create();
		final Matcher kept = create();
		final HedgehogCli.Result result = key("delete", deleted.group(1));

		assertThat(result.exitCode(), equalTo(0));
		assertThat(key("list").out(), equalTo(kept.group(1) + System.lineSeparator()));
		assertThat(Files.readString(file).contains(deleted.group(2)), equalTo(false));
	}

	@Example
	public void refusesToDeleteAnUnknownKey() {
		final HedgehogCli.Result result = key("delete", "HKUNKNOWN00000000000");

		assertThat(result.exitCode(), equalTo(1));
		assertThat(result.err(), containsString("HKUNKNOWN00000000000"));
		result.assertNoStackTrace();
	}

	@Example
	public void asksForTheKeyToDelete() {
		final HedgehogCli.Result result = key("delete");

		assertThat(result.exitCode(), equalTo(2));
		assertThat(result.err(), containsString("Missing required parameter"));
	}

	@Example
	public void listsNothingBeforeTheFirstKey() {
		final HedgehogCli.Result result = key("list");

		assertThat(result.exitCode(), equalTo(0));
		assertThat(result.out(), equalTo(""));
	}

	@Example
	public void groupsTheKeyCommands() {
		assertThat(HedgehogCli.run("s3", "key").exitCode(), equalTo(2));
		assertThat(HedgehogCli.run("s3", "key", "--help").out(), containsString("create"));
	}
}

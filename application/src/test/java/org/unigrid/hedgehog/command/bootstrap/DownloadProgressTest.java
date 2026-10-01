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

package org.unigrid.hedgehog.command.bootstrap;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.TreeMap;
import java.util.stream.Collectors;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;

public class DownloadProgressTest {
	private static final int TENTH = 10;
	private static final String PREFIX = "Downloading ";

	private static List<Integer> reported(List<Integer> percents) {
		final ByteArrayOutputStream out = new ByteArrayOutputStream();
		final DownloadProgress progress = new DownloadProgress(new PrintStream(out, true, StandardCharsets.UTF_8));

		percents.forEach(progress::accept);

		return out.toString(StandardCharsets.UTF_8).lines()
			.map(line -> Integer.valueOf(line.substring(PREFIX.length(), line.length() - 1))).toList();
	}

	private static List<Integer> tenthsOf(List<Integer> percents) {
		return percents.stream().map(percent -> percent / TENTH).toList();
	}

	@Property
	public void shouldReportTheFirstPercentOfEveryTenthItEnters(@ForAll List<@IntRange(max = 100) Integer> percents) {
		final List<Integer> growing = percents.stream().sorted().toList();
		final TreeMap<Integer, Integer> firstOfEveryTenth = growing.stream().filter(percent -> percent >= TENTH)
			.collect(Collectors.toMap(percent -> percent / TENTH, percent -> percent, (first, later) -> first,
				TreeMap::new)
			);

		assertThat(reported(growing), equalTo(List.copyOf(firstOfEveryTenth.values())));
	}

	@Property
	public void shouldReportOnlyWhatItWasToldOncePerTenth(@ForAll List<@IntRange(max = 100) Integer> percents) {
		final List<Integer> reported = reported(percents);

		assertThat(tenthsOf(reported), equalTo(tenthsOf(reported).stream().distinct().sorted().toList()));
		assertThat(percents.containsAll(reported), equalTo(true));
	}
}

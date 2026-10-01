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

package org.unigrid.hedgehog.command.option;

import java.util.List;
import java.util.stream.Stream;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import org.unigrid.hedgehog.Hedgehog;
import picocli.CommandLine;
import picocli.CommandLine.ParameterException;

/* Only parses, so no command ever runs and nothing reaches the network or the disk */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class OptionsCli {
	public static final int MAX_PORT = 65535;

	public static void parse(List<String> command, String... options) {
		new CommandLine(Hedgehog.class).parseArgs(Stream.concat(command.stream(), Stream.of(options))
			.toArray(String[]::new)
		);
	}

	public static ParameterException refusal(List<String> command, String... options) {
		try {
			parse(command, options);
		} catch (ParameterException ex) {
			return ex;
		}

		throw new AssertionError("Accepted " + List.of(options));
	}

	public static Arbitrary<String> hosts() {
		return Arbitraries.strings().withCharRange('a', 'z').numeric().withChars(".-").ofMinLength(1).ofMaxLength(40)
			.filter(host -> !host.startsWith("-"));
	}
}

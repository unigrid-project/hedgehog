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

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.PropagationMode;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;
import org.unigrid.hedgehog.model.option.OptionsCli;
import org.unigrid.hedgehog.jqwik.RestoreOptionsHook;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;

@AddLifecycleHook(value = RestoreOptionsHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class SporkReleaseTest {
	private static final List<String> DAEMON = List.of("daemon");

	/* The sporks the board signs with the keys of this release, and those keys */
	private static final List<Type> OF_THIS_RELEASE = List.of(Type.MINT_STORAGE, Type.MINT_SUPPLY,
		Type.VESTING_STORAGE, Type.STATISTICS_PUBKEY, Type.BOARD_KEYS);

	private static final List<Type> EVERY_KNOWN_TYPE = List.of(Type.MINT_STORAGE, Type.MINT_SUPPLY,
		Type.VESTING_STORAGE, Type.STORAGE, Type.LEDGER_BLACKLIST, Type.LEDGER_FEE, Type.COMMUNICATION,
		Type.STATISTICS_PUBKEY, Type.BOARD_KEYS);

	private static void run(final boolean preview) {
		if (preview) {
			OptionsCli.parse(DAEMON, "--preview");
		} else {
			OptionsCli.parse(DAEMON);
		}
	}

	private static boolean isOfThisRun(final Type type, final boolean preview) {
		return (preview ? EVERY_KNOWN_TYPE : OF_THIS_RELEASE).contains(type);
	}

	private static SporkDatabase databaseWithEverySpork() {
		final SporkDatabase database = SporkDatabase.builder().build();

		Arrays.stream(Type.values()).filter(type -> type != Type.UNDEFINED)
			.forEach(type -> database.set(GridSpork.create(type)));

		return database;
	}

	@Property
	public void shouldTakeOnlyTheTypesOfThisReleaseWithoutThePreview(@ForAll Type type) {
		run(false);
		assertThat(type.toString(), SporkRelease.isTaken(type), is(OF_THIS_RELEASE.contains(type)));
	}

	@Property
	public void shouldTakeEveryKnownTypeWithThePreview(@ForAll Type type) {
		run(true);
		assertThat(type.toString(), SporkRelease.isTaken(type), is(type != Type.UNDEFINED));
	}

	@Property
	public void shouldNeverTakeAnUndefinedType(@ForAll boolean preview) {
		run(preview);

		assertThat(SporkRelease.isTaken(Type.UNDEFINED), is(false));
		assertThat(SporkRelease.types().contains(Type.UNDEFINED), is(false));
		assertThat(SporkRelease.stored(databaseWithEverySpork(), Type.UNDEFINED), equalTo(Optional.empty()));
	}

	@Example
	public void shouldListTheTypesOfThisReleaseInTheOrderOfTheTypesWithoutThePreview() {
		run(false);
		assertThat(SporkRelease.types(), equalTo(OF_THIS_RELEASE));
	}

	@Example
	public void shouldListEveryKnownTypeInTheOrderOfTheTypesWithThePreview() {
		run(true);
		assertThat(SporkRelease.types(), equalTo(EVERY_KNOWN_TYPE));
	}

	/* A spork that a run with --preview stored stays in the database, and a run without it reads none of it */
	@Property
	public void shouldReadWhatTheDatabaseHoldsOnlyOfATypeOfThisRun(@ForAll Type type, @ForAll boolean preview) {
		final SporkDatabase database = databaseWithEverySpork();

		run(preview);

		if (isOfThisRun(type, preview)) {
			assertThat(type.toString(), SporkRelease.stored(database, type).orElseThrow(),
				sameInstance(database.get(type)));
		} else {
			assertThat(type.toString(), SporkRelease.stored(database, type), equalTo(Optional.empty()));
		}
	}

	@Property
	public void shouldReadAnUnsetTypeAsNothing(@ForAll Type type, @ForAll boolean preview) {
		run(preview);
		assertThat(type.toString(), SporkRelease.stored(SporkDatabase.builder().build(), type),
			equalTo(Optional.empty()));
	}
}

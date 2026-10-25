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

package org.unigrid.hedgehog;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import net.jqwik.api.Example;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import org.unigrid.hedgehog.model.network.schedule.PublishSporkSchedule;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.SporkDatabase;
import org.unigrid.hedgehog.server.rest.BootstrapResource;
import org.unigrid.hedgehog.server.rest.LedgerResource;
import org.unigrid.hedgehog.service.ledger.SporkLedgerAuthority;

/* What acts on a spork or sends it on goes by the sporks that count, so that a spork whose signer the board switched off
   decides nothing and is not spread */
public class SporkPolicyReadersTest {
	private static final Class<?>[] READERS = { SporkLedgerAuthority.class, LedgerResource.class,
		BootstrapResource.class, PublishSporkSchedule.class };

	private static final String NAMES = Arrays.stream(READERS).map(Class::getName).map(Pattern::quote)
		.collect(Collectors.joining("|"));

	private static final DescribedPredicate<JavaMethodCall> READS_A_SLOT = DescribedPredicate.describe(
		"read a slot of the spork database as it is", call -> call.getTargetOwner().isEquivalentTo(SporkDatabase.class)
			&& call.getName().startsWith("get")
	);

	private static JavaClasses production() throws Exception {
		return new ClassFileImporter().importPath(Path.of(Hedgehog.class.getProtectionDomain().getCodeSource()
			.getLocation().toURI()));
	}

	@Example
	public void shouldReadSporksOnlyAsTheyCount() throws Exception {
		final JavaClasses production = production();

		noClasses().that().belongToAnyOf(READERS).should().callMethodWhere(READS_A_SLOT).check(production);
		classes().that().haveNameMatching(NAMES).should().callMethod(SporkDatabase.class, "counting",
			GridSpork.Type.class).check(production);
	}
}

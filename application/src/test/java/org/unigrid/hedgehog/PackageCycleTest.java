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

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.nio.file.Path;
import net.jqwik.api.Example;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;

public class PackageCycleTest {
	private static JavaClasses production() throws Exception {
		/* Importing the production classes root keeps test classes out and works on the module path */
		final Path root = Path.of(Hedgehog.class.getProtectionDomain().getCodeSource().getLocation().toURI());
		final JavaClasses classes = new ClassFileImporter().importPath(root);

		assertThat(classes.size(), greaterThan(0));
		return classes;
	}

	@Example
	public void shouldHaveNoCyclesBetweenPackages() throws Exception {
		slices().matching("(**)").should().beFreeOfCycles().check(production());
	}

	/* The handlers, the schedules and the resources call the ledger service, which is the part of the node that
	   knows nothing of them: whatever they share belongs with them or below the service, not in it */
	@Example
	public void shouldLetTheTransportCallTheLedgerServiceAndNotTheOtherWayRound() throws Exception {
		noClasses().that().resideInAPackage("..service.ledger..").should().dependOnClassesThat()
			.resideInAnyPackage("..model.network.handler..", "..model.network.schedule..", "..server..")
			.check(production());
	}
}

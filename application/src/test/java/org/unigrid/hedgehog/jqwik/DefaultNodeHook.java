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

package org.unigrid.hedgehog.jqwik;

import java.util.List;
import me.alexpanov.net.FreePortFinder;
import net.jqwik.api.lifecycle.BeforeContainerHook;
import net.jqwik.api.lifecycle.ContainerLifecycleContext;
import org.unigrid.hedgehog.model.option.OptionsCli;
import org.unigrid.hedgehog.server.rest.RestToken;

/* Parses the options of a daemon run without --preview, on ports of its own and with no seeds, before the container
   of the test class starts. Each test class has a JVM of its own, so the options need no restoring. */
public class DefaultNodeHook implements BeforeContainerHook {
	@Override
	public void beforeContainer(ContainerLifecycleContext context) {
		context.optionalContainerClass().ifPresent(container -> {
			/* The topology thread of the P2P server asks for the container while it starts, before the harness
			   has named it */
			NamedCDIProvider.NAME_REFERENCE.set(container.getSimpleName());
			parseTheOptions();
		});
	}

	/* The S3 endpoint is on once the preview is, so it is given a port of its own as well */
	private static void parseTheOptions() {
		final int netPort = FreePortFinder.findFreeLocalPort();
		final int restPort = FreePortFinder.findFreeLocalPort(netPort + 1);
		final int s3Port = FreePortFinder.findFreeLocalPort(restPort + 1);

		OptionsCli.parse(List.of("daemon"), "--nethost", "localhost", "--netport", String.valueOf(netPort),
			"--resthost", "localhost", "--restport", String.valueOf(restPort), "--resttoken", RestToken.generate(),
			"--no-seeds", "--s3host", "localhost", "--s3port", String.valueOf(s3Port));
	}
}

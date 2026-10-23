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

package org.unigrid.hedgehog.command.cli.spork;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.unigrid.hedgehog.model.JsonConfiguration;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.StorageSpork.SporkData;
import picocli.CommandLine.Command;
import picocli.CommandLine.ParameterException;

/* The data replaces the parameters as a whole, and a parameter it leaves out takes its default */
@Command(name = "storage")
public class Storage extends SporkCommand<SporkData> {
	private static final ObjectMapper JSON = new JsonConfiguration().getContext(SporkData.class);

	@Override
	protected String location() {
		return "/gridspork/storage";
	}

	@Override
	protected GridSpork.Type type() {
		return GridSpork.Type.STORAGE;
	}

	@Override
	protected SporkData read(final String data) {
		try {
			return JSON.readValue(data, SporkData.class);
		} catch (JsonProcessingException ex) {
			throw new ParameterException(spec().commandLine(), "The storage parameters are not accepted: "
				+ ex.getOriginalMessage(), ex);
		}
	}

	@Override
	protected void apply(final SporkData data, final GridSpork spork) {
		spork.setData(data);
	}
}

/*
    Unigrid Hedgehog
    Copyright © 2021-2023 Stiftelsen The Unigrid Foundation, UGD Software AB

    Stiftelsen The Unigrid Foundation (org. nr: 802482-2408)
    UGD Software AB (org. nr: 559339-5824)

    This program is free software: you can redistribute it and/or modify it under the terms of the
    addended GNU Affero General Public License as published by the The Unigrid Foundation and
    the Free Software Foundation, version 3 of the License (see COPYING and COPYING.addendum).

    This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
    even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU Affero General Public License and the addendum for more details.

    You should have received an addended copy of the GNU Affero General Public License with this program.
    If not, see <http://www.gnu.org/licenses/> and <https://github.com/unigrid-project/hedgehog>.
 */

package org.unigrid.hedgehog.model.network.channel;

import java.net.URL;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import org.reflections.util.ClasspathHelper;

public class ChannelCollectorTest {
	@ChannelCodec
	public static class TestMultiCodec {
	}

	@ChannelCodec(ChannelCodec.Type.SERVER)
	public static class TestServerCodec {
	}

	@Property
	public void shouldBeABleToCollectCodecs(@ForAll ChannelCodec.Type type) {
		System.out.println("running");
		System.out.println(ChannelCollector.collectCodecs(ChannelCodec.Type.CLIENT,  new URL[] { ClasspathHelper.forClass(ChannelCollectorTest.class) } ));
	}
}

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

package org.unigrid.hedgehog.server.s3;

import java.util.List;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.PropagationMode;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import org.unigrid.hedgehog.command.option.OptionsCli;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import org.unigrid.hedgehog.jqwik.RestoreOptionsHook;
import org.unigrid.hedgehog.s3.cache.CachingBlobStore;
import org.unigrid.hedgehog.s3.server.S3ServerConfig;
import org.unigrid.hedgehog.service.storage.StorageBlobStore;

@AddLifecycleHook(value = RestoreOptionsHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class S3GatewayTest {
	@Provide
	public Arbitrary<String> provideHost() {
		return OptionsCli.hosts();
	}

	@Property
	public void shouldServeOverTlsWhereTheOptionsSay(@ForAll("provideHost") String host,
		@ForAll @IntRange(min = 1, max = OptionsCli.MAX_PORT) int port, @ForAll("provideHost") String domain) {

		OptionsCli.parse(List.of("daemon"), "--s3host", host, "--s3port", String.valueOf(port), "--s3domain", domain);

		assertThat(S3Gateway.config(), equalTo(S3ServerConfig.builder().host(host).port(port).domain(domain).build()));
	}

	@Example
	public void shouldServeTheStorageThroughACache() {
		OptionsCli.parse(List.of("daemon"));

		assertThat(S3Gateway.cached(new StorageBlobStore(null)), instanceOf(CachingBlobStore.class));
	}

	@Property
	public void shouldSizeTheCacheAsTheOptionsSay(@ForAll @LongRange(min = Long.MIN_VALUE, max = -1) long size) {
		OptionsCli.parse(List.of("daemon"), "--s3cachesize=" + size);

		assertThrows(IllegalArgumentException.class, () -> S3Gateway.cached(new StorageBlobStore(null)));
	}
}

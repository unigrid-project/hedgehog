/*
    Unigrid Hedgehog
    Copyright © 2021-2022 The Unigrid Foundation, UGD Software AB

    This program is free software: you can redistribute it and/or modify it under the terms of the
    addended GNU Affero General Public License as published by the The Unigrid Foundation and
    the Free Software Foundation, version 3 of the License (see COPYING and COPYING.addendum).

    This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
    even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU Affero General Public License and the addendum for more details.

    You should have received an addended copy of the GNU Affero General Public License with this program.
    If not, see <http://www.gnu.org/licenses/> and <https://github.com/unigrid-project/hedgehog>.
 */
package org.unigrid.hedgehog.server.rest;

import io.findify.s3mock.S3Mock;
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.BeforeExample;
import net.jqwik.api.lifecycle.AfterExample;
import org.unigrid.hedgehog.client.RestClient;


public class StorageBucketTest extends BaseRestClientTest {
//	S3Mock api;

//	@Inject	
//	protected Bucket bucket;

	
//	@BeforeExample
//	public void before() {
//		api = new S3Mock.Builder().withPort(8001).withInMemoryBackend().build();
//		api.start();
//	}
//
//	@AfterExample
//	public void after() {
//		api.shutdown();
//	}

//	@Example
//	@SneakyThrows
//	public void createBucket() {
//		final RestClient client = new RestClient(server.getRest().getHostName(), server.getRest().getPort());
//		client.get("/bucket");
//		client.close();
//	}
	
	@Example
	@SneakyThrows
	public void listBucket() {
		final RestClient client = new RestClient(server.getRest().getHostName(), server.getRest().getPort());
		client.get("/bucket");
		client.close();
	}
	
//	@Example
//	@SneakyThrows
//	public void deleteBucket() {
//		RestClient client = new RestClient(server.getRest().getHostName(), server.getRest().getPort());
//		client.get("/bucket/delete");
//		client.close();
//	}
}

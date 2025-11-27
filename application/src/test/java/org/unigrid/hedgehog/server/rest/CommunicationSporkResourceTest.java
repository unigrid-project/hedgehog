/*
    Unigrid Hedgehog
    Copyright © 2021-2025 Stiftelsen The Unigrid Foundation

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

package org.unigrid.hedgehog.server.rest;

import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.net.ssl.SSLContext;
import lombok.SneakyThrows;
import mockit.Mock;
import mockit.MockUp;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeProperty;
import net.jqwik.api.lifecycle.BeforeTry;
import org.apache.commons.lang3.SerializationUtils;
import org.glassfish.jersey.client.ClientConfig;
import org.glassfish.jersey.jackson.internal.jackson.jaxrs.json.JacksonJaxbJsonProvider;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;
import static org.hamcrest.Matchers.startsWith;
import org.unigrid.hedgehog.client.ResponseOddityException;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import org.unigrid.hedgehog.model.JsonConfiguration;
import org.unigrid.hedgehog.model.crypto.Signature;
import org.unigrid.hedgehog.model.network.Topology;
import org.unigrid.hedgehog.model.network.packet.Packet;
import org.unigrid.hedgehog.model.network.packet.PublishSpork;
import org.unigrid.hedgehog.model.spork.CommunicationFlag;
import org.unigrid.hedgehog.model.spork.CommunicationSpork;
import org.unigrid.hedgehog.model.spork.CommunicationSpork.SporkData;
import org.unigrid.hedgehog.model.spork.CommunicationSporkTest;
import static org.unigrid.hedgehog.model.spork.CommunicationSporkTest.MAX_UNSIGNED_INT;
import static org.unigrid.hedgehog.model.spork.CommunicationSporkTest.MAX_UNSIGNED_SHORT;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.SporkDatabase;

public class CommunicationSporkResourceTest extends BaseRestClientTest {
	private static final String URL = "/gridspork/communication";
	private static final long MAX_ENTRY_BYTES = 1_048_576L;

	@Inject
	private SporkDatabase sporkDatabase;

	private final List<Packet> relayed = new ArrayList<>();

	/* The client of the other tests drops the body of a refusal, and the body is what several tests here check */
	private Client rawClient;

	@Provide
	public Arbitrary<SporkData> validData() {
		return CommunicationSporkTest.sporkDataAcrossTheBounds().filter(CommunicationSporkTest::accepts);
	}

	@Provide
	public Arbitrary<SporkData> invalidData() {
		return CommunicationSporkTest.sporkDataAcrossTheBounds()
			.filter(data -> !CommunicationSporkTest.accepts(data));
	}

	/* Cast to an int these wrap to 0 and to 1, so a body that was truncated instead of refused could pass validation */
	@Provide
	public Arbitrary<Long> groupSizesBeyondTheRangeOfAnInt() {
		return Arbitraries.of(MAX_UNSIGNED_INT + 1, MAX_UNSIGNED_INT + 2);
	}

	private Response put(final SporkData data, final String privateKey) throws ResponseOddityException {
		return client.putWithHeaders(URL, Entity.json(data),
			new MultivaluedHashMap<>(Map.of("privateKey", privateKey)));
	}

	private Optional<SporkData> stored() throws ResponseOddityException {
		final Response response = client.get(URL);

		if (Status.fromStatusCode(response.getStatus()) == Status.NO_CONTENT) {
			return Optional.empty();
		}

		return Optional.of(response.readEntity(CommunicationSpork.class).getData());
	}

	@BeforeProperty
	private void collectRelayedPackets() {
		new MockUp<Topology>() {
			@Mock public void sendAll(Packet packet, Topology topology, Optional<?> consumer) {
				relayed.add(packet);
			}
		};
	}

	@BeforeTry
	private void forgetRelayedPackets() {
		relayed.clear();
	}

	@BeforeTry
	@SneakyThrows
	private void openRawClient() {
		final SSLContext context = SSLContext.getInstance("ssl");
		context.init(null, InsecureTrustManagerFactory.INSTANCE.getTrustManagers(), null);

		rawClient = ClientBuilder.newBuilder()
			.hostnameVerifier((hostname, session) -> true)
			.sslContext(context)
			.withConfig(new ClientConfig()
				.register(JacksonJaxbJsonProvider.class)
				.register(new JsonConfiguration()))
			.build();
	}

	@AfterTry
	private void closeRawClient() {
		rawClient.close();
	}

	private Response putRaw(final Entity<?> body, final String privateKey) {
		final String url = String.format("https://%s:%d%s", server.getRest().getHostName(),
			server.getRest().getPort(), URL);

		return rawClient.target(url).request().header("privateKey", privateKey).put(body);
	}

	private static Status statusOf(final Response response) {
		return Status.fromStatusCode(response.getStatus());
	}

	private void startFromAnEmptyDatabase() {
		sporkDatabase.setCommunicationSpork(null);
	}

	private CommunicationSpork setSpork(final Signature signer, final SporkData data) {
		assertThat(statusOf(putRaw(Entity.json(data), signer.getPrivateKey())), equalTo(Status.OK));
		return sporkDatabase.getCommunicationSpork();
	}

	/* An accepted request stores a new instance, so the same instance with the same content proves no change */
	private Response putLeavingNoTrace(final String privateKey, final Entity<?> body) {
		final CommunicationSpork before = sporkDatabase.getCommunicationSpork();
		final String content = before.toString();

		relayed.clear();

		final Response response = putRaw(body, privateKey);

		assertThat(sporkDatabase.getCommunicationSpork(), sameInstance(before));
		assertThat(before.toString(), equalTo(content));
		assertThat(relayed, is(empty()));
		return response;
	}

	private List<GridSpork> relayedSporks() {
		return relayed.stream().map(packet -> ((PublishSpork) packet).getGridSpork()).toList();
	}

	private static SporkData otherThan(final SporkData data) {
		final SporkData other = SerializationUtils.clone(data);

		other.setGroupSize(data.getGroupSize() % MAX_UNSIGNED_SHORT + 1);
		return other;
	}

	private static SporkData atTheMaxima() {
		final SporkData data = new SporkData();

		data.setGroupSize(MAX_UNSIGNED_SHORT);
		data.setReorganizePeriodSeconds(MAX_UNSIGNED_INT);
		data.setChainsPerGroup(MAX_UNSIGNED_SHORT);
		data.setClientsPerChain(MAX_UNSIGNED_SHORT);
		data.setChainCapacityBytes(MAX_UNSIGNED_INT);
		data.setRetentionSeconds(MAX_UNSIGNED_SHORT);
		data.setMaxEntryBytes(MAX_ENTRY_BYTES);
		data.setSessionIdleSeconds(MAX_UNSIGNED_SHORT);
		data.setCommunicationFlags(CommunicationFlag.of(CommunicationFlag.values()));
		return data;
	}

	private static SporkData withGroupSize(final int groupSize) {
		final SporkData data = new SporkData();

		data.setGroupSize(groupSize);
		return data;
	}

	@SneakyThrows
	@Property(tries = 10)
	public void storesValidParameters(@ForAll("provideSignature") final Signature signature,
		@ForAll("validData") final SporkData data) {

		assertThat(Status.fromStatusCode(put(data, signature.getPrivateKey()).getStatus()), equalTo(Status.OK));
		assertThat(stored(), equalTo(Optional.of(data)));
	}

	@SneakyThrows
	@Property(tries = 5)
	public void rejectsInvalidParameters(@ForAll("provideSignature") final Signature signature,
		@ForAll("invalidData") final SporkData data) {

		final Optional<SporkData> before = stored();

		/* The client refuses any status it does not expect, a 400 among them */
		final ResponseOddityException refusal = assertThrows(ResponseOddityException.class,
			() -> put(data, signature.getPrivateKey()));

		assertThat(refusal.getMessage(), startsWith("400 "));
		assertThat(stored(), equalTo(before));
	}

	/* The trusted signature is only drawn to install a network key that the fresh one then fails to match */
	@SneakyThrows
	@Property(tries = 3)
	public void rejectsUntrustedKeys(@ForAll("provideSignature") final Signature trusted,
		@ForAll("validData") final SporkData data) {

		final Optional<SporkData> before = stored();

		assertThat(Status.fromStatusCode(put(data, new Signature().getPrivateKey()).getStatus()),
			equalTo(Status.UNAUTHORIZED));
		assertThat(stored(), equalTo(before));
	}

	@SneakyThrows
	@Property(tries = 3)
	public void checksTheKeyBeforeTheData(@ForAll("provideSignature") final Signature trusted,
		@ForAll("invalidData") final SporkData data) {

		assertThat(statusOf(putRaw(Entity.json(data), new Signature().getPrivateKey())),
			equalTo(Status.UNAUTHORIZED));
	}

	@Property(tries = 1)
	public void explainsARefusalWithTheValidationMessage(@ForAll("provideSignature") final Signature signature) {
		final Response response = putRaw(Entity.json(withGroupSize(0)), signature.getPrivateKey());

		assertThat(statusOf(response), equalTo(Status.BAD_REQUEST));
		assertThat(response.readEntity(String.class), containsString("groupSize must be 1-65535, not 0"));
	}

	@Property(tries = 3)
	public void signsAndArchivesEachStoredSpork(@ForAll("provideSignature") final Signature signature,
		@ForAll("validData") final SporkData first) {

		final SporkData second = otherThan(first);

		startFromAnEmptyDatabase();

		final CommunicationSpork stored = setSpork(signature, first);
		assertThat(stored.isValidSignature(), is(true));

		final CommunicationSpork replaced = setSpork(signature, second);
		assertThat(replaced.isValidSignature(), is(true));

		final SporkData current = replaced.getData();
		final SporkData previous = replaced.getPreviousData();

		assertThat(current, equalTo(second));
		assertThat(previous, equalTo(first));
		assertThat(replaced.getPreviousTimeStamp(), equalTo(stored.getTimeStamp()));
	}

	@Property(tries = 3)
	public void relaysEachStoredSporkToThePeers(@ForAll("provideSignature") final Signature signature,
		@ForAll("validData") final SporkData first) {

		final CommunicationSpork stored = setSpork(signature, first);
		assertThat(relayedSporks(), contains(sameInstance(stored)));

		final CommunicationSpork replaced = setSpork(signature, otherThan(first));
		assertThat(relayedSporks(), contains(sameInstance(stored), sameInstance(replaced)));
	}

	@SneakyThrows
	@Property(tries = 3)
	public void leavesNoTraceOfAnUntrustedKeyOrInvalidData(@ForAll("provideSignature") final Signature signature,
		@ForAll("validData") final SporkData stored, @ForAll("invalidData") final SporkData invalid) {

		setSpork(signature, stored);

		final Response untrusted = putLeavingNoTrace(new Signature().getPrivateKey(),
			Entity.json(otherThan(stored)));

		assertThat(statusOf(untrusted), equalTo(Status.UNAUTHORIZED));

		final Response refused = putLeavingNoTrace(signature.getPrivateKey(), Entity.json(invalid));
		assertThat(statusOf(refused), equalTo(Status.BAD_REQUEST));
	}

	@SneakyThrows
	@Property(tries = 1)
	public void storesEveryFieldAtItsMaximum(@ForAll("provideSignature") final Signature signature) {
		final SporkData data = atTheMaxima();

		assertThat(statusOf(putRaw(Entity.json(data), signature.getPrivateKey())), equalTo(Status.OK));
		assertThat(stored(), equalTo(Optional.of(data)));
	}

	@Property(tries = 1)
	public void refusesAGroupSizeAboveItsMaximum(@ForAll("provideSignature") final Signature signature) {
		setSpork(signature, new SporkData());

		final Response response = putLeavingNoTrace(signature.getPrivateKey(),
			Entity.json(withGroupSize(MAX_UNSIGNED_SHORT + 1)));

		assertThat(statusOf(response), equalTo(Status.BAD_REQUEST));
		assertThat(response.readEntity(String.class), containsString("groupSize must be 1-65535, not 65536"));
	}

	@Property(tries = 2)
	public void refusesAGroupSizeBeyondTheRangeOfAnInt(@ForAll("provideSignature") final Signature signature,
		@ForAll("groupSizesBeyondTheRangeOfAnInt") final long groupSize) {

		setSpork(signature, new SporkData());

		final Response response = putLeavingNoTrace(signature.getPrivateKey(),
			Entity.json("{\"groupSize\":" + groupSize + "}"));

		assertThat(statusOf(response), equalTo(Status.BAD_REQUEST));
		assertThat(response.readEntity(String.class), containsString("groupSize"));
	}
}

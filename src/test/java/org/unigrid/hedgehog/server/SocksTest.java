package org.unigrid.hedgehog.server;

import jakarta.inject.Inject;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.core.Configuration;
import lombok.SneakyThrows;
import me.alexpanov.net.FreePortFinder;
import mockit.Expectations;
import mockit.Invocation;
import mockit.Mock;
import mockit.MockUp;
import mockit.Mocked;
import net.jqwik.api.lifecycle.BeforeTry;
import org.unigrid.hedgehog.command.option.SocksOptions;
import org.unigrid.hedgehog.jqwik.BaseMockedWeldTest;
import org.unigrid.hedgehog.server.socks.SocksServer;
import net.jqwik.api.Example;
import org.glassfish.jersey.client.ClientConfig;
import org.glassfish.jersey.client.JerseyClientBuilder;
import org.unigrid.hedgehog.client.RestClient;
import org.unigrid.hedgehog.model.cdi.CDIUtil;
import org.glassfish.jersey.client.HttpUrlConnectorProvider;

public class SocksTest extends BaseMockedWeldTest {
	@Mocked
	protected SocksOptions socksOptions;
	
	@Inject
	private SocksServer socksServer;
	
	@BeforeTry
	public void before() {
		new Expectations() {
			{
				SocksOptions.getHost(); result = "localhost";
				SocksOptions.getPort(); result = FreePortFinder.findFreeLocalPort();
			}
		};
		new MockUp<JerseyClientBuilder>() {
			@Mock @SneakyThrows public JerseyClientBuilder withConfig(Invocation invocation, Configuration c){
				System.out.println("Is it mocking?");
				final SocksConnectionFactory connectionFactory = new SocksConnectionFactory(socksServer.getHostName(), socksServer.getPort());
				final HttpUrlConnectorProvider connectorProvider = new HttpUrlConnectorProvider();
				connectorProvider.connectionFactory(connectionFactory);
				
				

				return invocation.proceed(((ClientConfig) c).connectorProvider(connectorProvider));
			}
		};
	}
	@SneakyThrows
	@Example
	public void shouldBeAbleToStart(){
		System.out.println(ClientBuilder.newBuilder());
		System.out.println("Is this started? SOCKSSSSSSS");
		CDIUtil.instantiate(socksServer);

		
		final RestClient client = new RestClient("dog.ceo", 443);
		
		System.out.println(socksServer.getPort());

		System.out.println(client.get("/api/breeds/list/all"));
		client.close();
		//Thread.sleep(10000);
	}
}

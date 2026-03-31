package org.unigrid.hedgehog.command.cli.spork;

import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;

import org.unigrid.hedgehog.command.util.RestClientCommand;
import org.unigrid.hedgehog.model.Json;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

@Command(name = "mint-storage")
public class MintStorage implements Runnable {

	@Spec
	private CommandSpec spec;

	@Override
	public void run() {
		Object parent = spec.parent().userObject();

		/* =======================
		   GET: läsa mint-storage
		   ======================= */
		if (parent instanceof GridSporkGet) {

			new RestClientCommand("GET", "/gridspork/mint-storage") {

				@Override
				protected String getLocation() {
					return pathTemplate;
				}

				@Override
				protected void execute(Response response) {
					System.out.println(Json.parse(response.readEntity(String.class)));
				}

			}.run();

		/* =======================
		   PUT: uppdatera mint-storage
		   ======================= */
		} else if (parent instanceof GridSporkGrow) {

			if (GridSporkGrow.getKey() == null || GridSporkGrow.getData() == null) {
				System.out.println("Both private key and data must be specified");
				return;
			}

			RestClientCommand cmd = new RestClientCommand("PUT", "/gridspork/mint-storage") {

				@Override
				protected String getLocation() {
					return pathTemplate;
				}

				@Override
				protected Entity<?> getEntity() {
					return Entity.text(GridSporkGrow.getData());
				}

				@Override
				protected void execute(Response response) {
					// ingen output
				}
			};

			MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();
			headers.add("privateKey", GridSporkGrow.getKey());

			cmd.setHeaders(headers);
			cmd.run();

		} else {
			throw new UnsupportedOperationException(
					"Unsupported parent command: " + parent.getClass()
			);
		}
	}
}

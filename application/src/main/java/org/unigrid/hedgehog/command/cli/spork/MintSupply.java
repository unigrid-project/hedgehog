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
	package org.unigrid.hedgehog.command.cli.spork;

	import jakarta.ws.rs.client.Entity;
	import jakarta.ws.rs.core.MultivaluedHashMap;
	import jakarta.ws.rs.core.MultivaluedMap;
	import jakarta.ws.rs.core.Response;
	
	import org.unigrid.hedgehog.command.cli.GridSporkSet;
	import org.unigrid.hedgehog.command.util.RestClientCommand;
	import org.unigrid.hedgehog.model.Json;
	import picocli.CommandLine.Command;
	import picocli.CommandLine.Model.CommandSpec;
	import picocli.CommandLine.Spec;
	
	/**
	 * CLI-kommando för att läsa eller uppdatera ("minta") mint-supply via GridSpork API.
	 */
	@Command(name = "mint-supply")
	public class MintSupply implements Runnable {
	
		@Spec
		private CommandSpec spec;
	
		@Override
		public void run() {
			Object parent = spec.parent().userObject();
	
			/* =======================
			   GET: läs mint-supply
			   ======================= */
			if (parent instanceof GridSporkGet) {
	
				new RestClientCommand("GET", "/gridspork/mint-supply") {
	
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
			   PUT: uppdatera mint-supply
			   ======================= */
			} else if (parent instanceof GridSporkSet) {
	
				if (GridSporkSet.getKey() == null || GridSporkSet.getData() == null) {
					System.out.println("Both private key and data must be specified");
					return;
				}
	
				RestClientCommand cmd = new RestClientCommand("PUT", "/gridspork/mint-supply") {
	
					@Override
					protected String getLocation() {
						return pathTemplate;
					}
	
					@Override
					protected Entity<?> getEntity() {
						return Entity.text(GridSporkSet.getData());
					}
	
					@Override
					protected void execute(Response response) {
						// Ingen output behövs
					}
				};
	
				MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();
				headers.add("privateKey", GridSporkSet.getKey());
	
				cmd.setHeaders(headers);
				cmd.run();
	
			} else {
				throw new UnsupportedOperationException(
						"Unsupported parent command: " + parent.getClass()
				);
			}
		}
	}
	
package org.unigrid.hedgehog.server.socks;

public interface PasswordAuth {

	public boolean auth(String user, String password);
}

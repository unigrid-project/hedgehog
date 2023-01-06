package org.unigrid.hedgehog.server;

import java.io.IOException;
import java.net.Authenticator;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.Proxy.Type;
import java.net.SocketAddress;
import java.net.URL;

import org.glassfish.jersey.client.HttpUrlConnectorProvider.ConnectionFactory;

public class SocksConnectionFactory implements ConnectionFactory {
	private final Proxy proxy;
	
	public SocksConnectionFactory(String hostname, int port) {
		Authenticator.setDefault(new Authenticator() {
			protected PasswordAuthentication getPasswordAuthentication() {
				PasswordAuthentication p = new PasswordAuthentication("test", "test".toCharArray());
				return p;
			}
		});
		SocketAddress socksAddr = new InetSocketAddress(hostname, port);
		this.proxy = new Proxy(Type.SOCKS, socksAddr);
	}
	
	public HttpURLConnection getConnection(URL url) throws IOException {
		return (HttpURLConnection) url.openConnection(proxy);
	}

}

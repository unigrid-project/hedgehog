/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package org.unigrid.hedgehog.server.socks;

import java.io.IOException;
import java.util.Properties;

public class PropertiesPasswordAuth implements PasswordAuth {

	private static Properties properties;
	
	static {
		properties = new Properties();
		try {
			properties.load(PropertiesPasswordAuth.class.getResourceAsStream("/password.properties"));
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	public boolean auth(String user, String password) {
		String configPasword = properties.getProperty(user);
		if(configPasword != null) {
			if(password.equals(configPasword)) {
				return true;
			}
		}
		return false;
	}

}
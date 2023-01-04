package org.unigrid.nativecdi;

import java.util.Iterator;
import java.util.ServiceLoader;

public class Main2 {

	public static void main(String[] args) {
		ServiceLoader<Application> loader = ServiceLoader.load(Application.class);
		Iterator<Application> iterator = loader.iterator();
		if (iterator.hasNext()) {
			System.out.println("Service found");
		} else {
			System.out.println("NOT FOUND!!!");
		}
	}
}

/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package net.saga.graalvm.weld.test;

import javax.enterprise.inject.se.SeContainer;
import javax.enterprise.inject.se.SeContainerInitializer;
import org.jboss.weld.environment.se.Weld;

/**
 *
 * @author summers
 */
public class Main {

    public static void main(String[] args) throws Exception {
        Weld weld = Weld.class.newInstance();
         SeContainerInitializer initializer = SeContainerInitializer.newInstance();
        /** disable discovery and register classes manually */
        try (SeContainer container = initializer.disableDiscovery().addPackages(Main.class).initialize()) {
            container.select(Application.class);
        }
    }
}

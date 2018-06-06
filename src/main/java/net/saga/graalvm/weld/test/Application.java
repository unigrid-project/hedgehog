/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package net.saga.graalvm.weld.test;

import javax.annotation.Priority;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.context.Initialized;
import javax.enterprise.event.Observes;
import javax.inject.Inject;
import javax.interceptor.Interceptor;

/**
 *
 * @author summers
 */
@ApplicationScoped
public class Application {

    @Inject
    private transient Logger logger;

    public void init(@Observes @Priority(Interceptor.Priority.APPLICATION - 100)
            @Initialized(ApplicationScoped.class) Object init) throws Exception {
        logger.info(init.toString());
        start();
    }

    private void start() throws Exception {
        logger.info("Staring Application ...");
       
    }
}

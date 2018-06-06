/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package net.saga.graalvm.weld.test;

import javax.enterprise.context.ApplicationScoped;

/**
 *
 * @author summers
 */
@ApplicationScoped
public class Logger {    

    void info(String msg) {
        java.util.logging.Logger.getAnonymousLogger().info(msg);
    }
    
}

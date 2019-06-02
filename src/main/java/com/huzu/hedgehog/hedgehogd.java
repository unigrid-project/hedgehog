package com.huzu.hedgehog;

import org.apache.commons.daemon.Daemon;
import org.apache.commons.daemon.DaemonContext;

public class hedgehogd implements Daemon{

    @Override
    public void init(DaemonContext context) throws Exception {
        System.out.println("MyDaemon init...");
    }

    @Override
    public void start() throws Exception {
        System.out.println("MyDaemon start...");
    }

    @Override
    public void stop() throws Exception {
        System.out.println("MyDaemon stop...");
    }

    @Override
    public void destroy() {
        System.out.println("MyDaemon destroy...");
    }
}

/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package net.saga.graalvm.weld.test;

import java.util.Iterator;

/**
 *
 * @author summers
 */
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

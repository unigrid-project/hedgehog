/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package net.saga.graalvm.weld.test;

import com.oracle.svm.core.annotate.AutomaticFeature;
import com.oracle.svm.core.jdk.Resources;
import java.io.FileInputStream;
import java.io.IOException;
import org.graalvm.nativeimage.Feature;

/**
 *
 * @author summers
 */
@AutomaticFeature
public class Main2Feature implements Feature {

    @Override
    public void beforeAnalysis(BeforeAnalysisAccess access) {
        Feature.super.beforeAnalysis(access); 

        System.out.println("Registering resources");
        try (FileInputStream is = new FileInputStream("/home/summers/NetBeansProjects/graalvm-weld-test/src/main/resources/META-INF/services/net.saga.graalvm.weld.test.Application")) {
            int read = -1;
            while ((read = is.read()) != -1) {
                System.out.print((char)read);
            }
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
         try (FileInputStream is = new FileInputStream("/home/summers/NetBeansProjects/graalvm-weld-test/src/main/resources/META-INF/services/net.saga.graalvm.weld.test.Application")) {
            Resources.registerResource("META-INF/services/net.saga.graalvm.weld.test.Application", is);
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }
 
    
    
}

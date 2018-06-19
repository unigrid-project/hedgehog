/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package net.saga.graalvm.weld.test;

import com.oracle.svm.core.annotate.AutomaticFeature;
import com.oracle.svm.core.jdk.Resources;
import java.io.IOException;
import java.io.InputStream;
import org.graalvm.nativeimage.Feature;
import org.graalvm.nativeimage.RuntimeReflection;
import org.jboss.weld.environment.se.Weld;

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
        
        try (InputStream is = Main2Feature.class.getClassLoader().getResourceAsStream("META-INF/services/net.saga.graalvm.weld.test.Application")) {
            Resources.registerResource("META-INF/services/net.saga.graalvm.weld.test.Application", is);
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
        
        try (InputStream is = Main2Feature.class.getClassLoader().getResourceAsStream("META-INF/services/com.oracle.truffle.api.TruffleRuntimeAccess")) {
            Resources.registerResource("META-INF/services/com.oracle.truffle.api.TruffleRuntimeAccess", is);
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
        
        try (InputStream is = Main2Feature.class.getClassLoader().getResourceAsStream("META-INF/services/javax.enterprise.inject.se.SeContainerInitializer")) {
            Resources.registerResource("META-INF/services/javax.enterprise.inject.se.SeContainerInitializer", is);
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
        
        try (InputStream is = Main2Feature.class.getClassLoader().getResourceAsStream("META-INF/services/javax.enterprise.inject.spi.CDIProvider")) {
            Resources.registerResource("META-INF/services/javax.enterprise.inject.spi.CDIProvider", is);
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
        
        try (InputStream is = Main2Feature.class.getClassLoader().getResourceAsStream("META-INF/services/javax.enterprise.inject.spi.Extension")) {
            Resources.registerResource("META-INF/services/javax.enterprise.inject.spi.Extension", is);
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
        
        RuntimeReflection.registerForReflectiveInstantiation(Weld.class);
        
    }
 
    
    
}

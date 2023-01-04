/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.unigrid.nativecdi.graal;

import com.oracle.svm.core.annotate.AutomaticFeature;
import java.io.IOException;
import java.io.InputStream;
//import com.oracle.svm.hosted.P;
import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeReflection;
import org.jboss.weld.environment.se.Weld;

@AutomaticFeature
public class Main2Feature implements Feature {

	@Override
	public void beforeAnalysis(BeforeAnalysisAccess access) {
		Feature.super.beforeAnalysis(access);

		System.out.println("Registering resources");

		//DyamicProxySupport
		/*try (InputStream is = Main2Feature.class.getClassLoader().getResourceAsStream("META-INF/services/net.saga.graalvm.weld.test.Application")) {
            Resources.registerResource("META-INF/services/net.saga.graalvm.weld.test.Application", is);
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
        
        try (InputStream is = Main2Feature.class.getClassLoader().getResourceAsStream("META-INF/services/com.oracle.truffle.api.TruffleRuntimeAccess")) {
            Resources.registerResource("META-INF/services/com.oracle.truffle.api.TruffleRuntimeAccess", is);
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
        */
        /*try (InputStream is = Main2Feature.class.getClassLoader().getResourceAsStream("META-INF/services/jakarta.enterprise.inject.se.SeContainerInitializer")) {
            Resources.registerResource("META-INF/services/jakarta.enterprise.inject.se.SeContainerInitializer", is);
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
        
        try (InputStream is = Main2Feature.class.getClassLoader().getResourceAsStream("META-INF/services/jakarta.enterprise.inject.spi.CDIProvider")) {
            Resources.registerResource("META-INF/services/jakarta.enterprise.inject.spi.CDIProvider", is);
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
        
        try (InputStream is = Main2Feature.class.getClassLoader().getResourceAsStream("META-INF/services/jakarta.enterprise.inject.spi.Extension")) {
            Resources.registerResource("META-INF/services/jakarta.enterprise.inject.spi.Extension", is);
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }

	RuntimeReflection.registerForReflectiveInstantiation(Weld.class);
*/
	}

}

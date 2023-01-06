package org.unigrid.nativecdi.graal;

import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.Discovery;
import jakarta.enterprise.inject.build.compatible.spi.ScannedClasses;
import jakarta.enterprise.inject.build.compatible.spi.Synthesis;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanCreator;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticComponents;

public class BuildExtension implements BuildCompatibleExtension {
	@Discovery
	public void addBean(ScannedClasses classes) {
		System.out.println("TRUFFLE!!!! " + classes);

		//classes.add(TestBeanRegistered.class.getName());
	}

	@Synthesis
	public void synthesizeBean(SyntheticComponents synth) {
		System.out.println("PARSNIP!!!! " + synth);
		/*synth.addBean(TestBean.class)
			.type(TestBean.class)
			.scope(ApplicationScoped.class)
			.createWith(SyntheticCreator.class);*/
	}

	/*public static class SyntheticCreator implements SyntheticBeanCreator<TestBean> {
		@Override
		public TestBean create(Instance<Object> lookup, Parameters params) {
			return () -> "synthetic";
		}

	}*/
}

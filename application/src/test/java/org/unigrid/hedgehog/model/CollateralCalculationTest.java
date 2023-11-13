package org.unigrid.hedgehog.model;

import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.NotBlank;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Example;
import net.jqwik.api.Provide;

public class CollateralCalculationTest {
	@Example
	public boolean shouldSomethingSomething() {
		final CollateralCalculation calculation = new CollateralCalculation();

		System.out.println(calculation.getCollateral(5000));
		return true;
	}
}

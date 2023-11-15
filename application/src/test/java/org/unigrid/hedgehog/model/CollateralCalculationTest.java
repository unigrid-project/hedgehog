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
		final Collateral calculation = new Collateral();

		System.out.println(calculation.get(5000));
		return true;
	}
}

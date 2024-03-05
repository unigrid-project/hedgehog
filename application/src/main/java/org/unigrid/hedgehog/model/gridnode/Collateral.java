/*
    Unigrid Hedgehog
    Copyright © 2021-2023 Stiftelsen The Unigrid Foundation, UGD Software AB

    Stiftelsen The Unigrid Foundation (org. nr: 802482-2408)
    UGD Software AB (org. nr: 559339-5824)

    This program is free software: you can redistribute it and/or modify it under the terms of the
    addended GNU Affero General Public License as published by the The Unigrid Foundation and
    the Free Software Foundation, version 3 of the License (see COPYING and COPYING.addendum).

    This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
    even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU Affero General Public License and the addendum for more details.

    You should have received an addended copy of the GNU Affero General Public License with this program.
    If not, see <http://www.gnu.org/licenses/> and <https://github.com/unigrid-project/hedgehog>.
 */

package org.unigrid.hedgehog.model;

import lombok.NoArgsConstructor;

@NoArgsConstructor
public class Collateral {
	private final int baseAmount = 100;

	private double calculate(double collateral, double n, int numNodes) {
		double sum = 0;
		numNodes = numNodes - 500;

		final double calculation = collateral - collateral * 0.025
			* Math.log(Math.pow(collateral, Math.min(5.0 - 0.1 * n, 1.0)));

		if (numNodes >= 0) {
			sum = calculate(calculation, n + 1, numNodes);
		} else {
			sum = collateral;
		}

		return sum;
	}

	public int numAllowedNodes(int runningNodes, double amount) {
		double cost = get(runningNodes);
		double d = amount / cost;

		return (int) d;
	}

	public double get(int numNodes) {
		return calculate(baseAmount, 1, numNodes);
	}
}

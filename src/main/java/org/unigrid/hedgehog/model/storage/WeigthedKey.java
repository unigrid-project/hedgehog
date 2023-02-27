/*
	The Janus Wallet
	Copyright © 2021-2022 The Unigrid Foundation, UGD Software AB

	This program is free software: you can redistribute it and/or modify it under the terms of the
	addended GNU Affero General Public License as published by the Free Software Foundation, version 3
	of the License (see COPYING and COPYING.addendum).

	This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
	even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
	GNU Affero General Public License for more details.

	You should have received an addended copy of the GNU Affero General Public License with this program.
	If not, see <http://www.gnu.org/licenses/> and <https://github.com/unigrid-project/janus-java>.
 */
package org.unigrid.hedgehog.model.storage;

import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.apache.commons.lang3.builder.HashCodeBuilder;

@AllArgsConstructor
@Data
public class WeigthedKey implements WeigthedKeyInterface<String, Double>, Comparable<WeigthedKeyInterface<String, Double>> {

	private String key;

	private Double weigth;

	private int accessed;

	private int size;

	private Date lastAccesed;
	
	static final private int TIME_DELAY = 5 * 60 * 1000;

	public Double getWeigth() {
		decay();
		double accessedDouble = accessed + 1.001;
		weigth = size * (Math.log(Math.pow(accessedDouble, 70)));
		return weigth;

	}

	private void decay() {
		Date now = new Date();
		
		long diff = now.getTime() - lastAccesed.getTime();
		int counter = (int) diff / TIME_DELAY;
		while (counter > 0) {
			accessed = accessed - 1 < 0 ? 0 : accessed - 1;
			lastAccesed = new Date();
			counter--;
		}
	}

	@Override
	public int compareTo(WeigthedKeyInterface<String, Double> other) {
		return Double.compare(this.getWeigth(), other.getWeigth());
	}

	@Override
	public int hashCode() {
		return new HashCodeBuilder(7,31).append(key).toHashCode();
	}

	@Override
	public boolean equals(Object o) {

		if (!(o instanceof WeigthedKey)) {
			return false;
		}

		WeigthedKey key = (WeigthedKey) o;
		return this.getKey().equals(key.getKey());
	}

	public void increassesAccessed() {
		accessed++;
	}
}

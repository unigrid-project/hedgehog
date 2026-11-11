/*
    Unigrid Hedgehog
    Copyright © 2021-2026 Stiftelsen The Unigrid Foundation

    Stiftelsen The Unigrid Foundation (org. nr: 802482-2408)

    This program is free software: you can redistribute it and/or modify it under the terms of the
    addended GNU Affero General Public License as published by the The Unigrid Foundation and
    the Free Software Foundation, version 3 of the License (see COPYING and COPYING.addendum).

    This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
    even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU Affero General Public License and the addendum for more details.

    You should have received an addended copy of the GNU Affero General Public License with this program.
    If not, see <http://www.gnu.org/licenses/> and <https://github.com/unigrid-project/hedgehog>.
 */

package org.unigrid.hedgehog.model.cdi;

import jakarta.annotation.Priority;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import lombok.Cleanup;
import org.apache.commons.configuration2.sync.LockMode;

@Interceptor @Protected
@Priority(Interceptor.Priority.APPLICATION)
public class ProtectedInterceptor {
	private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

	private Lock getLockAnnotation(InvocationContext ctx) {
		final Lock lockAnnotation = ctx.getMethod().getAnnotation(Lock.class);

		return lockAnnotation != null ? lockAnnotation : classLockAnnotation(ctx);
	}

	/* The target is an instance of the subclass that Weld generates to intercept the bean, which does not carry the
	   annotation of the class it extends, so the classes above it are looked at too. */
	private Lock classLockAnnotation(InvocationContext ctx) {
		for (Class<?> type = ctx.getTarget().getClass(); type != null; type = type.getSuperclass()) {
			final Lock lockAnnotation = type.getAnnotation(Lock.class);

			if (lockAnnotation != null) {
				return lockAnnotation;
			}
		}

		throw new IllegalStateException("The protected method " + ctx.getMethod().getName() + " of "
			+ ctx.getMethod().getDeclaringClass().getName()
			+ " has no lock, neither on the method nor on its class");
	}

	@AroundInvoke
	public Object protect(InvocationContext ctx) throws Exception {
		final Lock lockAnnotation = getLockAnnotation(ctx);
		Object returnValue = null;

		if (LockMode.WRITE.equals(lockAnnotation.value())) {
			@Cleanup("unlock") final ReentrantReadWriteLock.WriteLock handler = lock.writeLock();

			handler.lock();
			returnValue = ctx.proceed();
		} else {
			@Cleanup("unlock") final ReentrantReadWriteLock.ReadLock handler = lock.readLock();
			handler.lock();
			returnValue = ctx.proceed();
		}

		return returnValue;
	}
}

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
 package org.unigrid.hedgehog.model.cdi;

import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Interceptor
@Protected
public class ProtectedInterceptor {

    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();

    private Lock getLockAnnotation(InvocationContext ctx) {
        Lock lock = ctx.getMethod().getAnnotation(Lock.class);
        if (lock == null) {
            lock = ctx.getTarget().getClass().getAnnotation(Lock.class);
        }
        return lock;
    }

    @AroundInvoke
    public Object protect(InvocationContext ctx) throws Exception {

        Lock lock = getLockAnnotation(ctx);

        if (lock == null) {
            return ctx.proceed();
        }

        if (lock.value() == LockMode.WRITE) {

            ReentrantReadWriteLock.WriteLock writeLock = rwLock.writeLock();
            writeLock.lock();

            try {
                return ctx.proceed();
            } finally {
                writeLock.unlock();
            }

        } else {

            ReentrantReadWriteLock.ReadLock readLock = rwLock.readLock();
            readLock.lock();

            try {
                return ctx.proceed();
            } finally {
                readLock.unlock();
            }

        }
    }
}
/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2016 Red Hat, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.jenkinsci.plugins.cloudstats;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import javax.annotation.Nonnegative;
import javax.annotation.concurrent.ThreadSafe;

/**
 * Circular thread-safe Collection.
 *
 * <p>At most <code>capacity</code> elements are preserved and when more added, oldest elements are
 * deleted. Collection preserves insertion order of the elements so the oldest is the first one.
 *
 * <p>The class does not implement selective deletion operations (<code>remove()</code>, <code>
 * removeAll()</code>, <code>retainAll()</code>) but it implements <code>clear()</code>.
 *
 * <p>All operations, including iteration, are thread safe. Operations <code>add()</code>, <code>
 * size()</code> and <code>isEmpty()</code> performs in constant time. The rest of the operations
 * have linear time complexity, while never holding the lock to perform any custom logic (such as
 * calling <code>contains()</code> on elements) or entering <code>synchronized</code> section on any
 * other monitor except for the internal lock. In such cases, lock is used only to make the copy of
 * the content.
 *
 * @author ogondza.
 */
@ThreadSafe
public class CyclicThreadSafeCollection<E> implements Collection<E> {

    private final @NonNull E[] data;
    private @Nonnegative int next = 0;
    private @Nonnegative int size = 0;

    public CyclicThreadSafeCollection(int capacity) {
        if (capacity < 0) throw new IllegalArgumentException("Capacity must be non-negative");

        this.data = CyclicThreadSafeCollection.newArray(capacity);
    }

    @Override
    public boolean add(E e) {
        synchronized (data) {
            data[next] = e;
            next = (next + 1) % data.length;
            if (size < data.length) {
                size++;
            }
            return true; // cyclic collection always adds
        }
    }

    /**
     * Add several elements at once.
     *
     * <p>It is not guaranteed the elements will be consecutive in the collection (other thread can
     * add elements in between) but it is guaranteed to preserve order.
     */
    @Override
    public boolean addAll(@NonNull Collection<? extends E> c) {
        for (E e : c) {
            add(e);
        }
        return true;
    }

    @Override
    public void clear() {
        synchronized (data) {
            next = 0;
            size = 0;
            Arrays.fill(data, null); // Do not retain objects
        }
    }

    /**
     * Create thread-safe iterator for collection snapshot.
     *
     * <p>Iterated elements represent a snapshot of the collection.
     */
    @Override
    public @NonNull Iterator<E> iterator() {
        return toList().iterator();
    }

    /** Number of contained elements, never more than capacity. */
    @Override
    public @Nonnegative int size() {
        synchronized (data) {
            return size;
        }
    }

    /** Maximal collection capacity. */
    public @Nonnegative int capacity() {
        return data.length;
    }

    @Override
    public boolean isEmpty() {
        return size() == 0;
    }

    @Override
    public boolean contains(Object o) {
        return toList().contains(o);
    }

    @Override
    public boolean containsAll(@NonNull Collection<?> c) {
        return toList().containsAll(c);
    }

    /** Get elements in separate collection. */
    public @NonNull List<E> toList() {
        return Arrays.asList(toArray());
    }

    @Override
    public @NonNull E[] toArray() {
        synchronized (data) { // size() and toArray() needs to be consistent
            return toArray(CyclicThreadSafeCollection.newArray(size()));
        }
    }

    @SuppressWarnings("SuspiciousSystemArraycopy")
    @Override
    public @NonNull <T> T[] toArray(@NonNull T[] ret) {
        synchronized (data) {
            int size = size();
            if (ret.length < size) {
                ret = CyclicThreadSafeCollection.newArray(size);
            } else if (ret.length > size) {
                // javadoc: If this collection fits in the specified array with room to spare
                // (i.e., the array has more elements than this collection), the element
                // in the array immediately following the end of the collection is set to
                // <code>null</code>
                ret[size] = null;
            }
            if (size < data.length) { // Initial fill
                System.arraycopy(data, 0, ret, 0, size);
            } else {
                int offset = data.length - next;
                System.arraycopy(data, 0, ret, offset, next);
                System.arraycopy(data, next, ret, 0, offset);
            }
            return ret;
        }
    }

    @SuppressWarnings("unchecked")
    private static <X> X[] newArray(int capacity) {
        return (X[]) new Object[capacity];
    }

    @Override
    public boolean remove(Object o) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean removeAll(@NonNull Collection<?> c) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean retainAll(@NonNull Collection<?> c) {
        throw new UnsupportedOperationException();
    }
}

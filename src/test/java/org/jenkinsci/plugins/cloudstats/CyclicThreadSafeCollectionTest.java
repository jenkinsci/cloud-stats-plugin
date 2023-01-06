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

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/**
 * @author ogondza.
 */
public class CyclicThreadSafeCollectionTest {

    @Test
    public void capacity() {
        new CyclicThreadSafeCollection<Float>(1);
        new CyclicThreadSafeCollection<Float>(0);
        try {
            new CyclicThreadSafeCollection<Float>(-1);
            fail();
        } catch (IllegalArgumentException ignored) {
        } // expected
    }

    @Test
    public void preserveData() {
        CyclicThreadSafeCollection<Integer> log = new CyclicThreadSafeCollection<>(5);
        assertEquals(0, log.size());
        assertTrue(log.isEmpty());
        assertArrayEquals(new Integer[] {}, log.toArray());

        assertTrue(log.add(42));
        assertFalse(log.isEmpty());
        assertEquals(1, log.size());
        assertArrayEquals(new Integer[] {42}, log.toArray());

        assertTrue(log.add(43));
        assertEquals(2, log.size());
        assertArrayEquals(new Integer[] {42, 43}, log.toArray());

        assertTrue(log.add(44));
        assertEquals(3, log.size());
        assertArrayEquals(new Integer[] {42, 43, 44}, log.toArray());

        assertTrue(log.add(45));
        assertEquals(4, log.size());
        assertArrayEquals(new Integer[] {42, 43, 44, 45}, log.toArray());

        assertTrue(log.add(46));
        assertEquals(5, log.size());
        assertArrayEquals(new Integer[] {42, 43, 44, 45, 46}, log.toArray());
        assertTrue(log.add(47));
        assertEquals(5, log.size());
        assertArrayEquals(new Integer[] {43, 44, 45, 46, 47}, log.toArray());
        assertTrue(log.add(48));
        assertEquals(5, log.size());
        assertArrayEquals(new Integer[] {44, 45, 46, 47, 48}, log.toArray());
        assertTrue(log.add(49));
        assertEquals(5, log.size());
        assertArrayEquals(new Integer[] {45, 46, 47, 48, 49}, log.toArray());
        assertTrue(log.add(50));
        assertEquals(5, log.size());
        assertArrayEquals(new Integer[] {46, 47, 48, 49, 50}, log.toArray());
        assertTrue(log.addAll(Arrays.asList(51, 52)));
        assertEquals(5, log.size());
        assertArrayEquals(new Integer[] {48, 49, 50, 51, 52}, log.toArray());

        log.clear();
        assertEquals(0, log.size());
        assertTrue(log.isEmpty());
        assertArrayEquals(new Integer[] {}, log.toArray());
    }

    @Test
    public void toArray() {
        CyclicThreadSafeCollection<Integer> log = new CyclicThreadSafeCollection<>(5);
        Integer[] dst = new Integer[2];

        log.add(1);
        assertArrayEquals(new Integer[] {1, null}, log.toArray(dst));
        assertArrayEquals(new Integer[] {1, null}, dst);

        log.add(1);
        assertArrayEquals(new Integer[] {1, 1}, log.toArray(dst));
        assertArrayEquals(new Integer[] {1, 1}, dst);

        log.add(1);
        assertArrayEquals(new Integer[] {1, 1, 1}, log.toArray(dst));
        assertArrayEquals(
                new Integer[] {1, 1}, dst); // Not returned in dst array as it does not fit
    }

    @Test
    public void toList() {
        CyclicThreadSafeCollection<Integer> log = new CyclicThreadSafeCollection<>(2);

        log.add(1);
        assertEquals(Collections.singletonList(1), log.toList());
        log.add(2);
        assertEquals(Arrays.asList(1, 2), log.toList());
        log.add(3);
        assertEquals(Arrays.asList(2, 3), log.toList());

        log.clear();

        assertEquals(Collections.emptyList(), log.toList());
        log.add(4);
        assertEquals(Collections.singletonList(4), log.toList());
        log.add(5);
        assertEquals(Arrays.asList(4, 5), log.toList());
        log.add(6);
        assertEquals(Arrays.asList(5, 6), log.toList());
    }

    @Test
    public void contains() {
        CyclicThreadSafeCollection<Integer> log = new CyclicThreadSafeCollection<>(3);
        log.add(1);
        log.add(2);
        log.add(3);

        assertTrue(log.contains(1));
        assertTrue(log.contains(2));
        assertTrue(log.contains(3));
        assertFalse(log.contains(4));
        assertTrue(log.containsAll(Arrays.asList(1, 2)));

        assertFalse(log.contains(4));
        assertFalse(log.containsAll(Arrays.asList(4, 5)));
        assertFalse(log.containsAll(Arrays.asList(1, 2, 3, 4)));
    }

    @Test
    public void iterator() {
        CyclicThreadSafeCollection<Integer> log = new CyclicThreadSafeCollection<>(3);
        assertEquals(Collections.emptyList(), it2list(log));

        log.add(1);
        assertEquals(Collections.singletonList(1), it2list(log));

        log.add(2);
        assertEquals(Arrays.asList(1, 2), it2list(log));

        log.add(3);
        assertEquals(Arrays.asList(1, 2, 3), it2list(log));

        log.add(4);
        assertEquals(Arrays.asList(2, 3, 4), it2list(log));
    }

    private <T> List<T> it2list(Collection<T> it) {
        return new ArrayList<>(it);
    }

    @Test
    public void threadSafety() {
        final Collection<Integer> data = new CyclicThreadSafeCollection<>(100000);
        Runnable iterator =
                new Runnable() {
                    @Override
                    public void run() {
                        for (; ; ) {
                            for (Integer d : data) {
                                assertNotNull(d);
                            }

                            if (Thread.interrupted()) break;
                        }
                    }
                };

        Runnable appender =
                new Runnable() {
                    @Override
                    public void run() {
                        for (; ; ) {
                            assertTrue(data.add(data.size() + 42));

                            if (Thread.interrupted()) break;
                        }
                    }
                };

        Runnable container =
                new Runnable() {
                    private boolean last;

                    @Override
                    public void run() {
                        for (; ; ) {
                            // Pretend we use the result not to be optimized away
                            last = data.containsAll(Arrays.asList(17, 39, last ? 315 : 316));

                            if (Thread.interrupted()) break;
                        }
                    }
                };

        Runnable clearer =
                new Runnable() {
                    @Override
                    public void run() {
                        for (; ; ) {
                            // System.out.printf("Clearing %d%n", data.size());
                            data.clear();
                            try {
                                Thread.sleep(10);
                            } catch (InterruptedException e) {
                                break;
                            }
                        }
                    }
                };

        Runnable[] runnables =
                new Runnable[] {
                    appender, appender, appender, appender, appender, appender, appender, appender,
                    iterator, iterator, iterator, container, container, container, clearer
                };
        Thread[] threads = new Thread[runnables.length];
        try {
            for (int i = 0; i < runnables.length; i++) {
                threads[i] = new Thread(runnables[i]);
                threads[i].start();
            }

            Thread.sleep(10000);
        } catch (InterruptedException ignored) {
            // terminate after interrupting all children in finally
        } finally {
            for (Thread thread : threads) {
                assertTrue(thread.isAlive()); // Died with exception
                thread.interrupt();
            }
        }
    }
}

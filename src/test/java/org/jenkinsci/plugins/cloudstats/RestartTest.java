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

import hudson.Extension;
import hudson.ExtensionList;
import hudson.model.Node;
import hudson.slaves.ComputerListener;
import hudson.slaves.DumbSlave;
import hudson.slaves.RetentionStrategy;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runners.model.Statement;
import org.jvnet.hudson.test.RestartableJenkinsRule;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.jenkinsci.plugins.cloudstats.ProvisioningActivity.Phase.COMPLETED;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * @author ogondza.
 */
public class RestartTest {

    @Rule
    public RestartableJenkinsRule j = new RestartableJenkinsRule();

    @Test
    public void loadEmpty() {
        j.addStep(new Statement() {
            @Override public void evaluate() throws Throwable {
                CloudStatistics cs = CloudStatistics.get();
                cs.save();
                assertThat(cs.getActivities(), Matchers.emptyIterable());
            }
        });

        j.addStep(new Statement() {
            @Override public void evaluate() {
                CloudStatistics cs = CloudStatistics.get();
                assertThat(cs.getActivities(), Matchers.emptyIterable());
            }
        });
    }

    @Test
    public void persistStatisticsBetweenRestarts() {
        final ProvisioningActivity.Id started = new ProvisioningActivity.Id("Cloud", "template", "started");
        final ProvisioningActivity.Id failed = new ProvisioningActivity.Id("Cloud", "template", "failed");
        final ProvisioningActivity.Id completed = new ProvisioningActivity.Id("Cloud", "template", "completed");

        j.addStep(new Statement() {
            @Override public void evaluate() throws Throwable {
                final CloudStatistics.ProvisioningListener listener = CloudStatistics.ProvisioningListener.get();

                listener.onStarted(started);
                listener.onStarted(failed);
                listener.onFailure(failed, new Exception());

                TrackedAgent node = TrackedAgent.create(completed, j.j);
                listener.onStarted(completed);
                listener.onComplete(completed, node);
                ExtensionList.lookup(CloudStatistics.OperationListener.class).get(0).onOnline(node.createComputer(), null);
                ExtensionList.lookup(CloudStatistics.SlaveCompletionDetector.class).get(0).onDeleted(node);
            }
        });

        j.addStep(new Statement() {
            @Override public void evaluate() {
                final CloudStatistics stats = CloudStatistics.get();

                assertThat(stats.getActivities(), Matchers.iterableWithSize(3));

                ProvisioningActivity c = stats.getActivityFor(completed);
                assertNotNull(c.getPhaseExecution(ProvisioningActivity.Phase.PROVISIONING));
                assertEquals(ProvisioningActivity.Status.OK, c.getStatus());
                assertEquals(COMPLETED, c.getCurrentPhase());

                ProvisioningActivity f = stats.getActivityFor(failed);
                assertNotNull(f.getPhaseExecution(ProvisioningActivity.Phase.PROVISIONING));
                assertEquals(ProvisioningActivity.Status.FAIL, f.getStatus());
                assertEquals(COMPLETED, f.getCurrentPhase());

                ProvisioningActivity s = stats.getActivityFor(started);
                assertNotNull(s.getPhaseExecution(ProvisioningActivity.Phase.PROVISIONING));

                assertEquals(ProvisioningActivity.Status.WARN, s.getStatus());
                assertEquals(COMPLETED, f.getCurrentPhase());
            }
        });
    }

    @Test
    public void resizeStatsCountOnRestart() {
        CloudStatistics.ARCHIVE_RECORDS = 1;
        j.addStep(new Statement() { // Capacity is set correctly initially
            @Override public void evaluate() {
                final CloudStatistics stats = CloudStatistics.get();
                addCompletedActivity(2);
                assertStats(stats, 1);
                CloudStatistics.ARCHIVE_RECORDS = 2;
                assertStats(stats, 1);
            }
        });

        j.addStep(new Statement() { // Capacity is extended
            @Override public void evaluate() {
                final CloudStatistics stats = CloudStatistics.get();
                assertStats(stats, 1);
                addCompletedActivity(2);
                assertStats(stats, 2, 3);
                CloudStatistics.ARCHIVE_RECORDS = 1;
            }
        });

        j.addStep(new Statement() { // Capacity is trimmed below current size truncating the log
            @Override public void evaluate() {
                final CloudStatistics stats = CloudStatistics.get();
                assertStats(stats, 3);
                addCompletedActivity(1);
                assertStats(stats, 4);
                CloudStatistics.ARCHIVE_RECORDS = 10;
            }
        });

        j.addStep(new Statement() { // Capacity is shrunk but above current log size
            @Override public void evaluate() {
                final CloudStatistics stats = CloudStatistics.get();
                addCompletedActivity(2);
                assertStats(stats, 4, 5, 6);
                CloudStatistics.ARCHIVE_RECORDS = 5;
            }
        });
        j.addStep(new Statement() {
            @Override public void evaluate() {
                final CloudStatistics stats = CloudStatistics.get();
                assertStats(stats, 4, 5, 6);
                addCompletedActivity(3);
                assertStats(stats, 5, 6, 7, 8, 9);
            }
        });
    }

    private transient static AtomicInteger sequence = new AtomicInteger(0);
    private static void addCompletedActivity(int count) {
        final CloudStatistics.ProvisioningListener listener = CloudStatistics.ProvisioningListener.get();
        for (int i = 0; i < count; i++) {
            ProvisioningActivity.Id id = new ProvisioningActivity.Id("Cloud", "template", Integer.toString(sequence.getAndIncrement()));
            listener.onStarted(id);
            listener.onFailure(id, new Error());
        }
    }

    private static void assertStats(CloudStatistics stats, int... expected) {
        List<Integer> statSequences = stats.getActivities().stream().map(pa -> Integer.parseInt(pa.getName())).collect(Collectors.toList());
        List<Integer> expectedList = Arrays.stream(expected).boxed().collect(Collectors.toList());
        assertEquals(expectedList, statSequences);

        // Verify the order remain preserved
        long last = 0;
        for (ProvisioningActivity activity : stats.getActivities()) {
            long sequenceNumber = Long.parseLong(activity.getName());
            assertThat(sequenceNumber, greaterThan(last));
            last = sequenceNumber;
        }
    }
}

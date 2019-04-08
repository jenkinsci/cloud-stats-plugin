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

import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runners.model.Statement;
import org.jvnet.hudson.test.RestartableJenkinsRule;

import static org.hamcrest.MatcherAssert.assertThat;
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
                assertThat(cs.getActivities(), Matchers.<ProvisioningActivity>emptyIterable());
            }
        });

        j.addStep(new Statement() {
            @Override public void evaluate() {
                CloudStatistics cs = CloudStatistics.get();
                assertThat(cs.getActivities(), Matchers.<ProvisioningActivity>emptyIterable());
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
                listener.onStarted(completed);
                listener.onComplete(completed, j.j.createOnlineSlave());
            }
        });

        j.addStep(new Statement() {
            @Override public void evaluate() {
                final CloudStatistics.ProvisioningListener listener = CloudStatistics.ProvisioningListener.get();
                final CloudStatistics stats = CloudStatistics.get();

                assertThat(stats.getActivities(), Matchers.<ProvisioningActivity>iterableWithSize(3));

                ProvisioningActivity c = stats.getActivityFor(completed);
                assertNotNull(c.getPhaseExecution(ProvisioningActivity.Phase.PROVISIONING));
                assertEquals(ProvisioningActivity.Status.OK, c.getStatus());

                ProvisioningActivity f = stats.getActivityFor(failed);
                assertNotNull(f.getPhaseExecution(ProvisioningActivity.Phase.PROVISIONING));
                assertEquals(ProvisioningActivity.Status.FAIL, f.getStatus());

                ProvisioningActivity s = stats.getActivityFor(started);
                assertNotNull(s.getPhaseExecution(ProvisioningActivity.Phase.PROVISIONING));
                assertEquals(ProvisioningActivity.Status.OK, s.getStatus());
                listener.onFailure(started, new Exception());

                assertEquals(stats.getRetainedActivities(), stats.getNotCompletedActivities());
            }
        });

        j.addStep(new Statement() {
            @Override public void evaluate() {
                final CloudStatistics stats = CloudStatistics.get();

                assertThat(stats.getActivities(), Matchers.<ProvisioningActivity>iterableWithSize(3));

                ProvisioningActivity s = stats.getActivityFor(started);
                assertEquals(ProvisioningActivity.Status.FAIL, s.getStatus());

                assertEquals(stats.getRetainedActivities(), stats.getNotCompletedActivities());
            }
        });
    }
}

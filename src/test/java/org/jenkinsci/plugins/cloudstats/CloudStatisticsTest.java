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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.jenkinsci.plugins.cloudstats.ProvisioningActivity.Phase.*;
import static org.jenkinsci.plugins.cloudstats.ProvisioningActivity.Status.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import hudson.EnvVars;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Label;
import hudson.model.LoadStatistics;
import hudson.model.Node;
import hudson.model.queue.QueueTaskFuture;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author ogondza.
 */
public class CloudStatisticsTest {

    public @Rule JenkinsRule j = new JenkinsRule();
    private NodeProvisioner.NodeProvisionerInvoker provisionerInvoker;

    static {
        LoadStatistics.CLOCK = 1000;
    }

    @Before
    public void before() throws Exception {
        // Pretend we are out of slaves
        j.jenkins.setNumExecutors(0);
        j.jenkins.setNodes(Collections.<Node>emptyList());

        // Do not provision when not expected
        ExtensionList<NodeProvisioner.NodeProvisionerInvoker> extensionList = j.jenkins.getExtensionList(NodeProvisioner.NodeProvisionerInvoker.class);
        provisionerInvoker = extensionList.get(0);
        //extensionList.remove(provisionerInvoker);
    }

    private void triggerProvisioning() {
        provisionerInvoker.run(); // The method first collects completed activities and then starts new ones - to start and collect it needs to be called twice.
        provisionerInvoker.run();
    }

    @Test
    public void showOnlyIfThereAreClouds() throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient();
        wc.getOptions().setThrowExceptionOnFailingStatusCode(false);

        assertThat(wc.goTo("manage").asText(), not(containsString("Cloud Statistics")));
        assertThat(wc.goTo("cloud-stats/").asText(), containsString("No clouds configured"));

        j.jenkins.clouds.add(new TestCloud("Dummy"));

        assertThat(wc.goTo("manage").asText(), containsString("Cloud Statistics"));
        String actual = wc.goTo("cloud-stats/").asText();
        assertThat(actual, not(containsString("No clouds configured")));
        assertThat(actual, containsString("Dummy Test Cloud"));
    }

    @Test
    public void provisionAndFail() throws Exception {
        j.createFreeStyleProject().scheduleBuild2(0);

        j.jenkins.clouds.add(new TestCloud("dummy", j, new ThrowException()));

        List<ProvisioningActivity> activities = null;
        for (;;) {
            Thread.sleep(1000);
            System.out.println(".");
            activities = CloudStatistics.get().getActivities();
            if (activities.size() != 0) {
                ProvisioningActivity.PhaseExecution execution = activities.get(0).getPhaseExecution(PROVISIONING);
                if (execution.getAttachment(PhaseExecutionAttachment.ExceptionAttachement.class) != null) {
                    break;
                }
            }
        }

        ProvisioningActivity activity = activities.get(0);
        ProvisioningActivity.PhaseExecution prov = activity.getPhaseExecution(PROVISIONING);
        PhaseExecutionAttachment.ExceptionAttachement attachment = prov.getAttachment(PhaseExecutionAttachment.ExceptionAttachement.class);
        assertEquals(ThrowException.EXCEPTION, attachment.getCause());
        assertEquals(FAIL, attachment.getStatus());
        assertEquals(FAIL, activity.getStatus());
        // TODO check the phasing as well
        //assertNotNull(activity.getPhaseExecution(COMPLETED));
    }

    @Test
    public void provisionAndLaunch() throws Exception {
        FreeStyleProject p = j.createFreeStyleProject();
        p.setAssignedLabel(Label.get("label"));
        QueueTaskFuture<FreeStyleBuild> build = p.scheduleBuild2(0);

        j.jenkins.clouds.add(new TestCloud("dummy", j, new LaunchSuccessfully()));

        triggerProvisioning();

        List<ProvisioningActivity> activities = CloudStatistics.get().getActivities();
        for (ProvisioningActivity a : activities) {
            assertEquals("dummy", a.getCloudName());
            assertThat(a.getNodeName(), startsWith("dummy-slave-"));
        }

        ProvisioningActivity activity = activities.get(0);
        assertNotNull(activity.getPhaseExecution(PROVISIONING));
        assertEquals(activity.getPhaseExecution(PROVISIONING).getAttachments().toString(), OK, activity.getStatus());

        // It can take a bit
        while (j.jenkins.getComputer(activity.getNodeName()) == null) {
            System.out.println("Waiting for node");
            Thread.sleep(100);
        }
        Computer computer = j.jenkins.getComputer(activity.getNodeName());
        assertNotNull(computer);

        while (activity.getPhaseExecution(LAUNCHING) != null) {
            System.out.println("Waiting for launch to start");
            Thread.sleep(100);
        }

        while (activity.getPhaseExecution(OPERATING) != null) {
            System.out.println("Waiting for slave to launch");
            Thread.sleep(100);
        }

        System.out.println("Waiting for slave to launch");
        computer.waitUntilOnline();
        assertNull(activity.getPhaseExecution(COMPLETED));

        System.out.println("Waiting for build to complete");
        Computer builtOn = build.get().getBuiltOn().toComputer();
        assertEquals(computer, builtOn);

        computer.doDoDelete();

        assertEquals(OK, activity.getStatus());
        // TODO check phasing
        // assertNotNull(activity.getPhaseExecution(COMPLETED));
    }

    public static final class TestCloud extends Cloud {
        private transient final Launcher provision;
        private transient final JenkinsRule j;
        private final AtomicInteger seq = new AtomicInteger();

        public TestCloud(String name) {
            super(name);
            provision = null;
            j = null;
        }

        public TestCloud(final String name, JenkinsRule j, final Launcher provision) {
            super(name);
            this.j = j;
            this.provision = provision;
        }

        @Override
        public Collection<NodeProvisioner.PlannedNode> provision(Label label, int excessWorkload) {
            assert provision != null;

            provision.j = j;
            provision.slaveName = name + "-slave-" + seq.getAndIncrement();

            return Collections.<NodeProvisioner.PlannedNode>singletonList(new NodeProvisioner.PlannedNode(
                    provision.slaveName,
                    Computer.threadPoolForRemoting.submit(provision),
                    1
            ));
        }

        @Override
        public boolean canProvision(Label label) {
            return provision != null;
        }

        @Extension
        public static final class Desc extends Descriptor<Cloud> {
            @Override
            public String getDisplayName() {
                return "Test Cloud";
            }
        }
    }

    private static abstract class Launcher implements Callable<Node> {
        protected transient JenkinsRule j;
        protected String slaveName;
    }

    private static class ThrowException extends Launcher {
        public static final NullPointerException EXCEPTION = new NullPointerException("Whoops");

        @Override
        public Node call() throws Exception {
            throw EXCEPTION;
        }
    }

    private static class LaunchSuccessfully extends Launcher {

        @Override
        public Node call() throws Exception {
            return j.createSlave(slaveName, "label", new EnvVars());
        }
    }
}

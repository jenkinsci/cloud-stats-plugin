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

import hudson.EnvVars;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.Functions;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Label;
import hudson.model.LoadStatistics;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.model.queue.QueueTaskFuture;
import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.AbstractCloudSlave;
import hudson.slaves.Cloud;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.DumbSlave;
import hudson.slaves.NodeProperty;
import hudson.slaves.NodeProvisioner;
import hudson.slaves.RetentionStrategy;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.jenkinsci.plugins.cloudstats.ProvisioningActivity.Phase.COMPLETED;
import static org.jenkinsci.plugins.cloudstats.ProvisioningActivity.Phase.LAUNCHING;
import static org.jenkinsci.plugins.cloudstats.ProvisioningActivity.Phase.OPERATING;
import static org.jenkinsci.plugins.cloudstats.ProvisioningActivity.Phase.PROVISIONING;
import static org.jenkinsci.plugins.cloudstats.ProvisioningActivity.Status.FAIL;
import static org.jenkinsci.plugins.cloudstats.ProvisioningActivity.Status.OK;
import static org.jenkinsci.plugins.cloudstats.ProvisioningActivity.Status.WARN;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

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
    }

    private void triggerProvisioning() {
        provisionerInvoker.run(); // The method first collects completed activities and then starts new ones - to start and collect it needs to be called twice.
        provisionerInvoker.run();
    }

    @Test
    public void showOnlyIfThereAreClouds() throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient();
        wc.getOptions().setThrowExceptionOnFailingStatusCode(false);
        wc.getOptions().setPrintContentOnFailingStatusCode(false);

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
        triggerProvisioning();

        List<ProvisioningActivity> activities = CloudStatistics.get().getActivities();
        // As it still fails many will be provisioned. Take the last one as the most recent is being updated
        ProvisioningActivity activity = activities.get(activities.size() - 1);
        ProvisioningActivity.PhaseExecution prov = activity.getPhaseExecution(PROVISIONING);

        while (activity.getStatus() != FAIL) {
            Thread.sleep(100);
        }
        assertEquals(FAIL, activity.getStatus());
        PhaseExecutionAttachment.ExceptionAttachment attachment = prov.getAttachment(PhaseExecutionAttachment.ExceptionAttachment.class);
        assertEquals(ThrowException.EXCEPTION, attachment.getCause());
        assertEquals(FAIL, attachment.getStatus());
        assertEquals(FAIL, activity.getStatus());

        detectCompletionNow();
        assertNotNull(activity.getPhaseExecution(COMPLETED));
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
            assertEquals(activities.toString(), "dummy", a.getId().getCloudName());
            assertThat(activities.toString(), a.getId().getNodeName(), startsWith("dummy-slave-"));
        }

        ProvisioningActivity activity = activities.get(0);
        assertNotNull(activity.getPhaseExecution(PROVISIONING));
        assertEquals(activity.getPhaseExecution(PROVISIONING).getAttachments().toString(), OK, activity.getStatus());

        // It can take a bit
        while (j.jenkins.getComputer(activity.getId().getNodeName()) == null) {
            System.out.println("Waiting for node");
            Thread.sleep(100);
        }
        Computer computer = j.jenkins.getComputer(activity.getId().getNodeName());
        assertNotNull(computer);

        while (activity.getPhaseExecution(LAUNCHING) == null) {
            System.out.println("Waiting for launch to start");
            Thread.sleep(100);
        }

        while (activity.getPhaseExecution(OPERATING) == null) {
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

        detectCompletionNow();
        assertNotNull(activity.getPhaseExecution(COMPLETED));
    }

    @Test
    public void ui() throws Exception {
        CloudStatistics cs = CloudStatistics.get();
        CloudStatistics.ProvisioningListener provisioningListener = CloudStatistics.ProvisioningListener.get();

        // When

        ProvisioningActivity.Id provisionId = new ProvisioningActivity.Id("MyCloud", 1, "broken-template");
        provisioningListener.onStarted(provisionId);
        provisioningListener.onFailure(provisionId, new Exception("Something bad happened"));

        ProvisioningActivity.Id warnId = new ProvisioningActivity.Id("PickyCloud", 2, null, "slave");
        provisioningListener.onStarted(warnId);
        Node slave = createTrackedSlave(warnId, j);
        ProvisioningActivity a = provisioningListener.onComplete(warnId, slave);
        a.attach(LAUNCHING, new PhaseExecutionAttachment(WARN, "There is something attention worthy"));

        slave.toComputer().waitUntilOnline();
        Thread.sleep(500);
        slave.toComputer().doDoDelete();

        ProvisioningActivity.Id okId = new ProvisioningActivity.Id("MyCloud", 3, "working-template", "future-slave");
        provisioningListener.onStarted(okId);
        slave = createTrackedSlave(okId, j);
        provisioningListener.onComplete(okId, slave);
        slave.toComputer().waitUntilOnline();
        Thread.sleep(500);
        slave.toComputer().doDoDelete();
        detectCompletionNow(); // Force completion detection

        Thread.sleep(500);

        // Then

        List<ProvisioningActivity> all = cs.getActivities();

        ProvisioningActivity failedToProvision = all.get(0);
        assertEquals(provisionId, failedToProvision.getId());
        assertEquals(FAIL, failedToProvision.getStatus());
        assertEquals(null, failedToProvision.getPhaseExecution(LAUNCHING));
        assertEquals(null, failedToProvision.getPhaseExecution(OPERATING));
        assertNotNull(failedToProvision.getPhaseExecution(COMPLETED));
        ProvisioningActivity.PhaseExecution failedProvisioning = failedToProvision.getPhaseExecution(PROVISIONING);
        assertEquals(FAIL, failedProvisioning.getStatus());
        assertEquals("Something bad happened", ((PhaseExecutionAttachment.ExceptionAttachment) failedProvisioning.getAttachments().get(0)).getCause().getMessage());

        ProvisioningActivity warn = all.get(1);
        assertEquals(warnId, warn.getId());
        assertEquals(WARN, warn.getStatus());
        assertNotNull(warn.getPhaseExecution(PROVISIONING));
        assertNotNull(warn.getPhaseExecution(LAUNCHING));
        assertNotNull(warn.getPhaseExecution(OPERATING));
        assertNotNull(warn.getPhaseExecution(COMPLETED));
        ProvisioningActivity.PhaseExecution warnedLaunch = warn.getPhaseExecution(LAUNCHING);
        assertEquals(WARN, warnedLaunch.getStatus());
        assertEquals("There is something attention worthy", warnedLaunch.getAttachments().get(0).getTitle());

        ProvisioningActivity ok = all.get(2);
        assertEquals(okId, ok.getId());
        assertEquals(OK, ok.getStatus());
        assertNotNull(ok.getPhaseExecution(PROVISIONING));
        assertNotNull(ok.getPhaseExecution(LAUNCHING));
        assertNotNull(ok.getPhaseExecution(OPERATING));
        assertNotNull(ok.getPhaseExecution(COMPLETED));

        //j.interactiveBreak();
    }

    @Test
    public void renameActivity() throws Exception {
        CloudStatistics.ProvisioningListener l = CloudStatistics.ProvisioningListener.get();
        ProvisioningActivity.Id fixup = new ProvisioningActivity.Id("Cloud", 1, "template", "incorrectName");
        ProvisioningActivity.Id assign = new ProvisioningActivity.Id("Cloud", 2, "template");
        ProvisioningActivity fActivity = l.onStarted(fixup);
        ProvisioningActivity aActivity = l.onStarted(assign);

        assertEquals("incorrectName", fActivity.getDisplayName());
        assertEquals("template", aActivity.getDisplayName());

        LaunchSuccessfully.TrackedSlave fSlave = new LaunchSuccessfully.TrackedSlave(new ProvisioningActivity.Id("Cloud", 1, "template", "correct-name"), j);
        LaunchSuccessfully.TrackedSlave aSlave = new LaunchSuccessfully.TrackedSlave(new ProvisioningActivity.Id("Cloud", 2, "template", "Some Name"), j);

        l.onComplete(fixup, fSlave);
        l.onComplete(assign, aSlave);

        assertEquals(fSlave.getDisplayName(), fActivity.getDisplayName());
        assertEquals(aSlave.getDisplayName(), aActivity.getDisplayName());
    }

    private void detectCompletionNow() throws Exception {
        j.jenkins.getExtensionList(CloudStatistics.SlaveCompletionDetector.class).get(0).doRun();
    }

    @Nonnull
    private static LaunchSuccessfully.TrackedSlave createTrackedSlave(ProvisioningActivity.Id id, JenkinsRule j) throws Exception {
        LaunchSuccessfully.TrackedSlave slave = new LaunchSuccessfully.TrackedSlave(id, j);
        j.jenkins.addNode(slave);
        return slave;
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
            int i = seq.getAndIncrement();
            provision.id = new ProvisioningActivity.Id(name, i, null, name + "-slave-" + i);

            return Collections.<NodeProvisioner.PlannedNode>singletonList(new TrackedPlannedNode(
                    name, provision.id.getNodeName(), 1, Computer.threadPoolForRemoting.submit(provision)
            ) {
                @Override public @Nonnull ProvisioningActivity.Id getId() {
                    return provision.id;
                }
            });
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
        protected ProvisioningActivity.Id id;
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
            return createTrackedSlave(id, j);
        }

        private static final class TrackedSlave extends AbstractCloudSlave implements TrackedItem {
            private final ProvisioningActivity.Id id;

            public TrackedSlave(ProvisioningActivity.Id id, JenkinsRule j) throws Exception {
                super(id.getNodeName(), "dummy", j.createTmpDir().getPath(), "1", Node.Mode.NORMAL, "label", j.createComputerLauncher(new EnvVars()), RetentionStrategy.NOOP, Collections.<NodeProperty<?>>emptyList());
                this.id = id;
            }

            public TrackedSlave(
                    String name, String nodeDescription, String remoteFS, String numExecutors, Mode mode, String labelString, ComputerLauncher launcher, RetentionStrategy retentionStrategy, List<? extends NodeProperty<?>> nodeProperties, ProvisioningActivity.Id id
            ) throws IOException, Descriptor.FormException {
                super(name, nodeDescription, remoteFS, numExecutors, mode, labelString, launcher, retentionStrategy, nodeProperties);
                this.id = id;
            }

            @Override
            public AbstractCloudComputer createComputer() {
                return new TrackedComputer(this, id);
            }

            @Override
            protected void _terminate(TaskListener listener) throws IOException, InterruptedException {

            }

            @Override
            public @Nonnull ProvisioningActivity.Id getId() {
                return id;
            }
        }

        private static final class TrackedComputer extends AbstractCloudComputer<TrackedSlave> implements TrackedItem {

            private final ProvisioningActivity.Id id;

            public TrackedComputer(TrackedSlave slave, ProvisioningActivity.Id id) {
                super(slave);
                this.id = id;
            }

            @Override
            public @Nonnull ProvisioningActivity.Id getId() {
                return id;
            }
        }
    }
}

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

import com.gargoylesoftware.htmlunit.ElementNotFoundException;
import com.gargoylesoftware.htmlunit.Page;
import com.gargoylesoftware.htmlunit.html.HtmlAnchor;
import com.gargoylesoftware.htmlunit.html.HtmlPage;
import hudson.BulkChange;
import hudson.ExtensionList;
import hudson.Functions;
import hudson.model.Computer;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Label;
import hudson.model.LoadStatistics;
import hudson.model.Node;
import hudson.model.queue.QueueTaskFuture;
import hudson.security.AuthorizationStrategy;
import hudson.slaves.NodeProvisioner;
import jenkins.model.NodeListener;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.recipes.LocalData;

import javax.annotation.Nonnull;
import java.io.ObjectStreamException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
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
import static org.junit.Assert.fail;

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
        // Pretend we are out of agents
        j.jenkins.setNumExecutors(0);
        j.jenkins.setNodes(Collections.emptyList());

        // Do not provision when not expected
        ExtensionList<NodeProvisioner.NodeProvisionerInvoker> extensionList = j.jenkins.getExtensionList(NodeProvisioner.NodeProvisionerInvoker.class);
        provisionerInvoker = extensionList.get(0);
    }

    private void triggerProvisioning() {
        provisionerInvoker.run(); // The method first collects completed activities and then starts new ones - to start and collect it needs to be called twice.
        provisionerInvoker.run();
    }

    @Test
    public void provisionAndFail() throws Exception {
        j.createFreeStyleProject().scheduleBuild2(0);

        j.jenkins.clouds.add(new TestCloud("dummy", j, new TestCloud.ThrowException()));
        triggerProvisioning();

        ProvisioningActivity activity;
        CloudStatistics cs = CloudStatistics.get();
        for (;;) {
            List<ProvisioningActivity> activities = cs.getActivities();
            if (activities.size() > 0) {
                activity = activities.get(0);

                if (activity.getStatus() == FAIL) break;
            }
            Thread.sleep(100);
        }

        PhaseExecution prov = activity.getPhaseExecution(PROVISIONING);
        assertEquals(FAIL, activity.getStatus());
        PhaseExecutionAttachment.ExceptionAttachment attachment = prov.getAttachments(PhaseExecutionAttachment.ExceptionAttachment.class).get(0);
        assertEquals(Functions.printThrowable(TestCloud.ThrowException.EXCEPTION), attachment.getText());
        assertEquals(FAIL, attachment.getStatus());
        assertEquals(FAIL, activity.getStatus());

        assertNotNull(activity.getPhaseExecution(COMPLETED));

        assertEquals(cs.getRetainedActivities(), cs.getNotCompletedActivities());
    }

    @Test
    public void provisionAndLaunch() throws Exception {
        CloudStatistics cs = CloudStatistics.get();

        FreeStyleProject p = j.createFreeStyleProject();
        p.setAssignedLabel(Label.get("label"));
        QueueTaskFuture<FreeStyleBuild> build = p.scheduleBuild2(0);

        j.jenkins.clouds.add(new TestCloud("dummy", j, new LaunchSuccessfully()));
        triggerProvisioning();

        List<ProvisioningActivity> activities;
        for (;;) {
            activities = cs.getActivities();
            if (activities.size() > 0) break;
        }
        for (ProvisioningActivity a : activities) {
            assertEquals(activities.toString(), "dummy", a.getId().getCloudName());
            assertThat(activities.toString(), a.getId().getNodeName(), startsWith("dummy-agent-"));
            assertThat(activities.toString(), a.getName(), startsWith("dummy-agent-"));
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
            System.out.println("Waiting for agent to launch");
            Thread.sleep(100);
        }

        System.out.println("Waiting for agent to launch");
        computer.waitUntilOnline();
        assertNull(activity.getPhaseExecution(COMPLETED));

        System.out.println("Waiting for build to complete");
        Computer builtOn = build.get().getBuiltOn().toComputer();
        assertEquals(computer, builtOn);

        assertThat(cs.getNotCompletedActivities(), contains(activity));
        computer.doDoDelete();
        assertEquals(OK, activity.getStatus());
        assertNotNull(activity.getCurrentPhase().toString(), activity.getPhaseExecution(COMPLETED));
        assertThat(cs.getNotCompletedActivities(), not(contains(activity)));

        assertEquals(cs.getRetainedActivities(), cs.getNotCompletedActivities());
    }

    @Test
    public void ui() throws Exception {
        j.jenkins.clouds.add(new TestCloud("MyCloud"));
        j.jenkins.clouds.add(new TestCloud("PickyCloud"));

        final String EXCEPTION_MESSAGE = "Something bad happened. Something bad happened. Something bad happened. Something bad happened. Something bad happened. Something bad happened.";
        CloudStatistics cs = CloudStatistics.get();
        CloudStatistics.ProvisioningListener provisioningListener = CloudStatistics.ProvisioningListener.get();

        // When

        ProvisioningActivity.Id failId = new ProvisioningActivity.Id("MyCloud", "broken-template");
        provisioningListener.onStarted(failId);
        provisioningListener.onFailure(failId, new Exception(EXCEPTION_MESSAGE));

        ProvisioningActivity.Id warnId = new ProvisioningActivity.Id("PickyCloud", null, "agent");
        provisioningListener.onStarted(warnId);
        Node slave = TrackedAgent.create(warnId, j);
        ProvisioningActivity a = provisioningListener.onComplete(warnId, slave);
        final String WARNING_MESSAGE = "There is something attention worthy. There is something attention worthy.";
        a.attach(LAUNCHING, new PhaseExecutionAttachment(WARN, WARNING_MESSAGE));

        slave.toComputer().waitUntilOnline();

        ProvisioningActivity.Id okId = new ProvisioningActivity.Id("MyCloud", "working-template", "future-agent");
        provisioningListener.onStarted(okId);
        slave = TrackedAgent.create(okId, j);
        provisioningListener.onComplete(okId, slave);
        slave.toComputer().waitUntilOnline();
        Thread.sleep(500);
        slave.toComputer().doDoDelete();

        Thread.sleep(500);

        // Then
        ProvisioningActivity failedToProvision = cs.getActivityFor(failId);
        assertEquals(failId, failedToProvision.getId());
        assertEquals(FAIL, failedToProvision.getStatus());
        assertNull(failedToProvision.getPhaseExecution(LAUNCHING));
        assertNull(failedToProvision.getPhaseExecution(OPERATING));
        assertNotNull(failedToProvision.getPhaseExecution(COMPLETED));
        PhaseExecution failedProvisioning = failedToProvision.getPhaseExecution(PROVISIONING);
        assertEquals(FAIL, failedProvisioning.getStatus());
        PhaseExecutionAttachment.ExceptionAttachment exception = (PhaseExecutionAttachment.ExceptionAttachment) failedProvisioning.getAttachments().get(0);
        assertEquals(EXCEPTION_MESSAGE, exception.getTitle());
        assertThat(exception.getText(), startsWith("java.lang.Exception: " + EXCEPTION_MESSAGE));

        JenkinsRule.WebClient wc = j.createWebClient();
        j.jenkins.setAuthorizationStrategy(AuthorizationStrategy.UNSECURED);

        Page page = wc.goTo("cloud-stats").getAnchorByHref("/jenkins" + cs.getUrl(failedToProvision, failedProvisioning, exception)).click();
        assertThat(page.getWebResponse().getContentAsString(), containsString(EXCEPTION_MESSAGE));

        ProvisioningActivity ok = cs.getActivityFor(okId);
        assertEquals(okId, ok.getId());
        assertEquals(OK, ok.getStatus());
        assertNotNull(ok.getPhaseExecution(PROVISIONING));
        assertNotNull(ok.getPhaseExecution(LAUNCHING));
        assertNotNull(ok.getPhaseExecution(OPERATING));
        assertNotNull(ok.getPhaseExecution(COMPLETED));

        ProvisioningActivity warn = cs.getActivityFor(warnId);
        assertEquals(warnId, warn.getId());
        assertEquals(WARN, warn.getStatus());
        assertNotNull(warn.getPhaseExecution(PROVISIONING));
        assertNotNull(warn.getPhaseExecution(LAUNCHING));
        assertNotNull(warn.getPhaseExecution(OPERATING));
        assertNull(warn.getPhaseExecution(COMPLETED));
        PhaseExecution warnedLaunch = warn.getPhaseExecution(LAUNCHING);
        assertEquals(WARN, warnedLaunch.getStatus());
        assertEquals(WARNING_MESSAGE, warnedLaunch.getAttachments().get(0).getTitle());

        assertEquals(50D, CloudStatistics.get().getIndex().cloudHealth("MyCloud").getOverall().getPercentage(), 0);
        assertEquals(100D, CloudStatistics.get().getIndex().cloudHealth("PickyCloud").getOverall().getPercentage(), 0);

        //j.interactiveBreak();

        assertNotNull(wc.goTo("").getAnchorByText("MyCloud"));
        j.jenkins.clouds.replace(new TestCloud("new")); // Remove used clouds
        try {
            wc.goTo("").getAnchorByText("MyCloud");
            fail("Dead link found");
        } catch (ElementNotFoundException e) {
            // Expected
        }
    }

    @Test
    public void renameActivity() throws Exception {
        CloudStatistics cs = CloudStatistics.get();
        CloudStatistics.ProvisioningListener l = CloudStatistics.ProvisioningListener.get();

        ProvisioningActivity.Id fixup = new ProvisioningActivity.Id("Cloud", "template", "incorrectName");
        ProvisioningActivity.Id assign = new ProvisioningActivity.Id("Cloud", "template");
        ProvisioningActivity fActivity = l.onStarted(fixup);
        ProvisioningActivity aActivity = l.onStarted(assign);

        assertEquals("incorrectName", fActivity.getName());
        assertEquals("template", aActivity.getName());

        TrackedAgent fSlave = new TrackedAgent(fixup, j, "correct-name");
        TrackedAgent aSlave = new TrackedAgent(assign, j, "Some Name");

        l.onComplete(fixup, fSlave);
        l.onComplete(assign, aSlave);

        assertEquals(fSlave.getDisplayName(), fActivity.getName());
        assertEquals(aSlave.getDisplayName(), aActivity.getName());

        // Node update
        // Until `jenkins.getNodesObject.replaceNode(..., ...);` is exposed
        j.jenkins.removeNode(aSlave);
        TrackedAgent replacement = new TrackedAgent(assign, j, "Updated Name");
        j.jenkins.addNode(replacement);

        NodeListener.fireOnUpdated(aSlave, replacement);
        assertEquals("Updated Name", aActivity.getName());

        // Explicit rename
        fActivity.rename("renamed");

        assertEquals("renamed", cs.getActivityFor(fixup).getName());
    }

    @Test // Single cyclic buffer ware split to active and archived activities
    @LocalData
    public void migrateToV03() {
        CloudStatistics cs = CloudStatistics.get();
        assertEquals(2, cs.getActivities().size());
        assertEquals(1, cs.getNotCompletedActivities().size());

        assertEquals(cs.getRetainedActivities(), cs.getNotCompletedActivities());
    }

    @Test @Issue("JENKINS-41037")
    public void modifiedWhileSerialized() throws Exception {
        final CloudStatistics cs = CloudStatistics.get();
        final CloudStatistics.ProvisioningListener l = CloudStatistics.ProvisioningListener.get();
        final ProvisioningActivity activity = l.onStarted(new ProvisioningActivity.Id("Cloud", "template", "PAOriginal"));
        final StatsModifyingAttachment blocker = new StatsModifyingAttachment(OK, "Blocker");
        Computer.threadPoolForRemoting.submit(new Callable<Object>() {
            @Override public Object call() {
                cs.attach(activity, PROVISIONING, blocker);
                cs.persist();
                return null;
            }
        }).get();

        String serialized = cs.getConfigFile().asString();
        assertThat(serialized, not(containsString("ConcurrentModificationException")));
        assertThat(serialized, not(containsString("active class=\"linked-hash-set\"")));
        assertThat(serialized, containsString("Blocker"));
        assertThat(serialized, containsString("PAOriginal"));
        assertThat(serialized, containsString("PAModifying"));
        assertThat(serialized, containsString("active class=\"java.util.concurrent.CopyOnWriteArrayList\""));

        assertEquals(cs.getRetainedActivities(), cs.getNotCompletedActivities());
    }

    @Test
    public void multipleAttachmentsForPhase() throws Exception {
        CloudStatistics cs = CloudStatistics.get();
        CloudStatistics.ProvisioningListener provisioningListener = CloudStatistics.ProvisioningListener.get();

        ProvisioningActivity.Id pid = new ProvisioningActivity.Id("cloud", "template");
        ProvisioningActivity pa = provisioningListener.onStarted(pid);
        cs.attach(pa, PROVISIONING, new PhaseExecutionAttachment.ExceptionAttachment(OK, new Error("OKmsg")));
        cs.attach(pa, PROVISIONING, new PhaseExecutionAttachment.ExceptionAttachment(WARN, new Error("WARNmsg")));

        pa.enter(LAUNCHING);

        cs.attach(pa, LAUNCHING, new PhaseExecutionAttachment.ExceptionAttachment(WARN, new Error("WARNmsg")));
        cs.attach(pa, LAUNCHING, new PhaseExecutionAttachment.ExceptionAttachment(FAIL, new Error("FAILmsg")));

        // Attaching failure caused the activity to complete
        cs.attach(pa, COMPLETED, new PhaseExecutionAttachment.ExceptionAttachment(OK, new Error("OKmsg1")));
        cs.attach(pa, COMPLETED, new PhaseExecutionAttachment.ExceptionAttachment(OK, new Error("OKmsg2")));

        // All 6 attachments can be navigated to
        JenkinsRule.WebClient wc = j.createWebClient();
        HtmlPage csp = wc.goTo("cloud-stats");
        ArrayList<HtmlAnchor> attachments = new ArrayList<>();
        String numberedUrl = "WILL_BE_OVERRIDEN";
        for (HtmlAnchor anchor : csp.getAnchors()) {
            String href = anchor.getHrefAttribute();
            if (href.contains(Integer.toString(pa.getId().getFingerprint()))) {
                attachments.add(anchor);
                if (href.contains(":1/")) {
                    numberedUrl = href;
                }
            }
        }
        assertThat(attachments.size(), equalTo(6));
        for (HtmlAnchor attachment : attachments) {
            wc.goTo("cloud-stats");
            Page attPage = attachment.click();
            assertThat(attPage.getWebResponse().getContentAsString(), containsString("java.lang.Error"));
        }

        URL url = j.getURL();
        numberedUrl = url.getProtocol() + "://" + url.getAuthority() + numberedUrl;
        wc.getPage(new URL(url, numberedUrl));
        wc.getPage(new URL(url, numberedUrl.replaceAll(":1", ":0")));

        wc.getOptions().setThrowExceptionOnFailingStatusCode(false);
        wc.getOptions().setPrintContentOnFailingStatusCode(false);
        //noinspection deprecation
        assertEquals(404, wc.getPage(new URL(url, numberedUrl.replaceAll(":1", ":17"))).getWebResponse().getStatusCode());

        assertEquals(cs.getRetainedActivities(), cs.getNotCompletedActivities());
    }

    @Test
    @LocalData
    @Issue("JENKINS-41037")
    public void migrateToV010() throws Exception {
        CloudStatistics cs = CloudStatistics.get();
        ProvisioningActivity activity = cs.getActivities().iterator().next();
        assertThat(activity.getName(), equalTo("Asdf"));
        cs.persist();

        String serialized = cs.getConfigFile().asString();
        assertThat(serialized, not(containsString("active class=\"linked-hash-set\"")));
        assertThat(serialized, containsString("active class=\"java.util.concurrent.CopyOnWriteArrayList\""));

        assertEquals(cs.getRetainedActivities(), cs.getNotCompletedActivities());
    }

    @Test
    @LocalData
    public void migrateToV013() throws Exception {
        CloudStatistics cs = CloudStatistics.get();
        ProvisioningActivity activity = cs.getActivities().iterator().next();
        List<PhaseExecutionAttachment.ExceptionAttachment> attachments = activity.getPhaseExecution(PROVISIONING).getAttachments(PhaseExecutionAttachment.ExceptionAttachment.class);
        PhaseExecutionAttachment.ExceptionAttachment partial = attachments.get(0);
        assertThat(partial.getDisplayName(), equalTo("EXCEPTION_MESSAGE"));
        assertThat(partial.getText(), equalTo("Plugin was unable to deserialize the exception from version 0.12 or older"));

        PhaseExecutionAttachment.ExceptionAttachment full = attachments.get(1);

        final String EX_MSG = "java.lang.NullPointerException";
        assertThat(full.getDisplayName(), equalTo(EX_MSG));
        assertThat(full.getText(), startsWith(EX_MSG));

        // Extract the text on next line by platform independent impl.
        String subStr = full.getText().substring(full.getText().indexOf(EX_MSG) + EX_MSG.length());
        for (int i=0;; i++) {
            if (!Character.isWhitespace(subStr.charAt(i))) {
                subStr = subStr.substring(i);
                break;
            }
        }
        assertThat(subStr, startsWith("at org.jenkinsci.plugins.cloudstats.CloudStatisticsTest.migrateToV013"));

        cs.persist();
        assertThat(cs.getConfigFile().asString(), not(containsString("suppressedExceptions")));

        assertEquals(cs.getRetainedActivities(), cs.getNotCompletedActivities());
    }

    // Test ConcurrentModificationException in CloudStatistics.save()
    @Test
    @Issue("JENKINS-49162")
    public void testConcurrentModificationException() throws Exception {
        Runnable activityProducer = new Runnable() {
            @Override
            public void run() {
                for (;;) {
                    try {
                        ProvisioningActivity activity = CloudStatistics.ProvisioningListener.get().onStarted(new ProvisioningActivity.Id("test1", null, "test1"));
                        activity.enterIfNotAlready(LAUNCHING);
                        Thread.sleep(new Random().nextInt(50));
                        activity.enterIfNotAlready(OPERATING);
                        Thread.sleep(new Random().nextInt(50));
                        activity.enterIfNotAlready(COMPLETED);
                        Thread.sleep(new Random().nextInt(50));
                    } catch (InterruptedException e) {
                        break;
                    } catch (Exception e) {
                        e.printStackTrace();
                    }

                    if (Thread.interrupted()) break;
                }
            }
        };

        Runnable activitiesSaver = new Runnable() {
            @Override
            public void run() {
                for (;;) {
                    try {
                        Thread.sleep(new Random().nextInt(100));
                        CloudStatistics.get().save();
                    } catch (InterruptedException e) {
                        break;
                    } catch (Exception e) {
                        e.printStackTrace();
                    }

                    if (Thread.interrupted()) break;
                }
            }
        };

        Runnable[] runnables = new Runnable[] {
                activityProducer, activityProducer, activityProducer, activityProducer, activityProducer,
                activityProducer, activityProducer, activityProducer, activityProducer, activityProducer,
                activityProducer, activityProducer, activityProducer, activityProducer, activityProducer,
                activityProducer, activityProducer, activityProducer, activityProducer, activityProducer,
                activitiesSaver
        };
        Thread[] threads = new Thread[runnables.length];
        try {
            for (int i = 0; i < runnables.length; i++) {
                threads[i] = new Thread(runnables[i]);
                threads[i].start();
            }

            Thread.sleep(10000);
        } catch (InterruptedException e) {
            // terminate after interrupting all children in finally
        } finally {
            for (Thread thread: threads) {
                assert thread.isAlive(); // Died with exception
                thread.interrupt();
            }
            // Avoid to return NULL from Jenkins.getInstance() (IllegalStateException)
            Thread.sleep(100);
        }
    }

    // Activity that adds another one while being written to simulate concurrent iteration and update
    private static final class StatsModifyingAttachment extends PhaseExecutionAttachment  {

        public StatsModifyingAttachment(@Nonnull ProvisioningActivity.Status status, @Nonnull String title) {
            super(status, title);
        }

        private Object writeReplace() throws ObjectStreamException {
            try {
                // Avoid saving as it is a) not related to test and b) spins infinite recursion of saving
                BulkChange bc = new BulkChange(CloudStatistics.get());
                final CloudStatistics.ProvisioningListener l = CloudStatistics.ProvisioningListener.get();
                l.onStarted(new ProvisioningActivity.Id("Cloud", "template", "PAModifying"));
                bc.abort();
            } catch (Throwable e) {
                return e;
            }
            return this;
        }
    }

    private void detectCompletionNow() {
        j.jenkins.getExtensionList(CloudStatistics.DanglingSlaveScavenger.class).get(0).doRun();
    }

    private static class LaunchSuccessfully extends TestCloud.Launcher {

        @Override
        public Node call() throws Exception {
            return TrackedAgent.create(id, j);
        }
    }
}

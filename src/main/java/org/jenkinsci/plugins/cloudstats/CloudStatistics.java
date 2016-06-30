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

import hudson.BulkChange;
import hudson.Extension;
import hudson.XmlFile;
import hudson.model.Computer;
import hudson.model.Label;
import hudson.model.ManagementLink;
import hudson.model.Node;
import hudson.model.PeriodicWork;
import hudson.model.Saveable;
import hudson.model.TaskListener;
import hudson.slaves.Cloud;
import hudson.slaves.CloudProvisioningListener;
import hudson.slaves.ComputerListener;
import hudson.slaves.NodeProvisioner;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.DoNotUse;
import org.kohsuke.accmod.restrictions.NoExternalUse;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Statistics of past cloud activities.
 */
@Extension
public class CloudStatistics extends ManagementLink implements Saveable {

    private static final Logger LOGGER = Logger.getLogger(CloudStatistics.class.getName());

    /*
     * The log itself uses synchronized collection, to manipulate single entry it needs to be explicitly synchronized.
     */
    private final @Nonnull CyclicThreadSafeCollection<ProvisioningActivity> log = new CyclicThreadSafeCollection<>(100);

    /**
     * Get the singleton instance.
     */
    public static @Nonnull CloudStatistics get() {
        return jenkins().getExtensionList(CloudStatistics.class).get(0);
    }

    @Restricted(NoExternalUse.class)
    public CloudStatistics() {
        try {
            load();
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Unable to load stored statistics", e);
        }
    }

    public String getDisplayName() {
        return "Cloud Statistics";
    }

    @Override
    public String getIconFileName() {
        // This _needs_ to be done in getIconFileName only because of JENKINS-33683.
        Jenkins jenkins = jenkins();
        if (!jenkins.hasPermission(Jenkins.ADMINISTER)) return null;
        if (jenkins.clouds.isEmpty() && log.isEmpty()) return null;
        return "graph.png";
    }

    @Override
    public String getUrlName() {
        return "cloud-stats";
    }

    @Override
    public String getDescription() {
        return "Report of current and past provisioning activities";
    }

    public List<ProvisioningActivity> getActivities() {
        return log.toList();
    }

    public @CheckForNull ProvisioningActivity getActivityFor(ProvisioningActivity.Id id) {
        for (ProvisioningActivity activity : log.toList()) {
            if (activity.isFor(id)) {
                return activity;
            }
        }

        LOGGER.log(Level.WARNING, "No activity tracked for " + id, new IllegalStateException());
        return null;
    }

    public @CheckForNull ProvisioningActivity getActivityFor(TrackedItem item) {
        ProvisioningActivity.Id id = item.getId();
        if (id == null) return null;
        return getActivityFor(id);
    }

    public ActivityIndex getIndex() {
        return new ActivityIndex(log.toList());
    }

    @Restricted(NoExternalUse.class) // view only
    public ProvisioningActivity getActivity(@Nonnull String hashString) {
        int hash;
        try {
            hash = Integer.parseInt(hashString);
        } catch (NumberFormatException nan) {
            return null;
        }

        for (ProvisioningActivity activity : log) {
            if (activity.getId().getFingerprint() == hash) {
                return activity;
            }
        }

        return null;
    }

    @Restricted(NoExternalUse.class) // view only
    public @CheckForNull String getUrl(
            @Nonnull ProvisioningActivity activity,
            @Nonnull PhaseExecution phaseExecution,
            @Nonnull PhaseExecutionAttachment attachment
    ) {
        activity.getClass(); phaseExecution.getClass(); attachment.getClass();

        // No UI
        if (attachment.getUrlName() == null) return null;

        StringBuilder url = new StringBuilder();
        url.append("activity/").append(activity.getId().getFingerprint()).append('/');
        url.append("phase/").append(phaseExecution.getPhase().toString()).append('/');
        url.append(phaseExecution.getUrlName(attachment)).append('/');
        return url.toString();
    }

    /**
     * Attach information to activity's phase execution.
     */
    public void attach(@Nonnull ProvisioningActivity activity, @Nonnull ProvisioningActivity.Phase phase, @Nonnull PhaseExecutionAttachment attachment) {
        activity.attach(phase, attachment);
        // Enforce attachment going through this class so we know when to save
        persist();
    }

    public void save() throws IOException {
        getConfigFile().write(this);
    }

    private void persist() {
        try {
            save();
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Unable to store cloud statistics", e);
        }
    }

    private void load() throws IOException {
        final XmlFile file = getConfigFile();
        if(file.exists()) {
            file.unmarshal(this);
        }
    }

    private XmlFile getConfigFile() {
        return new XmlFile(Jenkins.XSTREAM, new File(new File(
                jenkins().root,
                getClass().getCanonicalName() + ".xml"
        ).getAbsolutePath()));
    }

    /**
     * Listen to ongoing provisioning activities.
     *
     * All activities that are triggered by Jenkins queue load (those that goes through {@link NodeProvisioner}) are
     * reported by Jenkins core. This api needs to be called by plugin if and only if the slaves are provisioned differently.
     */
    @Extension
    public static class ProvisioningListener extends CloudProvisioningListener {

        private final CloudStatistics stats = CloudStatistics.get();

        @Override @Restricted(DoNotUse.class)
        public void onStarted(Cloud cloud, Label label, Collection<NodeProvisioner.PlannedNode> plannedNodes) {
            BulkChange change = new BulkChange(stats);
            try {
                boolean changed = false;
                for (NodeProvisioner.PlannedNode plannedNode : plannedNodes) {
                    ProvisioningActivity.Id id = getIdFor(plannedNode);
                    if (id != null) {
                        onStarted(id);
                        changed = true;
                    }
                }

                if (changed) {
                    stats.persist();
                }
            } finally {
                change.abort();
            }
        }

        /**
         * Inform plugin provisioning has started. This is only needed when provisioned outside {@link NodeProvisioner}.
         *
         * @param id Unique identifier of the activity. The plugin is responsible for this to be unique and all subsequent
         *           calls are identified by the same Id instance.
         */
        public @Nonnull ProvisioningActivity onStarted(@Nonnull ProvisioningActivity.Id id) {
            ProvisioningActivity activity = new ProvisioningActivity(id);
            stats.log.add(activity);
            // Do not save in case called from loop from an overload
            if (!BulkChange.contains(stats)) {
                stats.persist();
            }
            return activity;
        }

        @Override @Restricted(DoNotUse.class)
        public void onComplete(NodeProvisioner.PlannedNode plannedNode, Node node) {
            ProvisioningActivity.Id id = getIdFor(plannedNode);
            if (id != null) {
                onComplete(id, node);
            }
        }

        /**
         * Inform plugin provisioning has completed. This is only needed when provisioned outside {@link NodeProvisioner}.
         *
         * The method should be called before the node is added to Jenkins.
         */
        public @CheckForNull ProvisioningActivity onComplete(@Nonnull ProvisioningActivity.Id id, @Nonnull Node node) {
            ProvisioningActivity activity = stats.getActivityFor(id);
            if (activity != null) {
                activity.rename(node.getDisplayName());
                stats.persist();
            }
            return activity;
        }

        @Override @Restricted(DoNotUse.class)
        public void onFailure(NodeProvisioner.PlannedNode plannedNode, Throwable t) {
            ProvisioningActivity.Id id = getIdFor(plannedNode);
            if (id != null) {
                onFailure(id, t);
            }
        }

        /**
         * Inform plugin provisioning has failed. This is only needed when provisioned outside {@link NodeProvisioner}.
         *
         * No node with {@code id} should be added added to jenkins.
         */
        public @CheckForNull ProvisioningActivity onFailure(@Nonnull ProvisioningActivity.Id id, @Nonnull Throwable throwable) {
            ProvisioningActivity activity = stats.getActivityFor(id);
            if (activity != null) {
                stats.attach(activity, ProvisioningActivity.Phase.PROVISIONING, new PhaseExecutionAttachment.ExceptionAttachment(
                        ProvisioningActivity.Status.FAIL, throwable
                ));
            }
            return activity;
        }

        public static ProvisioningListener get() {
            return jenkins().getExtensionList(ProvisioningListener.class).get(0);
        }
    }

    private static @Nonnull Jenkins jenkins() {
        Jenkins jenkins = Jenkins.getInstance();
        if (jenkins == null) throw new IllegalStateException();
        return jenkins;
    }

    // TODO: ComputerListener#preLaunch might not have access to Node instance:
    //    at hudson.slaves.SlaveComputer._connect(SlaveComputer.java:219)
    //    at hudson.model.Computer.connect(Computer.java:339)
    //    at hudson.slaves.RetentionStrategy$1.start(RetentionStrategy.java:108)
    //    at hudson.model.AbstractCIBase.updateComputer(AbstractCIBase.java:129)
    //    at hudson.model.AbstractCIBase.updateComputerList(AbstractCIBase.java:180)
    //            - locked <0x13cf> (a java.lang.Object)
    //    at jenkins.model.Jenkins.updateComputerList(Jenkins.java:1200)
    //    at jenkins.model.Jenkins.setNodes(Jenkins.java:1696)
    //    at jenkins.model.Jenkins.addNode(Jenkins.java:1678)
    //            - locked <0x13a6> (a hudson.model.Hudson)
    //    at org.jvnet.hudson.test.JenkinsRule.createSlave(JenkinsRule.java:814)
    @Extension @Restricted(NoExternalUse.class)
    public static class OperationListener extends ComputerListener {

        private final CloudStatistics stats = CloudStatistics.get();

        @Override
        public void preLaunch(Computer c, TaskListener taskListener) throws IOException, InterruptedException {
            ProvisioningActivity.Id id = getIdFor(c);
            if (id == null) return;
            ProvisioningActivity activity = stats.getActivityFor(id);
            if (activity == null) return;

            // Do not enter second time on relaunch
            boolean entered = activity.enterIfNotAlready(ProvisioningActivity.Phase.LAUNCHING);
            if (entered) {
                stats.save();
            }
        }

        @Override
        public void onLaunchFailure(Computer c, TaskListener taskListener) throws IOException, InterruptedException {
            ProvisioningActivity.Id id = getIdFor(c);
            if (id == null) return;
            ProvisioningActivity activity = stats.getActivityFor(id);
            if (activity == null) return;

            // TODO attach details
            // ...
        }

        @Override public void onOnline(Computer c, TaskListener listener) throws IOException, InterruptedException {
            ProvisioningActivity.Id id = getIdFor(c);
            if (id == null) return;
            ProvisioningActivity activity = stats.getActivityFor(id);
            if (activity == null) return;

            // Do not enter second time on relaunch
            boolean entered = activity.enterIfNotAlready(ProvisioningActivity.Phase.OPERATING);
            if (entered) {
                stats.save();
            }
        }
    }

    // TODO Replace with better extension point https://issues.jenkins-ci.org/browse/JENKINS-33780
    // TODO does not support slave rename at all.
    @Restricted(NoExternalUse.class) @Extension
    public static class SlaveCompletionDetector extends PeriodicWork {

        private final CloudStatistics stats = CloudStatistics.get();

        @Override
        public long getRecurrencePeriod() {
            return MIN * 10;
        }

        @Override
        protected void doRun() throws Exception {
            List<ProvisioningActivity.Id> trackedExisting = new ArrayList<>();
            for (Computer computer : jenkins().getComputers()) {
                if (computer instanceof TrackedItem) {
                    trackedExisting.add(((TrackedItem) computer).getId());
                }
            }

            boolean changed = false;
            for (ProvisioningActivity activity: stats.log) {
                Map<ProvisioningActivity.Phase, PhaseExecution> executions = activity.getPhaseExecutions();

                if (executions.get(ProvisioningActivity.Phase.COMPLETED) != null) continue; // Completed already
                assert activity.getStatus() != ProvisioningActivity.Status.FAIL; // Should be completed already if failed

                // TODO there is still a chance some activity will never be recognised as completed when provisioning completes without error and launching never starts for some reason
                if (executions.get(ProvisioningActivity.Phase.LAUNCHING) == null) continue; // Still provisioning

                if (trackedExisting.contains(activity.getId())) continue; // Still operating

                activity.enter(ProvisioningActivity.Phase.COMPLETED);
                changed = true;
            }
            if (changed) {
                stats.save();
            }
        }
    }

    private static @CheckForNull ProvisioningActivity.Id getIdFor(NodeProvisioner.PlannedNode plannedNode) {
        if (!(plannedNode instanceof TrackedItem)) {
            LOGGER.info("No support for cloud-stats-plugin by " + plannedNode.getClass());
            return null;
        }

        return ((TrackedItem) plannedNode).getId();
    }

    private static @CheckForNull ProvisioningActivity.Id getIdFor(Computer computer) {
        if (computer instanceof Jenkins.MasterComputer) return null;

        if (!(computer instanceof TrackedItem)) {
            LOGGER.info("No support for cloud-stats-plugin by " + computer.getClass());
            return null;
        }

        return ((TrackedItem) computer).getId();
    }
}

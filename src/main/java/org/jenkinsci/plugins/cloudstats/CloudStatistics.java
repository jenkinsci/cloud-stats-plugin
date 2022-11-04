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

import com.google.common.annotations.VisibleForTesting;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.BulkChange;
import hudson.Extension;
import hudson.FilePath;
import hudson.XmlFile;
import hudson.model.Computer;
import hudson.model.Label;
import hudson.model.ManagementLink;
import hudson.model.Node;
import hudson.model.PeriodicWork;
import hudson.model.Saveable;
import hudson.model.TaskListener;
import hudson.security.Permission;
import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.Cloud;
import hudson.slaves.CloudProvisioningListener;
import hudson.slaves.ComputerListener;
import hudson.slaves.NodeProvisioner;
import jenkins.model.Jenkins;
import jenkins.model.NodeListener;
import jenkins.util.Timer;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.DoNotUse;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.StaplerProxy;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.annotation.concurrent.GuardedBy;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Statistics of provisioning activities.
 */
@Extension
public class CloudStatistics extends ManagementLink implements Saveable, StaplerProxy {

    private static final Logger LOGGER = Logger.getLogger(CloudStatistics.class.getName());

    /*package*/ static final String ARCHIVE_RECORDS_PROPERTY_NAME = "org.jenkinsci.plugins.cloudstats.CloudStatistics.ARCHIVE_RECORDS";

    /**
     * The number of completed records to be stored.
     */
    @SuppressFBWarnings(value = "MS_SHOULD_BE_FINAL", justification = "Not final for testing")
    public static /*final*/ int ARCHIVE_RECORDS = Integer.getInteger(ARCHIVE_RECORDS_PROPERTY_NAME, 100);

    /**
     * All activities that are not in completed state.
     *
     * The consistency between 'active' and 'log' is ensured by active monitor.
     */
    @GuardedBy("active") // JENKINS-41037: XStream can iterate while it is written
    private /*final except for serialization*/ @NonNull Collection<ProvisioningActivity> active = new CopyOnWriteArrayList<>();

    /**
     * Activities that are in completed state. The oldest entries (least recently completed) are rotated.
     *
     * The collection itself uses synchronized collection, to manipulate single entry it needs to be explicitly synchronized.
     */
    @GuardedBy("active")
    private /*final except for serialization*/ @NonNull CyclicThreadSafeCollection<ProvisioningActivity> log = new CyclicThreadSafeCollection<>(ARCHIVE_RECORDS);

    /**
     * Get the singleton instance.
     */
    public static @NonNull CloudStatistics get() {
        return Jenkins.getInstance().getExtensionList(CloudStatistics.class).get(0);
    }

    @Restricted(NoExternalUse.class)
    public CloudStatistics() {
        try {
            load();
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Unable to load stored statistics", e);
        }

        // Complete activities that survived restart in provisioning state as that is a symptom of a restart while NodeProvisioner
        // was tracking the provisioning. However, that is interrupted by restart so such activities are never completed
        // unless we intervene here.
        for (ProvisioningActivity activity : getActivities()) {
            if (activity.getCurrentPhase() == ProvisioningActivity.Phase.PROVISIONING) {
                PhaseExecutionAttachment attachment = new PhaseExecutionAttachment(
                        ProvisioningActivity.Status.OK, "Provisioning interrupted by restart"
                );
                activity.enter(ProvisioningActivity.Phase.COMPLETED);
                attach(activity, ProvisioningActivity.Phase.COMPLETED, attachment);
                archive(activity);
                LOGGER.info("Closing dangling provisioning activity " + activity);
            }
        }
    }

    public String getDisplayName() {
        return "Cloud Statistics";
    }

    @Override
    public String getIconFileName() {
        // This _needs_ to be done in getIconFileName only because of JENKINS-33683.
        Jenkins jenkins = Jenkins.get();
        if (!jenkins.hasPermission(getRequiredPermission())) return null;
        if (jenkins.clouds.isEmpty() && isEmpty()) return null;
        return "graph.png";
    }

    @Override
    public @NonNull Permission getRequiredPermission() {
        return Jenkins.SYSTEM_READ;
    }

    @Override
    public Object getTarget() {
        Jenkins.get().checkPermission(getRequiredPermission());
        return this;
    }

    private boolean isEmpty() {
        synchronized (active) {
            return log.isEmpty() && active.isEmpty();
        }
    }

    /**
     * Get activities that was not completed yet.
     */
    public @NonNull Collection<ProvisioningActivity> getNotCompletedActivities() {
        ArrayList<ProvisioningActivity> activeCopy;
        synchronized (active) {
            activeCopy = new ArrayList<>(active);
        }

        // Perform explicit removal of completed activities as `active` is not guaranteed to contain not completed activities only.
        ArrayList<ProvisioningActivity> ret = new ArrayList<>(activeCopy.size());
        for (ProvisioningActivity activity : activeCopy) {
            if (activity.getCurrentPhase() != ProvisioningActivity.Phase.COMPLETED) {
                ret.add(activity);
            }
        }

        return ret;
    }

    @VisibleForTesting
    /*package*/ @NonNull Collection<ProvisioningActivity> getRetainedActivities() {
        synchronized (active) {
            return new ArrayList<>(active);
        }
    }

    @Override
    public String getUrlName() {
        return "cloud-stats";
    }

    @Override
    public String getDescription() {
        return "Report of current and past provisioning activities";
    }

    /**
     * Name of the category for this management link. Exists so that plugins with core dependency pre-dating the version
     * when this was introduced can define a category.
     *
     * TODO when the core version is &gt;2.226 change this to override {@code getCategory()} instead
     *
     * @return name of the desired category, one of the enum values of Category, e.g. {@code STATUS}.
     * @since 2.226 of Jenkins core
     */
    public String getCategoryName() {
        return "STATUS";
    }

    public List<ProvisioningActivity> getActivities() {
        synchronized (active) {
            ArrayList<ProvisioningActivity> out = new ArrayList<>(active.size() + log.size());
            out.addAll(log.toList());
            out.addAll(active);
            return out;
        }
    }

    /**
     * Get activity that is suspected to be completed already.
     *
     * @return The activity or null if rotated already.
     */
    public @CheckForNull ProvisioningActivity getPotentiallyCompletedActivityFor(ProvisioningActivity.Id id) {
        if (id == null) return null;

        for (ProvisioningActivity activity : getActivities()) {
            if (activity.isFor(id)) {
                return activity;
            }
        }
        return null;
    }

    /**
     * Get "active" activity, missing activity will be logged.
     */
    public @CheckForNull ProvisioningActivity getActivityFor(ProvisioningActivity.Id id) {
        ProvisioningActivity activity = getPotentiallyCompletedActivityFor(id);
        if (activity != null) return activity;

        LOGGER.log(Level.WARNING, "No activity tracked for " + id, new IllegalStateException());
        return null;
    }

    public @CheckForNull ProvisioningActivity getActivityFor(TrackedItem item) {
        ProvisioningActivity.Id id = item.getId();
        if (id == null) return null;
        return getActivityFor(id);
    }

    public ActivityIndex getIndex() {
        return new ActivityIndex(getActivities());
    }

    @Restricted(NoExternalUse.class) // view only
    public ProvisioningActivity getActivity(@NonNull String hashString) {
        int hash;
        try {
            hash = Integer.parseInt(hashString);
        } catch (NumberFormatException nan) {
            return null;
        }

        for (ProvisioningActivity activity : getActivities()) {
            if (activity.getId().getFingerprint() == hash) {
                return activity;
            }
        }

        return null;
    }

    @Restricted(NoExternalUse.class) // view only
    public @CheckForNull String getUrl(
            @NonNull ProvisioningActivity activity,
            @NonNull PhaseExecution phaseExecution,
            @NonNull PhaseExecutionAttachment attachment
    ) {
        activity.getClass(); phaseExecution.getClass(); attachment.getClass();

        // No UI
        if (attachment.getUrlName() == null) return null;

        StringBuilder url = new StringBuilder("/cloud-stats/");
        url.append("activity/").append(activity.getId().getFingerprint()).append('/');
        url.append("phase/").append(phaseExecution.getPhase().toString()).append('/');
        url.append(phaseExecution.getUrlName(attachment)).append('/');
        return url.toString();
    }

    /**
     * Attach information to activity's phase execution.
     */
    // Enforce attach goes through this class to complete the activity upon first failure and persist as needed
    public void attach(@NonNull ProvisioningActivity activity, @NonNull ProvisioningActivity.Phase phase, @NonNull PhaseExecutionAttachment attachment) {
        activity.attach(phase, attachment);

        if (attachment.getStatus() == ProvisioningActivity.Status.FAIL) {
            boolean entered = activity.enterIfNotAlready(ProvisioningActivity.Phase.COMPLETED);
            if (entered) {
                archive(activity);
            }
        }
        persist();
    }

    public void save() throws IOException {
        if (BulkChange.contains(this)) return;

        getConfigFile().write(this);
    }

    /*package*/ void persist() {
        try {
            save();
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Unable to store cloud statistics", e);
        }
    }

    private void load() throws IOException {
        final XmlFile file = getConfigFile();
        if (file.exists()) {
            file.unmarshal(this);
        }

        boolean changed = false;

        // Migrate config from version 0.2 - even non-completed activities ware in log collection
        synchronized (active) {
            if (active.isEmpty()) {
                List<ProvisioningActivity> toSort = log.toList();
                log.clear();
                for (ProvisioningActivity activity: toSort) {
                    assert activity != null;
                    if (activity.getPhaseExecution(ProvisioningActivity.Phase.COMPLETED) == null) {
                        active.add(activity);
                        changed = true;
                    } else {
                        log.add(activity);
                    }
                }
            }
        }

        // Migrate config from version 0.22, the guarantee of everything in active is not completed is now strict
        // Making old data to strictly follow that. Note this does not alter the structure of the data, but only their semantics
        synchronized (active) {
            Collection<ProvisioningActivity> defensiveCopyOfActiveField = getRetainedActivities();
            for (ProvisioningActivity pa: defensiveCopyOfActiveField) {
                if (pa.getCurrentPhase() == ProvisioningActivity.Phase.COMPLETED) {
                    active.remove(pa);
                    log.add(pa);
                    changed = true;
                }
            }
        }

        if (changed) {
            persist();
        }
    }

    @SuppressWarnings("unused")
    private Object readResolve() {
        // Replace former implementation of active queue
        if (!(active instanceof CopyOnWriteArrayList)) {
            Collection<ProvisioningActivity> a = active;
            active = new CopyOnWriteArrayList<>();
            active.addAll(a);
        }

        try {
            // // There are reports when #data was witnessed null for no reason I could identify: JENKINS-47836, JENKINS-47836
            log.size();
        } catch (NullPointerException npe) {
            String msg = "Failed to properly deserialize cloud-stats records: ";
            log = new CyclicThreadSafeCollection<>(ARCHIVE_RECORDS);
            FilePath configFile = new FilePath(getConfigFile().getFile());
            try {
                if (configFile.exists()) {
                    FilePath target = new FilePath(new File(configFile.getRemote() + ".bak-JENKINS-44929"));
                    configFile.renameTo(target);
                    LOGGER.warning(msg + " Please file a bug report attaching " + target.getRemote());
                } else {
                    LOGGER.warning(msg + " " + configFile.getRemote() + " not found");
                }
            } catch (IOException | InterruptedException ex) {
                LOGGER.log(Level.SEVERE, msg + " Unable to capture the old config.", ex);
            }
        }

        // Resize the collection
        if (ARCHIVE_RECORDS != log.capacity()) {
            CyclicThreadSafeCollection<ProvisioningActivity> existing = log;
            log = new CyclicThreadSafeCollection<>(ARCHIVE_RECORDS);
            int added = 0;
            for (ProvisioningActivity pa : existing) {
                if (added > ARCHIVE_RECORDS) break; // Prevent adding more when shrinking
                log.add(pa);
                added++;
            }
        }

        return this;
    }

    /*package for testing*/ XmlFile getConfigFile() {
        return new XmlFile(Jenkins.XSTREAM, new File(new File(
                Jenkins.getInstance().root,
                getClass().getCanonicalName() + ".xml"
        ).getAbsolutePath()));
    }

    private void archive(ProvisioningActivity activity) {
        synchronized (active) {
            log.add(activity);
            active.remove(activity);
        }
    }

    /**
     * Listen to ongoing provisioning activities.
     *
     * All activities that are triggered by Jenkins queue load (those that goes through {@link NodeProvisioner}) are
     * reported by Jenkins core. This api needs to be called by plugin if and only if the agents are provisioned differently.
     *
     * Implementation note: onComplete and onFailure are being called while holding the queue lock from NodeProvisioner,
     * so the work is extracted to separate thread.
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
                    // Not using change.commit() here as persist handles exceptions already
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
        public @NonNull ProvisioningActivity onStarted(@NonNull ProvisioningActivity.Id id) {
            ProvisioningActivity activity = new ProvisioningActivity(id);
            synchronized (stats.active) {
                stats.active.add(activity);
            }
            stats.persist();
            return activity;
        }

        @Override @Restricted(DoNotUse.class)
        public void onComplete(NodeProvisioner.PlannedNode plannedNode, Node node) {
            ProvisioningActivity.Id id = getIdFor(plannedNode);
            if (id != null) {
                Timer.get().schedule(() -> { // run in different thread not to block queue lock
                    onComplete(id, node);
                }, 0, TimeUnit.SECONDS);
            }
        }

        /**
         * Inform plugin provisioning has completed. This is only needed when provisioned outside {@link NodeProvisioner}.
         *
         * The method should be called before the node is added to Jenkins.
         */
        public @CheckForNull ProvisioningActivity onComplete(@NonNull ProvisioningActivity.Id id, @NonNull Node node) {
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
                Timer.get().schedule(() -> { // run in different thread not to block queue lock
                    onFailure(id, t);
                }, 0, TimeUnit.SECONDS);
            }
        }

        /**
         * Inform plugin provisioning has failed. This is only needed when provisioned outside {@link NodeProvisioner}.
         *
         * No node with {@code id} should be added added to jenkins.
         */
        public @CheckForNull ProvisioningActivity onFailure(@NonNull ProvisioningActivity.Id id, @NonNull Throwable throwable) {
            ProvisioningActivity activity = stats.getActivityFor(id);
            if (activity != null) {
                stats.attach(activity, ProvisioningActivity.Phase.PROVISIONING, new PhaseExecutionAttachment.ExceptionAttachment(
                        ProvisioningActivity.Status.FAIL, throwable
                ));
            }
            return activity;
        }

        public static ProvisioningListener get() {
            return Jenkins.getInstance().getExtensionList(ProvisioningListener.class).get(0);
        }
    }

    @Extension @Restricted(NoExternalUse.class)
    public static class OperationListener extends ComputerListener {

        private final CloudStatistics stats = CloudStatistics.get();

        @Override
        public void preLaunch(Computer c, TaskListener taskListener) {
            ProvisioningActivity.Id id = getIdFor(c);
            if (id == null) return;
            ProvisioningActivity activity = stats.getActivityFor(id);
            if (activity == null) return;

            // Do not enter second time on relaunch
            boolean entered = activity.enterIfNotAlready(ProvisioningActivity.Phase.LAUNCHING);
            if (entered) {
                stats.persist();
            }
        }

        @Override
        public void onLaunchFailure(Computer c, TaskListener taskListener) {
            ProvisioningActivity.Id id = getIdFor(c);
            if (id == null) return;
            ProvisioningActivity activity = stats.getActivityFor(id);
            if (activity == null) return;

            // TODO attach details
            // ...
        }

        @Override
        public void onOnline(Computer c, TaskListener listener) {
            ProvisioningActivity.Id id = getIdFor(c);
            if (id == null) return;
            ProvisioningActivity activity = stats.getActivityFor(id);
            if (activity == null) return;

            // Do not enter second time on relaunch
            boolean entered = activity.enterIfNotAlready(ProvisioningActivity.Phase.OPERATING);
            if (entered) {
                stats.persist();
            }
        }
    }

    // In theory, this should not be needed once SlaveCompletionDetector can reliably be used. Keeping this around to
    // be sure not to leak a thing.
    @Restricted(NoExternalUse.class) @Extension
    public static class DanglingSlaveScavenger extends PeriodicWork {

        private final CloudStatistics stats = CloudStatistics.get();

        @Override
        public long getRecurrencePeriod() {
            return MIN * 10;
        }

        @Override
        protected void doRun() {
            List<ProvisioningActivity.Id> trackedExisting = new ArrayList<>();
            for (Computer computer : Jenkins.getInstance().getComputers()) {
                if (computer instanceof TrackedItem) {
                    trackedExisting.add(((TrackedItem) computer).getId());
                }
            }

            ArrayList<ProvisioningActivity> completed = new ArrayList<>();
            for (ProvisioningActivity activity: stats.getRetainedActivities()) {
                Map<ProvisioningActivity.Phase, PhaseExecution> executions = activity.getPhaseExecutions();

                if (executions.get(ProvisioningActivity.Phase.COMPLETED) != null) {
                    completed.add(activity);
                    continue; // Completed already
                }
                assert activity.getStatus() != ProvisioningActivity.Status.FAIL; // Should be completed already if failed

                // TODO there is still a chance some activity will never be recognised as completed when provisioning
                // completes without error and launching never starts for some reason
                if (executions.get(ProvisioningActivity.Phase.LAUNCHING) == null) continue; // Still provisioning

                if (trackedExisting.contains(activity.getId())) continue; // Still operating

                activity.enter(ProvisioningActivity.Phase.COMPLETED);
                completed.add(activity);
            }
            if (!completed.isEmpty()) {
                synchronized (stats.active) {
                    stats.log.addAll(completed);
                    stats.active.removeAll(completed);
                }
                stats.persist();
            }
        }
    }

    @Restricted(NoExternalUse.class) @Extension
    public static class SlaveCompletionDetector extends NodeListener {

        private final CloudStatistics stats = CloudStatistics.get();

        // Reflect renames so the name of the activity tracks the agent name
        @Override
        protected void onUpdated(@NonNull Node oldOne, @NonNull Node newOne) {
            if (oldOne.getNodeName().equals(newOne.getNodeName())) return; // Not renamed

            ProvisioningActivity.Id id = getIdFor(oldOne);
            if (id == null) return; // Not tracked

            ProvisioningActivity activity = stats.getActivityFor(id);
            if (activity == null) return;

            activity.rename(newOne.getNodeName());
            stats.persist();
        }

        @Override
        protected void onDeleted(@NonNull Node node) {
            ProvisioningActivity.Id id = getIdFor(node);
            if (id == null) return; // Not tracked

            ProvisioningActivity activity = stats.getActivityFor(id);
            if (activity == null) return;

            // The phase might already been entered in case cloud plugins needed to to add an attachment to the phase.
            // Also deletion was not detected by this plugin on time in the past so some plugins opted in to get more
            // accurate times.
            boolean entered = activity.enterIfNotAlready(ProvisioningActivity.Phase.COMPLETED);
            if (entered) {
                stats.archive(activity);
                stats.persist();
            }
        }

    }

    private static @CheckForNull ProvisioningActivity.Id getIdFor(NodeProvisioner.PlannedNode plannedNode) {
        if (!(plannedNode instanceof TrackedItem)) {
            logTypeNotSupported(plannedNode.getClass());
            return null;
        }

        return ((TrackedItem) plannedNode).getId();
    }

    private static @CheckForNull ProvisioningActivity.Id getIdFor(Node node) {
        if (node instanceof Jenkins) return null;

        if (!(node instanceof TrackedItem)) {
            LOGGER.info("No support for cloud-stats-plugin by " + node.getClass());
            return null;
        }

        return ((TrackedItem) node).getId();
    }

    private static @CheckForNull ProvisioningActivity.Id getIdFor(Computer computer) {
        if (computer instanceof Jenkins.MasterComputer) return null;
        if (!(computer instanceof AbstractCloudComputer)) return null;

        if (!(computer instanceof TrackedItem)) {
            logTypeNotSupported(computer.getClass());
            return null;
        }

        return ((TrackedItem) computer).getId();
    }

    private static void logTypeNotSupported(Class<?> type) {
        if (!loggedUnsupportedTypes.contains(type)) {
            LOGGER.info("No support for cloud-stats plugin by " + type);
            loggedUnsupportedTypes.add(type);
        }
    }
    private static final Set<Class> loggedUnsupportedTypes = Collections.synchronizedSet(new HashSet<>());
}

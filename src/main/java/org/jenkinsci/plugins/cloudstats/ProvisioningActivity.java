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

import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Record of one provisioning attempt's lifecycle.
 *
 * @author ogondza.
 */
@Restricted(NoExternalUse.class) // Until we gain more confidence in this API
public final class ProvisioningActivity {

    /**
     * Progress of an activity.
     */
    public enum Phase {
        /**
         * Acquiring the slave is in progress.
         */
        PROVISIONING,
        /**
         * Slave agent is being launched,
         */
        LAUNCHING,
        /**
         * The node is connected as Jenkins slave, possibly running builds.
         */
        OPERATING,
        /**
         * The resources (if any) as well as the computer should be gone.
         *
         * This phase is never started.
         */
        COMPLETED
    }

    public enum Status {
        /**
         * All went well.
         */
        OK,
        /**
         * There was a problem worth looking at, though provisioning managed to proceed.
         *
         * The identification of the cause should be in attachment.
         */
        WARN,
        /**
         * The activity was aborted because of a problem so it never delivered functional slave.
         *
         * The identification of the cause should be in attachment.
         */
        FAIL
    }

    /**
     * Phase execution record.
     *
     * While the phases starts in declared order, they might not complete in that order. Much less previous phase will
     * be completed before next one starts.
     *
     * There are several reasons for that: provisioning listener is called when the results are picked up, the slave
     * might have started launching agent in the meantime. There are plugin that in fact enforce the launch to complete,
     * before completing the {@link hudson.slaves.NodeProvisioner.PlannedNode#future}. To avoid any problems this can cause,
     * The execution of phases is expected to occur in order, the execution will receive attachments regardless if the
     * next phase started or not. For the time tracking purposes, the phase is considered completed as soon as the next
     * phase completes. IOW, despite the fact the slave already started launching, plugin can still append provisioning log.
     */
    public static class PhaseExecution {
        private final @Nonnull List<PhaseExecutionAttachment> attachments = new CopyOnWriteArrayList<>();
        private final @Nonnull long started;

        public PhaseExecution() {
            this.started = System.currentTimeMillis();
        }

        public @Nonnull List<PhaseExecutionAttachment> getAttachments() {
            return Collections.unmodifiableList(attachments);
        }

        public @Nonnull Status getStatus() {
            Status status = Status.OK;
            for (PhaseExecutionAttachment a : attachments) {
                if (a.getStatus().ordinal() > status.ordinal()) {
                    status = a.getStatus();
                }
            }
            return status;
        }

        public @Nonnull Date getStarted() {
            return new Date(started);
        }

        public void attach(PhaseExecutionAttachment phaseExecutionAttachment) {
            attachments.add(phaseExecutionAttachment);
        }
    }

    /**
     * Name of the cloud that is provisioning this.
     */
    private final @Nonnull String cloudName;

    /**
     * Name of the node being provisioned.
     */
    private final @Nonnull String nodeName;

    /**
     * Unique identifier of the activity.
     *
     * As cloud name or node name do not have to be unique (in time).
     */
    private final int fingerprint;

    /**
     * All phases the activity has started so far.
     */
    private final Map<Phase, PhaseExecution> progress;
    {
        progress = new LinkedHashMap(Phase.values().length);
        progress.put(Phase.PROVISIONING, null);
        progress.put(Phase.LAUNCHING, null);
        progress.put(Phase.OPERATING, null);
        progress.put(Phase.COMPLETED, null);
    }

    public ProvisioningActivity(Cloud cloud, NodeProvisioner.PlannedNode node) {
        this(cloud.name, node.displayName, getFingerprint(node));
    }

    private static int getFingerprint(NodeProvisioner.PlannedNode node) {
        return System.identityHashCode(node);
    }

    /*package for testing*/ ProvisioningActivity(String cloud, String node, int fingerprint) {
        this.cloudName = cloud;
        this.nodeName = node;
        this.fingerprint = fingerprint;
        enter(Phase.PROVISIONING);
    }

    public @Nonnull String getCloudName() {
        return cloudName;
    }

    public @Nonnull String getNodeName() {
        return nodeName;
    }

    public @Nonnull Date getStarted() {
        synchronized (progress) {
            return progress.get(Phase.PROVISIONING).getStarted();
        }
    }

    /**
     * {@link PhaseExecution} or null in case it is/was not executed.
     */
    public @CheckForNull PhaseExecution getPhaseExecution(@Nonnull Phase phase) {
        synchronized (progress) {
            return progress.get(phase);
        }
    }

    /**
     * Status of the activity as a whole.
     *
     * It is the works status of any of the phases, OK by default.
     */
    public @Nonnull Status getStatus() {
        synchronized (progress) {
            Status status = Status.OK;
            for (PhaseExecution e : progress.values()) {
                if (e == null) continue;
                Status s = e.getStatus();
                if (status.ordinal() < s.ordinal()) {
                    status = s;
                }
            }
            return status;
        }
    }

    /**
     * Make the phase of this activity entered.
     *
     * @throws IllegalArgumentException In case phases are not entered in declared order or entered repeatedly.
     */
    public void enter(@Nonnull Phase phase) {
        synchronized (progress) {
            if (progress.get(phase) != null) throw new IllegalStateException("The phase is already executing");

            for (Phase p: Phase.values()) {
                if (p == phase) break;
                if (progress.get(p) == null) throw new IllegalStateException(
                        "Unable to enter phase " + phase + " since previous phase " + p + " have not started yet"
                );
            }

            progress.put(phase, new PhaseExecution());
        }
    }

    /**
     * @return true if this activity tracks <tt>node</tt>'s progress.
     */
    public boolean isFor(NodeProvisioner.PlannedNode node) {
        return getFingerprint(node) == fingerprint;
    }

    @Override
    public String toString() {
        return String.format("Provisioning %s/%s", cloudName, nodeName);
    }

    @Override
    public boolean equals(Object o) {
        if (o == null) return false;
        if (o == this) return true;
        if (!o.getClass().equals(getClass())) return false;
        ProvisioningActivity rhs = (ProvisioningActivity) o;
        return fingerprint == rhs.fingerprint;
    }

    @Override
    public int hashCode() {
        return fingerprint;
    }
}

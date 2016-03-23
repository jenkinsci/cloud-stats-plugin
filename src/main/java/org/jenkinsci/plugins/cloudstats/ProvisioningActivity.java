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
import org.jvnet.localizer.Localizable;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
         * This phase is never completed.
         */
        COMPLETED
    }

    public enum PhaseStatus {
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
     * Additional information attached to the phase.
     */
    public static class Attachment {

        private final @Nonnull Localizable title;

        public Attachment(@Nonnull Localizable title) {
            this.title = title;
        }
    }

    public static class PhaseExecution {
        private final @Nonnull PhaseStatus status;
        private final @Nonnull List<Attachment> attachments;
        private final @Nonnull long completed;

        public PhaseExecution(@Nonnull PhaseStatus status, @CheckForNull List<Attachment> attachments) {
            this.status = status;
            this.completed = System.currentTimeMillis();
            this.attachments = attachments != null
                    ? Collections.unmodifiableList(new ArrayList<>(attachments))
                    : Collections.<Attachment>emptyList()
            ;
        }

        public @Nonnull List<Attachment> getAttachments() {
            return attachments;
        }

        public @Nonnull PhaseStatus getStatus() {
            return status;
        }

        public @Nonnull Date getCompleted() {
            return new Date(completed);
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
     * The time when the activity has stared.
     */
    private final long started;

    /**
     * All phases the activity has completed so far.
     */
    private final Map<Phase, PhaseExecution> progress;
    {
        progress = new LinkedHashMap(Phase.values().length);
        progress.put(Phase.PROVISIONING, null);
        progress.put(Phase.LAUNCHING, null);
        progress.put(Phase.OPERATING, null);
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
        this.started = System.currentTimeMillis();
        this.fingerprint = fingerprint;
    }

    public @Nonnull String getCloudName() {
        return cloudName;
    }

    public @Nonnull String getNodeName() {
        return nodeName;
    }

    public @Nonnull Date getStarted() {
        return new Date(started);
    }

    public @Nonnull Phase getPhase() {
        if (getStatus() == PhaseStatus.FAIL) return Phase.COMPLETED;

        synchronized (progress) {
            for (Phase phase : Phase.values()) {
                if (progress.get(phase) == null) return phase;
            }
        }

        assert false: "Unreachable";
        return null;
    }

    /*package for testing*/ boolean hasReached(@Nonnull Phase phase) {
        return getPhase().ordinal() >= phase.ordinal();
    }

    /**
     * Status of the activity as a whole.
     *
     * It is the works status of any of the phases, OK by default.
     */
    public @Nonnull PhaseStatus getStatus() {
        synchronized (progress) {
            PhaseStatus status = PhaseStatus.OK;
            for (PhaseExecution e : progress.values()) {
                if (e == null) continue;
                PhaseStatus s = e.getStatus();
                if (status.ordinal() < s.ordinal()) {
                    status = s;
                }
            }
            return status;
        }
    }

    public void complete(@Nonnull Phase phase, @Nonnull PhaseStatus status, @CheckForNull List<Attachment> attachments) {
        synchronized (progress) {
            checkNextPhaseIsValid(phase);
            progress.put(phase, new PhaseExecution(
                    status, attachments
            ));
        }
    }

    private void checkNextPhaseIsValid(@Nonnull Phase desired) {
        Phase current = getPhase();

        if (current == desired) return; // completing current

        if (desired.ordinal() < current.ordinal()) throw new IllegalArgumentException(
                "The phase " + desired + " was already completed. Now in " + current
        );

        if (desired == Phase.COMPLETED) throw new IllegalArgumentException(
                "The phase COMPLETED is never considered completed"
        );
    }

    /**
     * @return true if this activity tracks <tt>node</tt>'s progress.
     */
    public boolean isFor(NodeProvisioner.PlannedNode node) {
        return getFingerprint(node) == fingerprint;
    }

    @Override
    public String toString() {
        return String.format("Provisioning %s/%s is %s (status: %s)", cloudName, nodeName, getPhase(), getStatus());
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

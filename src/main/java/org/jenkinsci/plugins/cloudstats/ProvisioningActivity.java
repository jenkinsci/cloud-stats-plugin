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

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import hudson.Util;
import hudson.model.ModelObject;
import java.io.Serializable;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import javax.annotation.concurrent.GuardedBy;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Record of provisioning attempt lifecycle.
 *
 * @author ogondza.
 */
public final class ProvisioningActivity implements ModelObject, Comparable<ProvisioningActivity> {

    public static final String PREMATURE_COMPLETION_DETECTED =
            "Provisioning activity was completed before reaching OPERATING phase without reporting a problem";

    /** Progress of an activity. */
    public enum Phase {
        /** Acquiring the agent is in progress. */
        PROVISIONING,
        /** Agent is being launched, */
        LAUNCHING,
        /** The node is connected as Jenkins agent, possibly running builds. */
        OPERATING,
        /**
         * The resources (if any) as well as the computer should be gone.
         *
         * <p>This phase is never terminated.
         */
        COMPLETED
    }

    public enum Status {
        /** All went well. */
        OK,
        /**
         * There was a problem worth looking at, though provisioning managed to proceed.
         *
         * <p>The identification of the cause should be in attachment.
         */
        WARN,
        /**
         * The activity was aborted because of a problem so it never delivered functional agent.
         *
         * <p>The identification of the cause should be in attachment.
         */
        FAIL
    }

    /**
     * Activity identifier.
     *
     * <p>Used to a) uniquely identify the activity throughout the lifecycle and b) map
     * Computer/Node/PlannedNode to its cloud/template.
     */
    public static final class Id implements Serializable {
        private final @NonNull String cloudName;
        private final @CheckForNull String templateName;
        private final @CheckForNull String nodeName;

        /** Unique identifier of the activity. */
        private final int fingerprint;

        /**
         * @param cloudName Name of the cloud that initiated this activity.
         * @param templateName Name of the template that initiated this activity.
         * @param nodeName Name of the agent to be provisioned. Of the name of the agent is not
         *     known ahead, it can be <code>null</code> cloud stats plugin will update it once it
         *     will be known.
         */
        public Id(@NonNull String cloudName, @CheckForNull String templateName, @CheckForNull String nodeName) {
            this.cloudName = cloudName;
            this.templateName = templateName;
            this.nodeName = nodeName;
            this.fingerprint = System.identityHashCode(this) ^ (int) System.currentTimeMillis();
        }

        public Id(@NonNull String cloudName, @CheckForNull String templateName) {
            this(cloudName, templateName, null);
        }

        public Id(@NonNull String cloudName) {
            this(cloudName, null);
        }

        /**
         * Clone the Id with different name set.
         *
         * <p>The created Id is equal to this one.
         */
        public @NonNull Id named(@NonNull String name) {
            return new Id(this, name);
        }

        private Id(@NonNull Id id, @NonNull String name) {
            cloudName = id.cloudName;
            templateName = id.templateName;
            fingerprint = id.fingerprint;
            nodeName = name;
        }

        /** Name of the cloud that initiated this activity. */
        public @NonNull String getCloudName() {
            return cloudName;
        }

        /**
         * Name of the template used to provision this agent. <code>null</code> if no further
         * distinction is needed except for cloud name.
         */
        public @CheckForNull String getTemplateName() {
            return templateName;
        }

        /**
         * Name of the agent to be provisioned by this activity. <code>null</code> if not known
         * ahead.
         */
        public @CheckForNull String getNodeName() {
            return nodeName;
        }

        /** Unique fingerprint of this activity. */
        public int getFingerprint() {
            return fingerprint;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            Id id = (Id) o;
            return fingerprint == id.fingerprint;
        }

        @Override
        public int hashCode() {
            return Objects.hash(fingerprint);
        }

        @Override
        public String toString() {
            return String.format(
                    "ProvisioningActivity for %s/%s/%s (%d)", cloudName, templateName, nodeName, fingerprint);
        }
    }

    private final @NonNull Id id;

    @GuardedBy("id")
    private @Nullable String name;

    /** All phases the activity has started so far. */
    private final Map<Phase, PhaseExecution> progress;

    {
        progress = Collections.synchronizedMap(new LinkedHashMap<>(Phase.values().length));
        progress.put(Phase.PROVISIONING, null);
        progress.put(Phase.LAUNCHING, null);
        progress.put(Phase.OPERATING, null);
        progress.put(Phase.COMPLETED, null);
    }

    public ProvisioningActivity(@NonNull Id id) {
        this.id = id;
        enter(new PhaseExecution(Phase.PROVISIONING));

        // No need to synchronize since in constructor
        String name = id.nodeName;
        if (name == null) {
            name = id.templateName;
        }
        if (name == null) {
            name = id.cloudName;
        }
        this.name = name;
    }

    /*package for testing*/ ProvisioningActivity(@NonNull Id id, long started) {
        this.id = id;
        enter(new PhaseExecution(Phase.PROVISIONING, started));

        // No need to synchronize since in constructor
        String name = id.nodeName;
        if (name == null) {
            name = id.templateName;
        }
        if (name == null) {
            name = id.cloudName;
        }
        this.name = name;
    }

    public @NonNull Id getId() {
        return id;
    }

    public @NonNull Date getStarted() {
        synchronized (progress) {
            return progress.get(Phase.PROVISIONING).getStarted();
        }
    }

    public long getStartedTimestamp() {
        synchronized (progress) {
            return progress.get(Phase.PROVISIONING).getStartedTimestamp();
        }
    }

    /** {@link PhaseExecution} or null in case it is/was not executed. */
    public @CheckForNull PhaseExecution getPhaseExecution(@NonNull Phase phase) {
        synchronized (progress) {
            return progress.get(phase);
        }
    }

    /**
     * Get sorted mapping of all phase executions.
     *
     * @return Map of {@link Phase} and nullable {@link PhaseExecution}.
     */
    public @NonNull Map<Phase, PhaseExecution> getPhaseExecutions() {
        // progress is threadsafe here
        return Collections.unmodifiableMap(progress);
    }

    /** Get current {@link PhaseExecution}. */
    public @NonNull PhaseExecution getCurrentPhaseExecution() {
        synchronized (progress) {
            PhaseExecution ex = progress.get(Phase.COMPLETED);
            if (ex != null) {
                return ex;
            }

            ex = progress.get(Phase.OPERATING);
            if (ex != null) {
                return ex;
            }

            ex = progress.get(Phase.LAUNCHING);
            if (ex != null) {
                return ex;
            }

            ex = progress.get(Phase.PROVISIONING);
            if (ex != null) {
                return ex;
            }

            throw new IllegalStateException("Unknown provisioning state of " + getDisplayName());
        }
    }

    /** Get current {@link Phase}. */
    public @NonNull Phase getCurrentPhase() {
        return getCurrentPhaseExecution().getPhase();
    }

    /**
     * Status of the activity as a whole.
     *
     * <p>It is the works status of any of the phases, OK by default.
     */
    public @NonNull Status getStatus() {
        synchronized (progress) {
            Status status = Status.OK;
            for (PhaseExecution e : progress.values()) {
                if (e == null) {
                    continue;
                }
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
     * @throws IllegalArgumentException In case phases are entered repeatedly.
     */
    public void enter(@NonNull Phase phase) {
        synchronized (progress) {
            if (progress.get(phase) != null) {
                throw new IllegalStateException("The phase " + phase + " has already started");
            }

            final Phase currentPhase = getCurrentPhase();
            if (currentPhase.compareTo(phase) >= 0) {
                throw new IllegalStateException("The phase " + getCurrentPhase() + " has already started");
            }

            progress.put(phase, new PhaseExecution(phase));

            if (phase == Phase.COMPLETED && currentPhase != Phase.OPERATING && getStatus() == Status.OK) {
                PhaseExecutionAttachment attachment =
                        new PhaseExecutionAttachment(Status.WARN, PREMATURE_COMPLETION_DETECTED);
                progress.get(Phase.COMPLETED).attach(attachment);
            }
        }
    }

    /*package for testing*/ void enter(@NonNull PhaseExecution pe) {
        synchronized (progress) {
            progress.put(pe.getPhase(), pe);
        }
    }

    /**
     * Make sure the phase of this activity is entered.
     *
     * <p>Exposed for convenience of clients that can be invoked repeatedly and have no easier way
     * to tell if phase was entered already, such as launch listener.
     *
     * @return {@code true} is phase was entered.
     */
    public boolean enterIfNotAlready(@NonNull Phase phase) {
        synchronized (progress) {
            // Entered or skipped
            if (progress.get(phase) != null || getCurrentPhase().compareTo(phase) >= 0) {
                return false;
            }
            progress.put(phase, new PhaseExecution(phase));
        }
        return true;
    }

    /**
     * Only to be invoked from {@link CloudStatistics#attach(ProvisioningActivity,
     * ProvisioningActivity.Phase, PhaseExecutionAttachment)}.
     */
    @Restricted(NoExternalUse.class)
    /*package*/ void attach(Phase phase, PhaseExecutionAttachment attachment) {
        PhaseExecution execution = getPhaseExecution(phase);
        if (execution == null) {
            throw new IllegalArgumentException("Phase " + phase + " not entered yet");
        }
        execution.attach(attachment);
    }

    public @CheckForNull String getName() {
        synchronized (id) {
            return name;
        }
    }

    @Override
    public @NonNull String getDisplayName() {
        return "Activity " + getName();
    }

    /**
     * Attach the name once we know what it is.
     *
     * <p>Should never be invoked by other plugins as it is only needed when name of the node is
     * discovered for the first time and during rename.
     */
    @Restricted(NoExternalUse.class)
    /*package*/ void rename(@NonNull String newName) {
        if (Util.fixEmptyAndTrim(newName) == null) {
            throw new IllegalArgumentException("Unable to rename to empty string");
        }
        synchronized (id) {
            name = newName;
        }
    }

    @Restricted(NoExternalUse.class) // Stapler only
    public PhaseExecution getPhase(@NonNull String phaseName) {
        Phase phase = Phase.valueOf(phaseName);
        return getPhaseExecution(phase);
    }

    /**
     * Get duration of the activity phase.
     *
     * @return Positive integer in case the phase is completed, negative in case it is in progress
     */
    @Restricted(NoExternalUse.class) // Stapler only
    public long getDuration(@NonNull PhaseExecution execution) {
        Phase phase = execution.getPhase();
        if (phase == Phase.COMPLETED) {
            throw new IllegalArgumentException();
        }

        // Find any later nonnull execution
        PhaseExecution next = null;
        for (Phase p : Phase.values()) {
            if (p.ordinal() <= phase.ordinal()) {
                continue;
            }
            next = getPhaseExecution(p);
            if (next != null) {
                break;
            }
        }

        long started = execution.getStartedTimestamp();
        return next != null ? next.getStartedTimestamp() - started : -(System.currentTimeMillis() - started);
    }

    public boolean isFor(Id id) {
        return id.fingerprint == this.id.fingerprint;
    }

    @Override
    public int compareTo(@NonNull ProvisioningActivity o) {
        return Long.compare(o.getStartedTimestamp(), getStartedTimestamp());
    }

    @Override
    public String toString() {
        return id.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (o == null) {
            return false;
        }
        if (o == this) {
            return true;
        }
        if (!o.getClass().equals(getClass())) {
            return false;
        }
        ProvisioningActivity rhs = (ProvisioningActivity) o;
        return id.equals(rhs.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode() * 31;
    }
}

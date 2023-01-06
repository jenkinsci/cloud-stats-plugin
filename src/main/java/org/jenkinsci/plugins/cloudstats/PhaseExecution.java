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
import hudson.model.ModelObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.DoNotUse;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Phase execution record.
 *
 * <p>While the phases starts in declared order, they might not complete in that order. Much less
 * previous phase will be completed before next one starts.
 *
 * <p>There are several reasons for that: provisioning listener is called when the results are
 * picked up, the agent might have started launching in the meantime. There are plugins that in fact
 * enforce the launch to complete, before completing the {@link
 * hudson.slaves.NodeProvisioner.PlannedNode#future}. To avoid any problems this can cause, the
 * execution of phases is expected to occur in order, the execution will accept attachments
 * regardless if the next phase started or not. For the time tracking purposes, the phase is
 * considered completed as soon as the next phase begins. IOW, despite the fact the agent already
 * started launching, plugin can still append provisioning log.
 */
public final class PhaseExecution implements ModelObject {
    private final @NonNull List<PhaseExecutionAttachment> attachments =
            new CopyOnWriteArrayList<>();
    private final long started;
    private final @NonNull ProvisioningActivity.Phase phase;

    /*package*/ PhaseExecution(@NonNull ProvisioningActivity.Phase phase) {
        this(phase, System.currentTimeMillis());
    }

    /*package*/ PhaseExecution(@NonNull ProvisioningActivity.Phase phase, long started) {
        this.started = started;
        this.phase = phase;
    }

    public @NonNull List<PhaseExecutionAttachment> getAttachments() {
        return Collections.unmodifiableList(attachments);
    }

    public @NonNull <T extends PhaseExecutionAttachment> List<T> getAttachments(
            @NonNull Class<T> type) {
        List<T> out = new ArrayList<>();
        for (PhaseExecutionAttachment attachment : getAttachments()) {
            if (type.isInstance(attachment)) {
                out.add(type.cast(attachment));
            }
        }
        return out;
    }

    public @NonNull ProvisioningActivity.Status getStatus() {
        ProvisioningActivity.Status status = ProvisioningActivity.Status.OK;
        for (PhaseExecutionAttachment a : getAttachments()) {
            if (a.getStatus().ordinal() > status.ordinal()) {
                status = a.getStatus();
            }
        }
        return status;
    }

    public @NonNull Date getStarted() {
        return new Date(started);
    }

    public long getStartedTimestamp() {
        return started;
    }

    public @NonNull ProvisioningActivity.Phase getPhase() {
        return phase;
    }

    @Override
    public @NonNull String getDisplayName() {
        return phase.toString();
    }

    /**
     * Only to be invoked from {@link CloudStatistics#attach(ProvisioningActivity,
     * ProvisioningActivity.Phase, PhaseExecutionAttachment)}.
     */
    @Restricted(NoExternalUse.class)
    /*package*/ void attach(@NonNull PhaseExecutionAttachment phaseExecutionAttachment) {
        attachments.add(phaseExecutionAttachment);
    }

    @Restricted(NoExternalUse.class)
    public @CheckForNull String getUrlName(@NonNull PhaseExecutionAttachment attachment) {
        String urlName = attachment.getUrlName();
        if (urlName == null) return null;

        if (!attachments.contains(attachment))
            throw new IllegalArgumentException("Attachment not present in current execution");

        int cntr = 0;
        for (PhaseExecutionAttachment a : attachments) {
            if (a.equals(attachment)) break;

            if (urlName.equals(a.getUrlName())) {
                cntr++;
            }
        }

        if (cntr > 0) {
            return "attachment/" + urlName + ':' + cntr;
        } else {
            return "attachment/" + urlName;
        }
    }

    @Restricted(DoNotUse.class)
    public PhaseExecutionAttachment getAttachment(String urlName) {
        int n = 0;
        int i = urlName.lastIndexOf(':');
        if (i != -1) {
            try {
                n = Integer.parseInt(urlName.substring(i + 1));
                urlName = urlName.substring(0, i);
            } catch (NumberFormatException nan) {
                // It is not expected that ':' is found in the name, though proceed to fail later as
                // the name will not be found
            }
        }

        // Fail early
        if (n > attachments.size()) return null;

        int cntr = 0;
        for (PhaseExecutionAttachment a : attachments) {
            if (!urlName.equals(a.getUrlName())) continue;

            if (cntr == n) return a;

            cntr++;
        }

        return null;
    }
}

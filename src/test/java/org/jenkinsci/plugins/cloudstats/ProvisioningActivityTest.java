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
import org.junit.Test;
import org.jvnet.hudson.test.Issue;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.jenkinsci.plugins.cloudstats.ProvisioningActivity.Phase.*;
import static org.jenkinsci.plugins.cloudstats.ProvisioningActivity.Status.*;
import static org.junit.Assert.*;

public class ProvisioningActivityTest {

    private static final ProvisioningActivity.Id DUMMY_ID = new ProvisioningActivity.Id("Fake cloud");

    @Test
    public void id() {
        ProvisioningActivity.Id id = new ProvisioningActivity.Id("c", "t");
        assertEquals(id, id);
        assertEquals(id, id.named("n"));

        ProvisioningActivity.Id nid = new ProvisioningActivity.Id("c", "t");
        assertNotEquals(id, nid);
    }

    @Test
    public void phaseExecutionTrivia() {
        long before = System.currentTimeMillis();
        PhaseExecution pe = new PhaseExecution(PROVISIONING);
        long after = System.currentTimeMillis();

        long started = pe.getStarted().getTime();
        assertThat(before, Matchers.lessThanOrEqualTo(started));
        assertThat(after, Matchers.greaterThanOrEqualTo(started));
    }

    @Test
    public void trivia() {
        long before = System.currentTimeMillis();
        ProvisioningActivity activity = new ProvisioningActivity(DUMMY_ID);
        long after = System.currentTimeMillis();

        assertEquals(DUMMY_ID.getCloudName(), activity.getId().getCloudName());
        long started = activity.getStarted().getTime();
        assertThat(before, Matchers.lessThanOrEqualTo(started));
        assertThat(after, Matchers.greaterThanOrEqualTo(started));
    }

    @Test
    public void phasing() {
        ProvisioningActivity activity = new ProvisioningActivity(DUMMY_ID);
        assertEquals(PROVISIONING, activity.getCurrentPhase());
        assertNotNull(activity.getPhaseExecution(PROVISIONING));
        assertNull(activity.getPhaseExecution(LAUNCHING));

        activity.enter(LAUNCHING);
        assertEquals(LAUNCHING, activity.getCurrentPhase());
        assertNotNull(activity.getPhaseExecution(LAUNCHING));
        assertNull(activity.getPhaseExecution(OPERATING));

        activity.enter(OPERATING);
        assertEquals(OPERATING, activity.getCurrentPhase());
        assertNotNull(activity.getPhaseExecution(OPERATING));
        assertNull(activity.getPhaseExecution(COMPLETED));

        activity.enter(COMPLETED);
        assertEquals(COMPLETED, activity.getCurrentPhase());
        assertNotNull(activity.getPhaseExecution(COMPLETED));

        activity = new ProvisioningActivity(DUMMY_ID);

        try {
            activity.enter(PROVISIONING);
            fail("This phase was already entered");
        } catch (IllegalStateException ex) {
            // expected
        }
    }

    @Test
    public void attachmentsAndStates() {
        ProvisioningActivity activity = new ProvisioningActivity(DUMMY_ID);
        PhaseExecution pe = activity.getPhaseExecution(PROVISIONING);

        PhaseExecutionAttachment ok = new PhaseExecutionAttachment(OK, "It is all fine");
        activity.attach(PROVISIONING, ok);
        List<PhaseExecutionAttachment> attachments = pe.getAttachments();
        assertEquals(1, attachments.size());
        assertEquals(ok, attachments.get(0));
        assertEquals(OK, pe.getStatus());

        PhaseExecutionAttachment warn = new PhaseExecutionAttachment(WARN, "Check this out");
        activity.attach(PROVISIONING, warn);
        attachments = pe.getAttachments();
        assertEquals(2, attachments.size());
        assertEquals(warn, attachments.get(1));
        assertEquals(WARN, pe.getStatus());

        activity.enter(LAUNCHING);
        activity.enter(OPERATING);
        pe = activity.getPhaseExecution(OPERATING);
        PhaseExecutionAttachment fail = new PhaseExecutionAttachment(FAIL, "Broken");
        activity.attach(OPERATING, fail);
        attachments = pe.getAttachments();
        assertEquals(1, attachments.size());
        assertEquals(fail, attachments.get(0));
        assertEquals(FAIL, pe.getStatus());
    }

    @Test
    public void duration() {
        // Completed
        ProvisioningActivity pa = new ProvisioningActivity(new ProvisioningActivity.Id("cld"));
        PhaseExecution provisioning = enter(pa, PROVISIONING, 10);
        PhaseExecution launching = enter(pa, LAUNCHING, 30);
        PhaseExecution operating = enter(pa, OPERATING, 40);
        PhaseExecution completed = enter(pa, COMPLETED, 100);

        assertEquals(20, pa.getDuration(provisioning));
        assertEquals(10, pa.getDuration(launching));
        assertEquals(60, pa.getDuration(operating));
        try {
            pa.getDuration(completed);
            fail();
        } catch (IllegalArgumentException ignored) {}

        // In progress
        pa = new ProvisioningActivity(new ProvisioningActivity.Id("cld"));
        provisioning = enter(pa, PROVISIONING, 10);
        launching = enter(pa, LAUNCHING, 30);

        assertEquals(20, pa.getDuration(provisioning));
        assertEquals(-(System.currentTimeMillis() - 30), pa.getDuration(launching));
        try {
            pa.getDuration(pa.getPhaseExecution(COMPLETED));
            fail();
        } catch (NullPointerException ignored) {}

        // Completed prematurely
        pa = new ProvisioningActivity(new ProvisioningActivity.Id("cld"));
        provisioning = enter(pa, PROVISIONING, 10);
        enter(pa, COMPLETED, 30);

        assertEquals(20, pa.getDuration(provisioning));
        try {
            pa.getDuration(pa.getPhaseExecution(LAUNCHING));
            fail();
        } catch (NullPointerException ignored) {}
        try {
            pa.getDuration(pa.getPhaseExecution(OPERATING));
            fail();
        } catch (NullPointerException ignored) {}
    }

    @Test @Issue("https://github.com/jenkinsci/cloud-stats-plugin/issues/4")
    public void enteringSkippedPhaseShouldFail() throws Exception {
        ProvisioningActivity pa = new ProvisioningActivity(new ProvisioningActivity.Id("cld"));
        try {
            pa.enter(PROVISIONING);
            fail("Entering phase entered already");
        } catch (IllegalStateException ex) {
            assertEquals("The phase PROVISIONING has already started", ex.getMessage());
        }
        assertFalse("Entering phase entered already", pa.enterIfNotAlready(PROVISIONING));

        pa.enter(COMPLETED);

        try {
            pa.enter(LAUNCHING);
            fail("Entering phase that was skipped should fail");
        } catch (IllegalStateException ex) {
            assertEquals("The phase COMPLETED has already started", ex.getMessage());
        }
        assertFalse("Entering phase entered already", pa.enterIfNotAlready(LAUNCHING));

        try {
            pa.enter(OPERATING);
            fail("Entering phase that was skipped should fail");
        } catch (IllegalStateException ex) {
            assertEquals("The phase COMPLETED has already started", ex.getMessage());
        }
        assertFalse("Entering phase entered already", pa.enterIfNotAlready(OPERATING));
    }

    @Test
    public void enteringCompletedPhaseWithoutOperationShouldBeWarningState() {
        ProvisioningActivity pa = new ProvisioningActivity(new ProvisioningActivity.Id("cld"));
        final PhaseExecutionAttachment failedAttachment = new PhaseExecutionAttachment(
                ProvisioningActivity.Status.FAIL,
                "Failed intentionally"
        );

        pa.enter(COMPLETED);
        assertEquals(WARN, pa.getPhaseExecution(COMPLETED).getStatus());
        assertThat(pa.getPhaseExecution(COMPLETED).getAttachments(), hasSize(1));
        assertThat(pa.getPhaseExecution(COMPLETED).getAttachments().get(0).getTitle(),
                equalTo(ProvisioningActivity.PREMATURE_COMPLETION_DETECTED));

        pa = new ProvisioningActivity(new ProvisioningActivity.Id("cld"));
        pa.getPhaseExecution(PROVISIONING).attach(failedAttachment);
        pa.enter(COMPLETED);
        assertEquals(OK, pa.getPhaseExecution(COMPLETED).getStatus());
        assertThat(pa.getPhaseExecution(COMPLETED).getAttachments(), hasSize(0));

        pa = new ProvisioningActivity(new ProvisioningActivity.Id("cld1"));
        pa.enter(LAUNCHING);
        pa.enter(COMPLETED);
        assertEquals(WARN, pa.getPhaseExecution(COMPLETED).getStatus());
        assertThat(pa.getPhaseExecution(COMPLETED).getAttachments(), hasSize(1));
        assertThat(pa.getPhaseExecution(COMPLETED).getAttachments().get(0).getTitle(),
                equalTo(ProvisioningActivity.PREMATURE_COMPLETION_DETECTED));

        pa = new ProvisioningActivity(new ProvisioningActivity.Id("cld1"));
        pa.enter(LAUNCHING);
        pa.getPhaseExecution(LAUNCHING).attach(failedAttachment);
        pa.enter(COMPLETED);
        assertEquals(OK, pa.getPhaseExecution(COMPLETED).getStatus());
        assertThat(pa.getPhaseExecution(COMPLETED).getAttachments(), hasSize(0));

        pa = new ProvisioningActivity(new ProvisioningActivity.Id("cld2"));
        pa.enter(LAUNCHING);
        pa.enter(OPERATING);
        pa.enter(COMPLETED);
        assertEquals(OK, pa.getPhaseExecution(COMPLETED).getStatus());
        assertThat(pa.getPhaseExecution(COMPLETED).getAttachments(), hasSize(0));
    }

    private PhaseExecution enter(ProvisioningActivity activity, ProvisioningActivity.Phase phase, long started) {
        PhaseExecution execution = new PhaseExecution(phase, started);
        activity.enter(execution);
        return execution;
    }
}

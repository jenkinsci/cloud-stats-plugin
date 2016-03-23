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

import java.util.Collections;
import java.util.Date;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.jenkinsci.plugins.cloudstats.ProvisioningActivity.Phase.*;
import static org.jenkinsci.plugins.cloudstats.ProvisioningActivity.PhaseStatus.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * @author ogondza.
 */
public class ProvisioningActivityTest {

    public static final List<ProvisioningActivity.Attachment> NO_ATTACHMENTS = Collections.<ProvisioningActivity.Attachment>emptyList();

    @Test
    public void trivia() {
        long before = System.currentTimeMillis();
        ProvisioningActivity activity = new ProvisioningActivity("cloud-name", "slave-name", 0);
        long after = System.currentTimeMillis();

        assertEquals("cloud-name", activity.getCloudName());
        assertEquals("slave-name", activity.getNodeName());
        long started = activity.getStarted().getTime();
        assertThat(before, Matchers.lessThanOrEqualTo(started));
        assertThat(after, Matchers.greaterThanOrEqualTo(started));
    }

    @Test
    public void phasing() {
        ProvisioningActivity activity = new ProvisioningActivity("cloud-name", "slave-name", 0);
        assertEquals(PROVISIONING, activity.getPhase());

        activity.complete(PROVISIONING, OK, NO_ATTACHMENTS);
        assertEquals(LAUNCHING, activity.getPhase());

        activity.complete(LAUNCHING, OK, NO_ATTACHMENTS);
        assertEquals(OPERATING, activity.getPhase());

        activity.complete(OPERATING, OK, NO_ATTACHMENTS);
        assertEquals(COMPLETED, activity.getPhase());

        activity = new ProvisioningActivity("cloud-name", "slave-name", 0);
        activity.complete(PROVISIONING, OK, NO_ATTACHMENTS);

        try {
            activity.complete(PROVISIONING, OK, NO_ATTACHMENTS);
            fail("This phase was completed entered");
        } catch (IllegalArgumentException ex) {
            // expected
        }

        // It is ok to skip phases - there is no use-case for that but plugin should be able o deal with the fact that some event just have not arrived.
        activity.complete(OPERATING, OK, NO_ATTACHMENTS);

        try {
            activity.complete(PROVISIONING, OK, NO_ATTACHMENTS);
            fail("Unable to go back in phases");
        } catch (IllegalArgumentException ex) {
            // expected
        }

        try {
            activity.complete(COMPLETED, OK, NO_ATTACHMENTS);
            fail("Unable to complete COMPLETED phase");
        } catch (IllegalArgumentException ex) {
            // expected
        }
    }

    @Test
    public void states() {
        ProvisioningActivity activity = new ProvisioningActivity("cloud-name", "slave-name", 0);
        assertEquals(OK, activity.getStatus());

        activity.complete(PROVISIONING, WARN, NO_ATTACHMENTS);
        assertEquals(WARN, activity.getStatus());

        activity.complete(LAUNCHING, OK, NO_ATTACHMENTS);
        assertEquals(WARN, activity.getStatus()); // Status is not going to get any better

        activity.complete(OPERATING, FAIL, NO_ATTACHMENTS);
        assertEquals(FAIL, activity.getStatus());

        activity = new ProvisioningActivity("cloud-name", "slave-name", 1);
        activity.complete(PROVISIONING, FAIL, NO_ATTACHMENTS);
        assertEquals(COMPLETED, activity.getPhase()); // Activity is completed once failed
    }
}

/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2017 Red Hat, Inc.
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

import org.junit.Test;

import java.util.Arrays;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/**
 * @author ogondza.
 */
public class HealthTest {

    // Use since moment to base ages on to avoid undesired millisecond differences cased by the speed of test execution
    public final long NOW = System.currentTimeMillis();

    public static final ProvisioningActivity.Id SUCCESS_ID = new ProvisioningActivity.Id("Success");
    public static final ProvisioningActivity.Id FAILURE_ID = new ProvisioningActivity.Id("Failure");

    public static final ProvisioningActivity SUCCESS = new ProvisioningActivity(SUCCESS_ID);
    public static final ProvisioningActivity FAILURE = new ProvisioningActivity(FAILURE_ID);

    public static final PhaseExecutionAttachment FAILED_ATTACHMENT = new PhaseExecutionAttachment(ProvisioningActivity.Status.FAIL, "It failed alright");

    static {
        FAILURE.attach(ProvisioningActivity.Phase.PROVISIONING, FAILED_ATTACHMENT);
    }

    @Test
    public void overall() throws Exception {
        Health.Report actual = health().getOverall();
        assertEquals(new Health.Report(Float.NaN), actual);
        assertEquals("?", actual.toString());

        actual = health(SUCCESS).getOverall();
        assertEquals(new Health.Report(100), actual);
        assertEquals("100%", actual.toString());

        actual = health(FAILURE).getOverall();
        assertEquals(new Health.Report(0), actual);
        assertEquals("0%", actual.toString());

        assertEquals("66.7%", health(SUCCESS, FAILURE, SUCCESS).getOverall().toString());
    }

    @Test
    public void currentTrivial() throws Exception {
        Health.Report actual = health().getCurrent();
        assertEquals(new Health.Report(Float.NaN), actual);
        assertEquals("?", actual.toString());

        actual = health(success(0)).getCurrent();
        assertFalse(Float.isNaN(actual.getPercentage()));
        assertThat(actual.getPercentage(), greaterThanOrEqualTo(99f));

        actual = health(failure(0)).getCurrent();
        assertFalse(Float.isNaN(actual.getPercentage()));
        assertThat(actual.getPercentage(), lessThanOrEqualTo(1f));

        actual = health(success(60)).getCurrent();
        assertFalse(Float.isNaN(actual.getPercentage()));
        assertThat(actual.getPercentage(), greaterThanOrEqualTo(99f));

        actual = health(failure(60)).getCurrent();
        assertFalse(Float.isNaN(actual.getPercentage()));
        assertThat(actual.getPercentage(), lessThanOrEqualTo(1f));
    }

    @Test
    public void currentSameAge() throws Exception {
        Health.Report actual = health(failure(0), success(0)).getCurrent();
        assertFalse(Float.isNaN(actual.getPercentage()));
        assertThat(actual.getPercentage(), equalTo(50F));

        actual = health(failure(10), success(10), success(50), failure(50)).getCurrent();
        assertFalse(Float.isNaN(actual.getPercentage()));
        assertThat(actual.getPercentage(), equalTo(50F));

        assertThat(
                health(success(50), failure(10), failure(50), success(10)).getCurrent().getPercentage(),
                equalTo(actual.getPercentage())
        );
    }

    @Test
    public void currentDifferentAge() throws Exception {
        // Current implementation considers same sequences equally successful regardless of the latest sample age
        assertThat(health(success(1)).getCurrent(), equalTo(health(success(0)).getCurrent()));
        assertEquals(
                health(failure(1), failure(11)).getCurrent(),
                health(failure(100), failure(110)).getCurrent()
        );

        assertThat(
                health(success(0), failure(1)).getCurrent(), lessThan(
                health(success(0), failure(2)).getCurrent()
        ));

        assertThat(
                health(success(0), failure(1)).getCurrent(), lessThan(
                health(success(1), failure(3)).getCurrent()
        ));

        assertThat(
                health(success(0), success(1), failure(2)).getCurrent(), greaterThan(
                health(success(0), failure(1), success(2)).getCurrent()
        ));
    }

    private Health health(ProvisioningActivity... pas) {
        return new Health(Arrays.asList(pas));
    }

    private ProvisioningActivity success(int age){
        return new ProvisioningActivity(SUCCESS_ID, NOW - age * 60 * 1000);
    }

    private ProvisioningActivity failure(int age){
        ProvisioningActivity pa = new ProvisioningActivity(FAILURE_ID, NOW - age * 60 * 1000);
        pa.attach(ProvisioningActivity.Phase.PROVISIONING, FAILED_ATTACHMENT);
        return pa;
    }
}

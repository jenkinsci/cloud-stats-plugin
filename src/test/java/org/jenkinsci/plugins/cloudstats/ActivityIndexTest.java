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

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;

/**
 * @author ogondza.
 */
public class ActivityIndexTest {

    @Test
    public void empty() {
        ActivityIndex index = new ActivityIndex(Collections.emptyList());
        assertThat(index.byCloud().size(), equalTo(0));
        assertThat(index.byTemplate().size(), equalTo(0));
        assertThat(index.forCloud("asdf"), Matchers.emptyIterable());
        assertThat(index.forTemplate("as", "df"), Matchers.emptyIterable());
    }

    @Test
    public void content() {
        ActivityIndex index = new ActivityIndex(Arrays.asList(
                new ProvisioningActivity(new ProvisioningActivity.Id("A", "a")),
                new ProvisioningActivity(new ProvisioningActivity.Id("A", "b", "x")),
                new ProvisioningActivity(new ProvisioningActivity.Id("B", "a", "xxxx")),
                new ProvisioningActivity(new ProvisioningActivity.Id("C", null, "c")),
                new ProvisioningActivity(new ProvisioningActivity.Id("C", null, "cc"))
        ));
        assertThat(index.byCloud().keySet(), containsInAnyOrder("A", "B", "C"));
        assertThat(index.byCloud().get("A").size(), equalTo(2));
        assertThat(index.byCloud().get("B").size(), equalTo(1));
        assertThat(index.byCloud().get("C").size(), equalTo(2));

        assertThat(index.byTemplate().keySet(), containsInAnyOrder("A", "B", "C"));
        assertThat(index.byTemplate().get("A").get("a").size(), equalTo(1));
        assertThat(index.byTemplate().get("C").get(null).size(), equalTo(2));

        assertThat(index.forCloud("A").size(), equalTo(2));
        assertThat(index.forTemplate("A", "a").size(), equalTo(1));
        assertThat(index.forTemplate("C", null).size(), equalTo(2));
        assertThat(index.forCloud("X").size(), equalTo(0));
        assertThat(index.forTemplate("X", "a").size(), equalTo(0));
        assertThat(index.forTemplate("A", "x").size(), equalTo(0));
    }

    @Test
    public void activitiesNotCompletedOrOperatingAreIgnoredForHealth() {
        ActivityIndex index = new ActivityIndex(Arrays.asList(
                enter(new ProvisioningActivity(new ProvisioningActivity.Id("P", "p")), ProvisioningActivity.Phase.PROVISIONING),
                enter(new ProvisioningActivity(new ProvisioningActivity.Id("L", "l")), ProvisioningActivity.Phase.LAUNCHING),
                enter(new ProvisioningActivity(new ProvisioningActivity.Id("O", "o")), ProvisioningActivity.Phase.OPERATING),
                enter(new ProvisioningActivity(new ProvisioningActivity.Id("C", "c")), ProvisioningActivity.Phase.COMPLETED)
        ));

        Map<String, Health> hc = index.healthByCloud();
        assertThat(hc.get("P").getOverall().getPercentage(), equalTo(Float.NaN));
        assertThat(index.cloudHealth("P").getOverall().getPercentage(), equalTo(Float.NaN));
        assertThat(hc.get("L").getOverall().getPercentage(), equalTo(Float.NaN));
        assertThat(index.cloudHealth("L").getOverall().getPercentage(), equalTo(Float.NaN));
        assertThat(hc.get("O").getOverall().getPercentage(), equalTo(100F));
        assertThat(index.cloudHealth("O").getOverall().getPercentage(), equalTo(100F));
        assertThat(hc.get("C").getOverall().getPercentage(), equalTo(100F));
        assertThat(index.cloudHealth("C").getOverall().getPercentage(), equalTo(100F));

        Map<String, Map<String, Health>> ht = index.healthByTemplate();
        assertThat(ht.get("P").get("p").getOverall().getPercentage(), equalTo(Float.NaN));
        assertThat(index.templateHealth("P", "p").getOverall().getPercentage(), equalTo(Float.NaN));
        assertThat(ht.get("L").get("l").getOverall().getPercentage(), equalTo(Float.NaN));
        assertThat(index.templateHealth("L", "l").getOverall().getPercentage(), equalTo(Float.NaN));
        assertThat(ht.get("O").get("o").getOverall().getPercentage(), equalTo(100F));
        assertThat(index.templateHealth("O", "o").getOverall().getPercentage(), equalTo(100F));
        assertThat(ht.get("C").get("c").getOverall().getPercentage(), equalTo(100F));
        assertThat(index.templateHealth("C", "c").getOverall().getPercentage(), equalTo(100F));
    }

    private ProvisioningActivity enter(ProvisioningActivity provisioningActivity, ProvisioningActivity.Phase p) {
        provisioningActivity.enterIfNotAlready(p);
        return provisioningActivity;
    }
}

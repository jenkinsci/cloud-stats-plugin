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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;

/**
 * @author ogondza.
 */
public class ActivityIndexTest {

    @Test
    public void empty() {
        ActivityIndex index = new ActivityIndex(Collections.<ProvisioningActivity>emptyList());
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
    public void matrices() {

    }
}

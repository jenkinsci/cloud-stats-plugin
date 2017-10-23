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

import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.recipes.PresetData;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

public class StatsWidgetTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Test
    public void showWhenCloudsConfigured() throws Exception {
        JenkinsRule.WebClient webClient = j.createWebClient();
        String content = webClient.goTo("").getWebResponse().getContentAsString();
        assertThat(content, not(containsString("Cloud Statistics")));
        assertThat(content, not(containsString("#cloudstats")));

        j.jenkins.clouds.add(new TestCloud("asdf"));
        content = webClient.goTo("").getWebResponse().getContentAsString();
        assertThat(content, containsString("Cloud Statistics"));
        assertThat(content, containsString("#cloudstats"));
    }

    @Test @PresetData(PresetData.DataSet.ANONYMOUS_READONLY)
    public void doNotShowWhenNotAuthenticated() throws Exception {
        j.jenkins.clouds.add(new TestCloud("asdf"));
        JenkinsRule.WebClient webClient = j.createWebClient();
        String content = webClient.goTo("").getWebResponse().getContentAsString();
        assertThat(content, not(containsString("Cloud Statistics")));
        assertThat(content, not(containsString("#cloudstats")));
    }
}

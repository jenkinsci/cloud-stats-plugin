package org.jenkinsci.plugins.cloudstats;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

import hudson.Extension;
import hudson.model.Descriptor;
import hudson.model.Label;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import java.util.Collection;
import java.util.Collections;

/**
 * @author ogondza.
 */
public class CloudStatisticsTest {

    public @Rule JenkinsRule j = new JenkinsRule();

    @Test
    public void showOnlyIfThereAreClouds() throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient();
        wc.getOptions().setThrowExceptionOnFailingStatusCode(false);

        assertThat(wc.goTo("manage").asText(), not(containsString("Cloud Statistics")));
        assertThat(wc.goTo("cloud-stats/").asText(), containsString("No clouds configured"));

        j.jenkins.clouds.add(new TestCloud("Dummy"));

        assertThat(wc.goTo("manage").asText(), containsString("Cloud Statistics"));
        String actual = wc.goTo("cloud-stats/").asText();
        assertThat(actual, not(containsString("No clouds configured")));
        assertThat(actual, containsString("Dummy Test Cloud"));
    }

    public static final class TestCloud extends Cloud {

        protected TestCloud(String name) {
            super(name);
        }

        @Override
        public Collection<NodeProvisioner.PlannedNode> provision(Label label, int excessWorkload) {
            return Collections.emptyList();
        }

        @Override
        public boolean canProvision(Label label) {
            return false;
        }

        @Extension
        public static final class Desc extends Descriptor<Cloud> {
            @Override
            public String getDisplayName() {
                return "Test Cloud";
            }
        }
    }
}

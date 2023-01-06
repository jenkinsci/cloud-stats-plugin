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

import hudson.Extension;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Label;
import hudson.model.Node;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import org.jvnet.hudson.test.JenkinsRule;

/**
 * @author ogondza.
 */
public final class TestCloud extends Cloud {
    private final transient Launcher provision;
    private final transient JenkinsRule j;
    private final transient AtomicInteger seq = new AtomicInteger();

    public TestCloud(String name) {
        super(name);
        provision = null;
        j = null;
    }

    public TestCloud(final String name, JenkinsRule j, final Launcher provision) {
        super(name);
        this.j = j;
        this.provision = provision;
    }

    @Override
    public Collection<NodeProvisioner.PlannedNode> provision(Label label, int excessWorkload) {
        assert provision != null;

        provision.j = j;
        int i = seq.getAndIncrement();
        provision.id = new ProvisioningActivity.Id(name, null, name + "-agent-" + i);

        return Collections.singletonList(
                new TrackedPlannedNode(
                        provision.id, 1, Computer.threadPoolForRemoting.submit(provision)));
    }

    @Override
    public boolean canProvision(Label label) {
        return provision != null;
    }

    @Extension
    public static final class Desc extends Descriptor<Cloud> {
        @Override
        public String getDisplayName() {
            return "Test Cloud";
        }
    }

    public abstract static class Launcher implements Callable<Node> {
        protected transient JenkinsRule j;
        protected ProvisioningActivity.Id id;
    }

    public static class ThrowException extends Launcher {
        public static final NullPointerException EXCEPTION = new NullPointerException("Whoops");

        @Override
        public Node call() throws Exception {
            throw EXCEPTION;
        }
    }
}

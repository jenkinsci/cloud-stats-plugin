/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2021 Red Hat, Inc.
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

import hudson.EnvVars;
import hudson.model.Descriptor;
import hudson.model.TaskListener;
import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.AbstractCloudSlave;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.NodeProperty;
import hudson.slaves.RetentionStrategy;
import org.jvnet.hudson.test.JenkinsRule;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.Collections;
import java.util.List;

/**
 * @author ogondza.
 */
final class TrackedAgent extends AbstractCloudSlave implements TrackedItem {
    private final ProvisioningActivity.Id id;

    public TrackedAgent(ProvisioningActivity.Id id, JenkinsRule j, String name) throws Exception {
        super(name == null ? id.getNodeName(): name, "dummy", j.createTmpDir().getPath(), "1", Mode.NORMAL, "label", j.createComputerLauncher(new EnvVars()), RetentionStrategy.NOOP, Collections.<NodeProperty<?>>emptyList());
        this.id = id;
    }

    public TrackedAgent(
            String name, String nodeDescription, String remoteFS, String numExecutors, Mode mode, String labelString, ComputerLauncher launcher, RetentionStrategy retentionStrategy, List<? extends NodeProperty<?>> nodeProperties, ProvisioningActivity.Id id
    ) throws IOException, Descriptor.FormException {
        super(name, nodeDescription, remoteFS, numExecutors, mode, labelString, launcher, retentionStrategy, nodeProperties);
        this.id = id;
    }

    @Nonnull
    public static TrackedAgent create(ProvisioningActivity.Id id, JenkinsRule j) throws Exception {
        TrackedAgent slave = new TrackedAgent(id, j, null);
        j.jenkins.addNode(slave);
        return slave;
    }

    @Override
    public AbstractCloudComputer createComputer() {
        return new TrackedComputer(this, id);
    }

    @Override
    protected void _terminate(TaskListener listener) {

    }

    @Override
    public ProvisioningActivity.Id getId() {
        return id;
    }
}

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

import hudson.model.Label;
import hudson.model.Node;
import hudson.slaves.Cloud;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.concurrent.Future;

import static hudson.slaves.NodeProvisioner.*;

/**
 * {@link PlannedNode} that keeps track of owning cloud and fingerprint to track the activity.
 *
 * {@link Cloud#provision(Label, int)} needs to return this subtype to have the provisioning activity tracked.
 *
 * @author ogondza.
 */
public class TrackedPlannedNode extends PlannedNode implements TrackedItem {

    private final @Nonnull ProvisioningActivity.Id id;

    public TrackedPlannedNode(@Nonnull String cloudName, @Nullable String templateName, @Nonnull String displayName, int numExecutors, @Nonnull Future<Node> future) {
        super(displayName, future, numExecutors);

        this.id = new ProvisioningActivity.Id(cloudName, templateName, this);
    }

    public TrackedPlannedNode(@Nonnull String cloudName, @Nullable String displayName, int numExecutors, @Nonnull Future<Node> future) {
        super(displayName, future, numExecutors);

        this.id = new ProvisioningActivity.Id(cloudName, null, this);
    }

    @Nonnull public ProvisioningActivity.Id getId() {
        return id;
    }
}

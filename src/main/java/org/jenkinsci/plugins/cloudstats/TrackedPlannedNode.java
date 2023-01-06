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

import static hudson.slaves.NodeProvisioner.*;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.Label;
import hudson.model.Node;
import hudson.slaves.Cloud;
import java.util.concurrent.Future;

/**
 * Convenient subclass of {@link PlannedNode} to simplify tracking the activity.
 *
 * <p>{@link Cloud#provision(Label, int)} needs to return {@link PlannedNode} implementing {@link
 * TrackedItem} to have the provisioning activity tracked.
 *
 * @author ogondza.
 * @see TrackedItem
 */
public class TrackedPlannedNode extends PlannedNode implements TrackedItem {

    private final @NonNull ProvisioningActivity.Id id;

    public TrackedPlannedNode(
            @NonNull ProvisioningActivity.Id id, int numExecutors, @NonNull Future<Node> future) {
        super(extractTemporaryName(id), future, numExecutors);

        this.id = id;
    }

    // Try to use the most specific name, fallback to less specific if not set
    private static String extractTemporaryName(ProvisioningActivity.Id id) {
        String name = id.getNodeName();
        if (name == null) {
            name = id.getTemplateName();
            if (name == null) {
                name = id.getCloudName();
            }
        }
        return name;
    }

    public @NonNull ProvisioningActivity.Id getId() {
        return id;
    }
}

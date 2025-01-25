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

import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Interface to be implemented by plugins to have their provisioning activities tracked.
 *
 * <p>It is necessary to implement this by {@link hudson.slaves.NodeProvisioner.PlannedNode}, {@link
 * hudson.model.Node} and {@link hudson.model.Computer}.
 *
 * @author ogondza.
 * @see TrackedPlannedNode
 */
public interface TrackedItem {
    /**
     * Get unique identifier of the provisioning item.
     *
     * @return The identifier. Can be null in case the item that is generally tracked opts-out of
     *     tracking. Primary use is to allow null for items that serialized before plugin was
     *     integrated and have no id to provide. Implementations can use this disable tracking
     *     selectively on per-item basis.
     */
    @Nullable
    ProvisioningActivity.Id getId();
}

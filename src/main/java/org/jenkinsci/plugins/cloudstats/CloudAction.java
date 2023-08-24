/*
 * The MIT License
 *
 * Copyright (c) Red Hat, Inc.
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

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Action;
import hudson.slaves.Cloud;
import java.util.Collection;
import java.util.Collections;
import jenkins.model.TransientActionFactory;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Attach statistics info to cloud page.
 *
 * @author ogondza.
 */
@Restricted(NoExternalUse.class)
public class CloudAction implements Action {

    public final Cloud cloud;

    public CloudAction(Cloud cloud) {
        this.cloud = cloud;
    }

    public CloudStatistics getCloudStatistics() {
        return CloudStatistics.get();
    }

    @CheckForNull
    @Override
    public String getIconFileName() {
        return "symbol-analytics";
    }

    @CheckForNull
    @Override
    public String getDisplayName() {
        return "Cloud Statistics";
    }

    @CheckForNull
    @Override
    public String getUrlName() {
        return "/cloud-stats/";
    }

    @Extension
    public static final class CloudActionFactory extends TransientActionFactory<Cloud> {
        @Override
        public Class<Cloud> type() {
            return Cloud.class;
        }

        @NonNull
        @Override
        public Collection<? extends Action> createFor(@NonNull Cloud cloud) {
            return Collections.singletonList(new CloudAction(cloud));
        }
    }
}

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

import static org.jenkinsci.plugins.cloudstats.ProvisioningActivity.Phase.COMPLETED;
import static org.jenkinsci.plugins.cloudstats.ProvisioningActivity.Phase.OPERATING;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Indexed view of statistics snapshot.
 *
 * <p>Provides access to activities indexed by cloud, template, etc.
 *
 * @author ogondza.
 * @see CloudStatistics#getIndex()
 */
public final class ActivityIndex {
    public static final List<ProvisioningActivity> EMPTY = Collections.emptyList();
    private final @NonNull Map<String, Collection<ProvisioningActivity>> byCloud;
    private final @NonNull Map<String, Map<String, Collection<ProvisioningActivity>>> byTemplate;

    public ActivityIndex(@NonNull List<ProvisioningActivity> activities) {
        Map<String, Collection<ProvisioningActivity>> byCloud = new HashMap<>();
        Map<String, Map<String, Collection<ProvisioningActivity>>> byTemplate = new HashMap<>();
        for (ProvisioningActivity a : activities) {
            ProvisioningActivity.Id id = a.getId();
            String cloudName = id.getCloudName();
            String templateName = id.getTemplateName();
            Collection<ProvisioningActivity> cld = byCloud.get(cloudName);
            if (cld == null) {
                cld = new ArrayList<>();
                byCloud.put(cloudName, cld);
            }
            cld.add(a);

            Map<String, Collection<ProvisioningActivity>> cl = byTemplate.get(cloudName);
            if (cl == null) {
                cl = new HashMap<>();
                byTemplate.put(cloudName, cl);
            }
            Collection<ProvisioningActivity> tmpl = cl.get(templateName);
            if (tmpl == null) {
                tmpl = new ArrayList<>();
                cl.put(templateName, tmpl);
            }
            tmpl.add(a);
        }
        this.byCloud = Collections.unmodifiableMap(byCloud);
        this.byTemplate = Collections.unmodifiableMap(byTemplate);
    }

    /**
     * Get activities sorted by owning cloud.
     *
     * @return Map where cloud names are the keys.
     */
    public @NonNull Map<String, Collection<ProvisioningActivity>> byCloud() {
        return byCloud;
    }

    /**
     * Get activities sorted by owning cloud and template
     *
     * @return Map where cloud names are the keys, values are maps where keys are template names.
     *     Note that template name can be null in case the cloud is not using templates. It should
     *     be the only key in such a case.
     */
    public @NonNull Map<String, Map<String, Collection<ProvisioningActivity>>> byTemplate() {
        return byTemplate;
    }

    /** Get activities owned by particular cloud. */
    public @NonNull Collection<ProvisioningActivity> forCloud(@NonNull String name) {
        Collection<ProvisioningActivity> ret = byCloud.get(name);
        return ret == null ? EMPTY : ret;
    }

    /** Get activities owned by particular cloud and template. */
    public @NonNull Collection<ProvisioningActivity> forTemplate(
            @NonNull String cloud, @Nullable String template) {
        Map<String, Collection<ProvisioningActivity>> forCloud = byTemplate.get(cloud);
        if (forCloud == null) return EMPTY;
        Collection<ProvisioningActivity> ret = forCloud.get(template);
        return ret == null ? EMPTY : ret;
    }

    /** Get map of cloud names to their health metrics. */
    public @NonNull Map<String, Health> healthByCloud() {
        HashMap<String, Health> ret = new HashMap<>(byCloud.size());
        for (Map.Entry<String, Collection<ProvisioningActivity>> entry : byCloud.entrySet()) {
            ret.put(entry.getKey(), new Health(filterForHealth(entry.getValue())));
        }

        return ret;
    }

    public @NonNull Map<String, Map<String, Health>> healthByTemplate() {
        HashMap<String, Map<String, Health>> ret = new HashMap<>(byTemplate.size());
        for (Map.Entry<String, Map<String, Collection<ProvisioningActivity>>> entry :
                byTemplate.entrySet()) {
            HashMap<String, Health> tmpltret = new HashMap<>(entry.getValue().size());
            for (Map.Entry<String, Collection<ProvisioningActivity>> template :
                    entry.getValue().entrySet()) {
                tmpltret.put(template.getKey(), new Health(filterForHealth(template.getValue())));
            }
            ret.put(entry.getKey(), tmpltret);
        }

        return ret;
    }

    public @NonNull Health cloudHealth(@NonNull String cloud) {
        return new Health(filterForHealth(forCloud(cloud)));
    }

    public @NonNull Health templateHealth(@NonNull String cloud, @Nullable String template) {
        return new Health(filterForHealth(forTemplate(cloud, template)));
    }

    private Collection<ProvisioningActivity> filterForHealth(Collection<ProvisioningActivity> as) {
        List<ProvisioningActivity> samples = new ArrayList<>(as.size());
        for (ProvisioningActivity sample : as) {
            ProvisioningActivity.Phase currentPhase = sample.getCurrentPhase();
            if (currentPhase != COMPLETED && currentPhase != OPERATING) continue;
            samples.add(sample);
        }
        return samples;
    }
}

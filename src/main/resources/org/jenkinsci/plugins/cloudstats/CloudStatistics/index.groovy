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

package org.jenkinsci.plugins.cloudstats.CloudStatistics

import org.jenkinsci.plugins.cloudstats.CloudStatistics
import org.jenkinsci.plugins.cloudstats.Health

def l = namespace(lib.LayoutTagLib)
def cs = namespace("lib/cloudstats")
def st = namespace("jelly:stapler")

// Pull vars from binding into script for type safety / code assistance
CloudStatistics stats = my

style("""
        #cloud-stats-overview th {
            text-align: left;
        }
""")

l.layout(permission: stats.getRequiredPermission()) {
    l.header(title: stats.displayName)
    l.main_panel {
        h1(stats.displayName)
        table(class: "jenkins-table sortable", width: "100%", id: "cloud-stats-overview") {
            tr {
                th("Cloud"); th("Template"); th("Overall success rate"); th("Current success rate"); th("Sample count")
            }
            def index = stats.index
            def templateHealth = index.healthByTemplate()
            index.healthByCloud().each { String cloud, Health ch ->
                tr {
                    td(cloud)
                    td()
                    td {
                        def score = ch.overall
                        l.icon("class": "${score.weather.iconClassName} icon-sm", alt: score.weather.score + "")
                        st.nbsp()
                        text(score)
                    }
                    td {
                        def score = ch.current
                        l.icon("class": "${score.weather.iconClassName} icon-sm", alt: score.weather.score + "")
                        st.nbsp()
                        text(score)
                    }
                    td(ch.numSamples)
                }
                def templates = templateHealth.get(cloud)
                if (templates.size() != 1 || templates.get(null) == null) {
                    templates.each { String template, Health th ->
                        tr {
                            td()
                            td(template)
                            td {
                                def score = th.overall
                                l.icon("class": "${score.weather.iconClassName} icon-sm", alt: score.weather.score + "")
                                st.nbsp()
                                text(score)
                            }
                            td {
                                def score = th.current
                                l.icon("class": "${score.weather.iconClassName} icon-sm", alt: score.weather.score + "")
                                st.nbsp()
                                text(score)
                            }
                            td(th.numSamples)
                        }
                    }
                }
            }
        }

        h2("Provisioning attempts")
        cs.attempts(stats: stats, activities: stats.activities)
    }
}

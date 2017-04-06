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

package org.jenkinsci.plugins.cloudstats

import static hudson.Util.getTimeSpanString;
import java.text.DateFormat
import java.text.SimpleDateFormat

def l = namespace(lib.LayoutTagLib)
def st = namespace("jelly:stapler")

l.layout(permission: app.ADMINISTER) {
    style("""
        cloud-stat-grid {
            border: 1px solid #BBB;
        }

        td.status-WARN {
            background-color: #FFBA93;
        }

        tr.status-WARN {
            background-color: #B2F5A8;
        }

        td.status-FAIL {
            background-color: #FF8B8F;
        }

        tr.status-FAIL {
            background-color: #FFB4B1;
        }

        .status-OK {
            background-color: #B2F5A8;
        }
    """)
    l.header(title: my.displayName)
    l.main_panel {
        h1(my.displayName)
        table(class: "pane sortable bigtable", width: "100%") {
            tr {
                th("Cloud"); th("Template"); th("Overall success rate"); th("Current success rate")
            }
            def index = my.index
            def templateHealth = index.healthByTemplate()
            index.healthByCloud().each { String cloud, Health ch ->
                tr {
                    td(cloud)
                    td()
                    td(ch.overall)
                    td(ch.current)
                }
                def templates = templateHealth.get(cloud)
                if (templates.size() != 1 || templates.get(null) == null) {
                    templates.each { String template, Health th ->
                        tr {
                            td()
                            td(template)
                            td(th.overall)
                            td(th.current)
                        }
                    }
                }
            }
        }

        h2("Provisioning attempts")
        table(class: "pane sortable bigtable", width: "100%", id: "cloud-stat-grid") {
            tr {
                th("Cloud"); th("Template"); th("Name"); th("Started"); th("Provisioning"); th("Launch"); th("Operation"); th("Completed")
            }

            DateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")

            my.activities.reverseEach { ProvisioningActivity activity ->
                def activityStatus = activity.status
                List<PhaseExecution> executions = new ArrayList<>(activity.phaseExecutions.values())
                tr("class": "status-${activityStatus}") {
                    td(activity.id.cloudName)
                    td(activity.id.templateName)
                    td(activity.name)
                    td(data: executions[0].startedTimestamp) {
                        text(df.format(executions[0].started))
                    }
                    for (PhaseExecution execution: executions) {
                        if (execution == null) {
                            td() // empty cell
                            continue
                        }

                        String content;
                        long data;
                        if (execution.phase != ProvisioningActivity.Phase.COMPLETED) {
                            def duration = activity.getDuration(execution)
                            data = Math.abs(duration)
                            content = getTimeSpanString(data)
                            if (duration < 0) {
                                content += " and counting"
                            }
                        } else {
                            content = df.format(execution.started)
                            data = execution.startedTimestamp
                        }

                        def attrs = [ "data": data ]
                        if (execution.status.ordinal() >= activityStatus.ordinal()) {
                            attrs["class"] = "status-${execution.status}"
                        }
                        td(attrs) {
                            text(content)
                            if (!execution.attachments.isEmpty()) {
                                ul {
                                    for (PhaseExecutionAttachment attachment : execution.attachments) {
                                        li {
                                            def url = my.getUrl(activity, execution, attachment)
                                            def title = attachment.displayName
                                            if (url == null) {
                                                text(title)
                                            } else {
                                                a(href: url) { text(title) }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

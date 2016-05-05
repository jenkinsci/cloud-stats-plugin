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
            border: 1px. solid #BBB;
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

        table(class: "pane sortable bigtable", width: "100%", id: "cloud-stat-grid") {
            tr {
                th("Cloud"); th("Template"); th("Name"); th("Started"); th("Provisioning"); th("Launch"); th("Operation"); th("Completed")
            }

            DateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm");

            for (ProvisioningActivity activity in my.activities) {
                def activityStatus = activity.status
                List<PhaseExecution> executions = new ArrayList<>(activity.phaseExecutions.values())
                tr("class": "status-${activityStatus}") {
                    td(activity.id.cloudName)
                    td(activity.id.templateName)
                    td(activity.name)
                    td(df.format(executions[0].started))
                    for (PhaseExecution execution: executions) {
                        def status = (execution == null || execution.status.ordinal() < activityStatus.ordinal()) ? null : execution.status
                        td(class: "status-${status}") {
                            if (execution != null) {
                                if (execution.phase != ProvisioningActivity.Phase.COMPLETED) {
                                    def duration = activity.getDuration(execution)
                                    text(getTimeSpanString(Math.abs(duration)))
                                    if (duration < 0) {
                                        text(" and counting")
                                    }
                                } else {
                                    text(df.format(execution.started))
                                }
                                if (!execution.attachments.isEmpty()) {
                                    ul {
                                        for (PhaseExecutionAttachment attachment : execution.attachments) {
                                            li {
                                                def url = my.getUrl(activity, execution, attachment)
                                                if (url == null) {
                                                    text(attachment.title)
                                                } else {
                                                    a(href: url) { text(attachment.title) }
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
}

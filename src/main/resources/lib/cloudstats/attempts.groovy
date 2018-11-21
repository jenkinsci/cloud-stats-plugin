/**
 * List provisioning attempts in tabular form.
 *
 * @param stats An instance of CloudStatistics.
 * @param activities A collection of activities to display.
 */
package lib.cloudstats

import jenkins.model.Jenkins
import org.jenkinsci.plugins.cloudstats.CloudStatistics
import org.jenkinsci.plugins.cloudstats.PhaseExecution
import org.jenkinsci.plugins.cloudstats.PhaseExecutionAttachment
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity

import java.text.DateFormat
import java.text.SimpleDateFormat

import static hudson.Util.getTimeSpanString

// Pull vars from binding into script for type safety / code assistance
CloudStatistics cs = stats
Collection<ProvisioningActivity> acts = activities
Jenkins j = app

style("""
        #cloud-stat-grid {
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

        #cloud-stat-grid ul {
            margin: 0;
            padding-left: 4em;
        }
""")
table(class: "pane sortable bigtable", width: "100%", id: "cloud-stat-grid") {
  tr {
    th("Cloud"); th("Template"); th("Name"); th("Started"); th("Provisioning"); th("Launch"); th("Operation"); th("Completed")
  }

  DateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")

  String[] computerNames = j.getComputers().collect { it.name }

  acts.reverseEach { ProvisioningActivity activity ->
    def activityStatus = activity.status
    List<PhaseExecution> executions = new ArrayList<>(activity.phaseExecutions.values())
    tr("class": "status-${activityStatus}") {
      td(activity.id.cloudName)
      td(activity.id.templateName)
      td{
        def n = activity.name
        if (computerNames.contains(n)) {
          a(href: j.getRootUrl() + "computer/" + n + "/") { text(n) }
        } else {
          text(n)
        }
      }
      td(data: executions[0].startedTimestamp) {
        text(df.format(executions[0].started))
      }
      for (PhaseExecution execution: executions) {
        if (execution == null) {
          td() // empty cell
          continue
        }

        String content
        long data
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
                  def url = cs.getUrl(activity, execution, attachment)
                  if (url == null) {
                    text(attachment.title)
                  } else {
                    // It is OK to shorten the text as we have the whole page for the details
                    a(href: j.getRootUrl() + url) { text(attachment.displayName) }
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

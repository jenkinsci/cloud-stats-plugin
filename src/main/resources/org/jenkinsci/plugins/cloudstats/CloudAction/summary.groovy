package org.jenkinsci.plugins.cloudstats.CloudAction

import hudson.model.HealthReport
import org.jenkinsci.plugins.cloudstats.ActivityIndex
import org.jenkinsci.plugins.cloudstats.CloudAction
import org.jenkinsci.plugins.cloudstats.Health
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity

def l = namespace(lib.LayoutTagLib)
def st = namespace("jelly:stapler")
def cs = namespace("lib/cloudstats")

// Pull vars from binding into script for type safety / code assistance
CloudAction action = my

ActivityIndex index = action.cloudStatistics.index
Health.Report report = index.cloudHealth(action.cloud.name).current
HealthReport hr = report.weather

h2 {
    l.icon("class": "${hr.iconClassName} icon-md", alt: hr.score + "")
    st.nbsp()
    text("${action.displayName} (Health ${report})")
}

int provisioning = launching = operating = 0
def noteworthy = []
index.forCloud(action.cloud.name).each {
    if (it.currentPhase == ProvisioningActivity.Phase.PROVISIONING) {
        provisioning++
    } else if (it.currentPhase == ProvisioningActivity.Phase.LAUNCHING) {
        launching++
    } else if (it.currentPhase == ProvisioningActivity.Phase.OPERATING) {
        operating++
    }
    if (it.status != ProvisioningActivity.Status.OK) {
        noteworthy << it
    }
}
table {
    tr {
        th("Operation")
        td(operating)
    }
    tr {
        th("Provisioning")
        td(provisioning)
    }
    tr {
        th("Launching")
        td(launching)
    }
}

if (!noteworthy.empty) {
    h2("Recent Problems")
    cs.attempts(activities: noteworthy, stats: action.cloudStatistics)
}

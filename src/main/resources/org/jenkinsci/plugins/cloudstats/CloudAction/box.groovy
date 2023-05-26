package org.jenkinsci.plugins.cloudstats.CloudAction

import jenkins.model.Jenkins
import org.jenkinsci.plugins.cloudstats.CloudAction
import org.jenkinsci.plugins.cloudstats.TrackedItem

def j = namespace(lib.JenkinsTagLib)

// Pull vars from binding into script for type safety / code assistance
CloudAction action = my
Jenkins jenkins = app

def c = jenkins.getComputers().grep {
    it instanceof TrackedItem && it.id != null && it.id.cloudName == action.cloud.name
}

j.executors(computers: c, ajax: false)

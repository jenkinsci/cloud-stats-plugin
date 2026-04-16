# Cloud Statistics Plugin

The plugin collects activities of other plugins and visualizes them as well as
provides them to other plugins in form of an API.

Note that a provisioning plugin needs to explicitly integrate against this plugin
in order to capture its statistics.

The statistics are presented on **Manage Jenkins > Cloud Statistics**.

## Essentials

- The plugin tracks the N most recent provisioning activities. One such
activity covers the whole lifecycle from provisioning to agent deletion.

- The activities have 4 hard-coded phases: `provisioning`, `launching`,
`operating` and `completed`. Operation starts with first successful launch and
ends with agent deletion (it is the only productive phase). The activity
is completed once the agent is gone and the activity is effectively a
history.

- Each phase execution tracks start time and a list of attachments. The attachment
is extensible and can be a mere piece of html, hyperlink or a model object with
URL subspace. This is to attach and present any kind of information: logs, exceptions,
etc.

- Each attachment has a state: `ok`, `warn` or `fail`. The worst of all
attached states is propagated to the phase execution and activity level. (If
agent fails to launch, and exception will be attached explaining why the
launch phase and thus the whole activity has failed).

## Remote API

The plugin now exposes the collected provisioning activities through the
standard Jenkins remote API from the Cloud Statistics management page.

The API is available under both of the following URL forms:

- Management page route:
  - JSON: `/manage/cloud-stats/api/json?depth=2`
  - XML: `/manage/cloud-stats/api/xml?depth=2`
- Direct cloud-stats route:
  - JSON: `/cloud-stats/api/json?depth=2`
  - XML: `/cloud-stats/api/xml?depth=2`

These routes expose the same data; if you are linking from the Jenkins
management UI, use the management page route, and if you are querying the
plugin endpoint directly, use the direct cloud-stats route.

The exported data includes provisioning activity identifiers and names,
timestamps, current phase, overall status, phase execution details, and
attachments such as provisioning errors and exception stack traces.

Use `depth=2` to include nested phase executions and their attachments in the
response.

## Integrating cloud plugin with cloud-stats-plugin

In order for cloud-stats plugin to recognize provisioning activity to track,
plugins are expected to do the following:

- Make `PlannedNode`, `Computer` and `Node` implement `TrackedItem`. The interface
provides a single method to connect all those using a dedicated `Id` instance. The
instance holds some necessary data to connect it back to the cloud/template
responsible for provisioning and works as a unique fingerprint identifying a single provisioning
activity. Therefore, `PlannedNode`, `Computer` and `Node` are expected to provide
the same instance of the `Id`. Note that there is a convenient abstract class
`TrackedPlannedNode` that creates the `Id` so a plugin merely needs to pass it around.

- In case there is a way to provision agents that does not go through Jenkins
core (`NodeProvisioner`), plugins are expected to notify cloud-stats about such
activity. This often happens when agents are provisioned manually in the Jenkins UI.
Provided the agent gets attached to Jenkins, cloud-stats will track it just fine from launching
phase on without any explicit notifications from a provisioning plugin.

- Optionally, a plugin can attach any kind of information to any phase.
cloud-stats is generally able to attach an exception in case of a failed launch or provisioning
(with the exception mentioned above). Though a plugin may wants to attach logs, outputs,
link to external services, etc.

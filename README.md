# Cloud Statistics Plugin

## Essentials

- The plugin tracks N most recent provisioning activities. One such
activity covers the whole lifecycle from provisioning to slave deletion.

- The activity have 4 hardcoded phases: provisioning, launching,
operating, completed. Operation starts with first successful launch and
ends with slave deletion (it is the only productive phase). The activity
is Completed once the slave is gone and the activity is effectively a
history.

- Each phase execution tracks start time and a list of attachments. The attachment
is extensible and can be a mare piece of html, hyperlink or a model object with
URL subspace. This is to attach and present any kind of information: logs, exceptions,
etc.

- Each Attachment has a state: ok, warn or fail. The worse of
attached states is propagated to phase execution and activity level. (If
slave fails to launch, and exception will be attached explaining why the
launch phase and thus the whole activity has failed).

## Integrating cloud plugin with cloud-stat-plugin

In order for cloud-stats plugin to recognize provisioning activity to track,
plugins are expected to do the following:

- Make `PlannedNode`, `Computer` and `Node` implement `TrackedItem`. The interface
provides single method to connect all those using dedicated `Id` instance. The
instance holds some necessary data to connect it back to the cloud/template
responsible for provisioning and as a unique fingerprint identifying single provisioning
activity. Therefore, `PlannedNode`, `Computer` and `Node` are expected to provide
the same instance of the `Id`. Note that there is a convenient abstract class
`TrackedPlannedNode` that create the `Id` so plugin merely needs to pass it around.

- In case there is a way to provision slaves that does not goes through Jenkins
core (`NodeProvisioner`), plugins are expected to notify cloud-stats about such
activity. This often happens when slaves are provisioned manually in Jenkins UI.
Provided the slave get attached to Jenkins, cloud-stats will track it from launching
phase on so the plugin is expected to manually notify about provisioning.

- Optionally, plugin can attach any kind of information to any phase it likes.
cloud-stats is generally able to attach exception in case of failed launch or provisioning
(with the exception mention above). Though plugin may want to attach logs, outputs,
link to external services, etc.

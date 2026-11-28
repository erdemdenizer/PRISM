PRISM - Process & Resource Interactive System Manager

Run bin/PRISM. No Java installation is needed. Keep the folder together: the program uses the
files next to it. Ordinary monitoring needs no root rights.

bin/prism-demo is a controllable workload to try limits on, from a terminal:

    bin/prism-demo --cpu 25 --memory 256MiB --duration 5m
    bin/prism-demo --help

Logs are in $XDG_STATE_HOME/prism-srm/logs (by default ~/.local/state/prism-srm/logs), settings
in $XDG_CONFIG_HOME/prism-srm (by default ~/.config/prism-srm).

On Linux the native CPU and memory limits work through systemd scopes for desktop applications
(those started from a launcher or a terminal). PRISM asks for your consent before it moves a
process into its scope, every time, and the move cannot be undone while the process runs. It
never limits PRISM itself or a service of your desktop session, natively or emulated. Where the
system cannot provide native limits (no systemd 255 user manager, no cgroup v2, a controller not
delegated), the log and the Limits page say why and the emulated CPU limit is offered instead.

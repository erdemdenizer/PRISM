PRISM - Process & Resource Interactive System Manager

Run bin/PRISM. No Java installation is needed. Keep the folder together: the program uses the
files next to it. Ordinary monitoring needs no root rights.

bin/prism-demo is a controllable workload to try limits on, from a terminal:

    bin/prism-demo --cpu 25 --memory 256MiB --duration 5m
    bin/prism-demo --help

Logs are in $XDG_STATE_HOME/prism-srm/logs (by default ~/.local/state/prism-srm/logs), settings
in $XDG_CONFIG_HOME/prism-srm (by default ~/.config/prism-srm).

On Linux the native CPU and memory limits are not enabled in this release (decision K16); the
emulated CPU limit is offered instead.

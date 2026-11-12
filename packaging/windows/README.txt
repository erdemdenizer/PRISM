PRISM - Process & Resource Interactive System Manager

Run PRISM.exe. No Java installation is needed. Keep the folder together: the program uses the
files next to it. Ordinary monitoring needs no administrator rights.

prism-demo.exe is a controllable workload to try limits on, from a console window:

    prism-demo.exe --cpu 25 --memory 256MiB --duration 5m
    prism-demo.exe --help

Logs and settings are in %LOCALAPPDATA%\Prism\SystemResourceManager.
This build is not code-signed, so Windows may warn before the first start.

# 0005. System metric semantics: CPU busy time and totals over physical devices

- Status: Accepted
- Date: 2026-10-05

## Context

Two of the system metrics have more than one defensible meaning, and the first
implementation showed that the choice is visible to the user.

**CPU.** On Windows there are two processor counters. *% Processor Time* is the share of
time the processors were not idle. *% Processor Utility* scales that by the frequency the
processor ran at relative to its nominal frequency, so it rises above the busy share
under turbo boost; Task Manager has shown it since Windows 8. OSHI reads the time-based
counters by default and offers the option `oshi.os.windows.cpu.utility` to read the
utility-based ones. Measured on the development machine, Prism's value tracked
*% Processor Time* within a few points while *% Processor Utility* was 5 to 12 points
higher. Linux and macOS have only the time-based figure.

**Disk and network totals.** The system lists more devices than there is hardware.
Windows lists every filter driver bound to a network adapter as an interface with a copy
of the adapter's counters (36 interfaces for 3 adapters on the development machine); a
VPN tunnel counts traffic that the adapter underneath counts again; a mounted virtual
disk counts transfers that the disk holding its image file counts again; on Linux the
same holds for bridges, bonds and device-mapper volumes. A sum over everything that is
listed counts the same bytes several times.

## Decision

1. **The CPU percentage is busy time on every system.** `oshi.os.windows.cpu.utility`
   stays off. Prism prefers one meaning across systems to matching Task Manager's number
   on Windows. No second CPU metric and no option is offered.
2. **Every disk and network interface is reported with its layer**: `PHYSICAL` (a
   hardware device at the bottom of the stack), `LOGICAL` (implemented in software on top
   of other devices or inside the machine), or `UNKNOWN` (the system does not say).
3. **A total is the sum over the physical devices only**, which is the traffic that
   crossed the machine's hardware. A logical device is never added to it. Every network
   interface, logical ones included, keeps its own rates in the snapshot; disks have no
   per-device rates there, because the specification asks for the disk total only.
4. **A total is absent rather than doubtful.** It is not produced when any listed device
   is `UNKNOWN`, when there is no physical device, or when the set of physical devices
   changed since the previous reading.
5. **The layer comes from a documented fact of the system, never from a device name.**
   - Windows interfaces: `ConnectorPresent` of `MIB_IF_ROW2`, which OSHI exposes and
     Windows sets "if there is a physical network adapter".
   - Windows disks: `BusType` of `MSFT_Disk` in the Storage Management API. Hardware
     buses are physical, a file-backed virtual disk is logical, anything else is unknown.
   - Linux and macOS: unknown for now. OSHI has no such fact there, so no total is
     produced on those systems until a source for it is written and run on them.

## Alternatives considered

- **Enable the OSHI utility option on Windows.** Matches Task Manager, but then 50% means
  something different on Windows than on Linux and macOS, and can exceed what "share of
  time busy" allows.
- **Sum every listed device.** The original behaviour for disks; multiplies the result by
  the number of layers.
- **Filter devices by name** (`br*`, `veth*`, "Virtual"). Names are conventions, are
  localized on Windows, and change; a wrong guess is silent.
- **Leave out every non-physical interface from the snapshot.** Hides traffic the user may
  care about, such as a VPN or a host-only network.
- **No total whenever a logical device exists.** Honest, but almost every development
  machine has a virtual adapter, so the total would rarely exist.

## Consequences

- On Windows the CPU value is compared with a *% Processor Time* counter when verifying,
  not with Task Manager, and the documentation says why the two differ.
- Traffic that never leaves the machine, such as between the host and a virtual machine on
  a host-only network, is not in the network total. It is visible on its own interface.
- On Linux and macOS the disk and network totals are absent until layer sources exist for
  them. Per-interface rates are available there.
- A storage space on Windows is `UNKNOWN`, which removes the disk total on machines that
  use one: it has not been established whether its member disks are listed as well.
- `DeviceRateTracker` implements rules 3 and 4 with no knowledge of the operating system
  and is tested with synthetic device topologies.
- The Windows disk query goes through OSHI's WMI helper classes
  (`oshi.util.platform.windows.WmiQueryHandler`, `WmiUtil`). They are utility classes that
  OSHI does not mark as public API, unlike `SystemInfo`, so an OSHI upgrade must re-check
  `WindowsDiskLayers`. Writing the COM initialization by hand was the alternative; it
  would repeat what OSHI already does on the same thread.
- An `Error` in a sampling cycle is logged and rethrown, which ends monitoring; nothing
  above the monitor is told yet. Milestone 3, which owns start-up and shutdown, decides
  how the application reacts to a monitor that has stopped.

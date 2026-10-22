/**
 * Operating-system boundary. This package holds the OS-neutral interfaces and the selection of the
 * implementation for the running system; it contains no JNA or OSHI code itself. The types the
 * interfaces exchange, result types included, live in {@code prism.srm.domain}. Implementations
 * live in the sub-packages: {@code oshi} for cross-platform code backed by OSHI, and {@code
 * windows}, {@code linux} and {@code macos} for OS-specific code. Those four are the only places
 * where JNA or OSHI may appear.
 */
package prism.srm.platform;

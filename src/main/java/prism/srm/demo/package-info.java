/**
 * The demo workload: a separate program that consumes CPU and memory in a controlled, bounded way,
 * so that monitoring, control, limits and OOM Guard can be demonstrated on a process that is safe to
 * suspend, limit and kill (DEMO-1 to DEMO-3). It is started on its own, never by the application. It
 * uses only the JDK, depends on no other package of Prism, and nothing in Prism depends on it;
 * {@code ArchitectureTest} keeps it that way.
 */
package prism.srm.demo;

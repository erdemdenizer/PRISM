package prism.srm.platform.oshi;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import oshi.software.os.OSProcess;
import oshi.software.os.OSThread;

/**
 * An OSHI process whose values are set by the test. It records every call that would make the
 * real OSHI go back to the operating system for one process, so a test can assert that none was
 * made.
 */
final class FakeOsProcess implements OSProcess {

    int pid = 100;
    long startTime = 1_700_000_000_000L;
    State state = State.RUNNING;
    String name = "fake";
    int parentPid = 1;
    int threadCount = 4;
    int priority = 8;
    long virtualSize = 4_000_000;
    long residentMemory = 2_000_000;
    long privateResidentMemory = 1_500_000;
    long kernelTime = 300;
    long userTime = 700;
    long bytesRead = 10_000;
    long bytesWritten = 5_000;
    String user = "alice";
    String userId = "1000";
    String path = "/opt/fake/bin/fake";
    String commandLine = "fake --serve";

    /** Thrown by {@link #getResidentMemory()} when set, to stand for a process that cannot be read. */
    RuntimeException failure;

    int userQueries;
    final List<String> expensiveCalls = new ArrayList<>();

    FakeOsProcess(int pid, long startTime) {
        this.pid = pid;
        this.startTime = startTime;
    }

    @Override
    public int getProcessID() {
        return pid;
    }

    @Override
    public long getStartTime() {
        return startTime;
    }

    @Override
    public State getState() {
        return state;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public int getParentProcessID() {
        return parentPid;
    }

    @Override
    public int getThreadCount() {
        return threadCount;
    }

    @Override
    public int getPriority() {
        return priority;
    }

    @Override
    public long getVirtualSize() {
        return virtualSize;
    }

    @Override
    public long getResidentMemory() {
        if (failure != null) {
            throw failure;
        }
        return residentMemory;
    }

    @Override
    public long getPrivateResidentMemory() {
        return privateResidentMemory;
    }

    @Override
    public long getKernelTime() {
        return kernelTime;
    }

    @Override
    public long getUserTime() {
        return userTime;
    }

    @Override
    public long getBytesRead() {
        return bytesRead;
    }

    @Override
    public long getBytesWritten() {
        return bytesWritten;
    }

    @Override
    public String getUser() {
        userQueries++;
        return user;
    }

    @Override
    public String getPath() {
        expensiveCalls.add("getPath");
        return path;
    }

    @Override
    public String getCommandLine() {
        expensiveCalls.add("getCommandLine");
        return commandLine;
    }

    @Override
    public List<String> getArguments() {
        expensiveCalls.add("getArguments");
        return List.of();
    }

    @Override
    public Map<String, String> getEnvironmentVariables() {
        expensiveCalls.add("getEnvironmentVariables");
        return Map.of();
    }

    @Override
    public String getCurrentWorkingDirectory() {
        expensiveCalls.add("getCurrentWorkingDirectory");
        return "";
    }

    @Override
    public String getUserID() {
        return userId;
    }

    @Override
    public String getGroup() {
        expensiveCalls.add("getGroup");
        return "";
    }

    @Override
    public String getGroupID() {
        expensiveCalls.add("getGroupID");
        return "";
    }

    @Override
    public List<OSThread> getThreadDetails() {
        expensiveCalls.add("getThreadDetails");
        return List.of();
    }

    @Override
    public boolean updateAttributes() {
        expensiveCalls.add("updateAttributes");
        return true;
    }

    @Override
    public long getOpenFiles() {
        expensiveCalls.add("getOpenFiles");
        return 0;
    }

    @Override
    public long getSoftOpenFileLimit() {
        expensiveCalls.add("getSoftOpenFileLimit");
        return 0;
    }

    @Override
    public long getHardOpenFileLimit() {
        expensiveCalls.add("getHardOpenFileLimit");
        return 0;
    }

    @Override
    public long getAffinityMask() {
        expensiveCalls.add("getAffinityMask");
        return 0;
    }

    @Override
    public double getProcessCpuLoadCumulative() {
        expensiveCalls.add("getProcessCpuLoadCumulative");
        return 0;
    }

    @Override
    public double getProcessCpuLoadBetweenTicks(OSProcess process) {
        expensiveCalls.add("getProcessCpuLoadBetweenTicks");
        return 0;
    }

    @Override
    public long getUpTime() {
        return 0;
    }

    @Override
    public int getBitness() {
        return 64;
    }
}

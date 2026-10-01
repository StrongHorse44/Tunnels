package io.github.stronghorse44.tunnels.deepmode;

/** The Shizuku user service: a shell (uid 2000) process that runs one command at a time for the app. */
interface IDeepShell {
    /** Destroy method defined by the Shizuku server; the transaction code is fixed by Shizuku. */
    void destroy() = 16777114;

    /** Asks the service process to exit. */
    void exit() = 1;

    /**
     * Runs `/system/bin/sh -c cmd` with a 20-second timeout and a 2 MB output cap. Returns combined
     * stdout and stderr; a non-zero exit appends "[exit N]", a timeout "[timed out]", a cap "[truncated]".
     */
    String runCommand(String cmd) = 2;
}

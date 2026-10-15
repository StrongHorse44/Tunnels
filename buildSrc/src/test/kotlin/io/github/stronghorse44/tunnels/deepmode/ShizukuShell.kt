package io.github.stronghorse44.tunnels.deepmode

/** Stand-in with the real class's name, so the egress tests can compile a caller of it. */
class ShizukuShell {
    fun run(command: String): String = command
}

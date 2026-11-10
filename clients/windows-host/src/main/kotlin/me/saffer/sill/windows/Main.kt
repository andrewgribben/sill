package me.saffer.sill.windows

import java.net.InetAddress

/**
 * Sill for Windows, phase 1: a Desktop-only home-door host. Run with no arguments to use the
 * default port and %LOCALAPPDATA%\Sill\ for state; `--port <n>` overrides the port, `--name <n>`
 * overrides the PC name Windows reports.
 */
fun main(args: Array<String>) {
    var port = HostDoor.DEFAULT_PORT
    var name = computerName()
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--port" -> { port = args.getOrNull(i + 1)?.toIntOrNull() ?: port; i++ }
            "--name" -> { name = args.getOrNull(i + 1) ?: name; i++ }
        }
        i++
    }

    val state = HostState(HostState.defaultDir())
    Log.start(state.dir)
    Log.info("Sill for Windows starting: PC '$name', door port $port, state in ${state.dir}, launch ${state.launchID}")
    Log.info("Mac ID ${me.saffer.sill.core.MacId.make(state.identity.fingerprint)}")

    val door = HostDoor(state, name, port)
    Log.info("home door listening on 0.0.0.0:${door.boundPort} (sill/1 paired only, sill-pair/1 for pairing)")

    val mdns = MdnsResponder(name, door.boundPort, { state.recognitionKey })
    mdns.start()
    Log.info("advertising _sill._tcp.local. as '$name'")

    Runtime.getRuntime().addShutdownHook(Thread {
        Log.info("shutting down")
        door.sessions.forEach { it.goodbye(me.saffer.sill.core.Goodbye(me.saffer.sill.core.Goodbye.QUIT)) }
        mdns.stop()
        door.close()
    })

    // The main thread just waits: everything runs on the door's and mDNS's own daemon threads.
    Thread.currentThread().join()
}

private fun computerName(): String =
    System.getenv("COMPUTERNAME") ?: try { InetAddress.getLocalHost().hostName } catch (_: Exception) { "Windows PC" }

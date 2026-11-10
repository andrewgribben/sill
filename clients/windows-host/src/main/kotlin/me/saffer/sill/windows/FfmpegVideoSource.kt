package me.saffer.sill.windows

import me.saffer.sill.core.ParameterSets
import java.io.File

/**
 * Captures one monitor with ffmpeg (ddagrab + hevc_nvenc, falling back to gdigrab + hevc_mf, then
 * gdigrab + libx265) and parses its Annex B stdout into parameter sets and access units. Dropping
 * frames rather than queuing them is the caller's job ([HostSession.VideoPump]): this class only
 * reads whatever ffmpeg writes and hands it on.
 *
 * shortcut: a keyframe request restarts ffmpeg (a fresh encoder always starts on an IDR), which
 * costs a connection stall of a few frames; a cleaner fix (NVENC's forced-IDR API) is a later
 * upgrade once phase 1's correctness is proven.
 */
class FfmpegVideoSource(private val monitorIndex: Int, private val width: Int, private val height: Int, private val fps: Int = 60, private val bitrate: Int = 15_000_000) : VideoSource {
    @Volatile private var process: Process? = null
    @Volatile private var running = false
    @Volatile private var reader: Thread? = null
    private var onParams: ((ParameterSets) -> Unit)? = null
    private var onFrame: ((Boolean, ByteArray) -> Unit)? = null

    override fun start(onParams: (ParameterSets) -> Unit, onFrame: (Boolean, ByteArray) -> Unit) {
        this.onParams = onParams; this.onFrame = onFrame
        running = true
        spawn()
    }

    private fun spawn() {
        val exe = findFfmpeg() ?: run { Log.error("ffmpeg not found on PATH or at the known WinGet install"); return }
        val attempts = listOf(
            nvencCommand(exe), mfCommand(exe), x265Command(exe),
        )
        for ((i, cmd) in attempts.withIndex()) {
            Log.info("ffmpeg attempt ${i + 1}/${attempts.size}: ${cmd.joinToString(" ")}")
            try {
                val p = ProcessBuilder(cmd).redirectErrorStream(false).start()
                Thread({ p.errorStream.bufferedReader().forEachLine { Log.info("ffmpeg: $it") } }, "ffmpeg-stderr").apply { isDaemon = true }.start()
                // An encoder or grabber that can't open exits within a moment: try the next one.
                if (p.waitFor(1500, java.util.concurrent.TimeUnit.MILLISECONDS)) { Log.warn("ffmpeg attempt ${i + 1} exited (${p.exitValue()})"); continue }
                if (!running) { p.destroyForcibly(); return }
                process = p
                startReader(p)
                return
            } catch (e: Exception) {
                Log.warn("ffmpeg attempt ${i + 1} failed to start: ${e.message}")
            }
        }
        // Capture is refused while the desktop can't be read (locked, a UAC prompt): try again until it can.
        Log.error("every ffmpeg attempt failed; trying again in 2 s")
        Thread({ try { Thread.sleep(2000) } catch (_: InterruptedException) {}; if (running && process?.isAlive != true) spawn() }, "ffmpeg-retry").apply { isDaemon = true; start() }
    }

    private fun startReader(p: Process) {
        val assembler = AccessUnitAssembler({ s -> onParams?.invoke(s) }, { key, au -> onFrame?.invoke(key, au) })
        reader = Thread({
            val stream = AnnexBStream(assembler::feed)
            val chunk = ByteArray(1 shl 16)
            val input = p.inputStream
            try {
                while (running) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    stream.write(chunk, n)
                }
            } catch (_: Exception) {
            } finally {
                // The one place a capture restarts (a keyframe request only kills the process), paced so a
                // capture that keeps failing (a locked screen) doesn't spin.
                if (running) { Log.warn("ffmpeg's stdout ended; restarting the capture"); try { Thread.sleep(500) } catch (_: InterruptedException) {}; if (running) spawn() }
            }
        }, "ffmpeg-reader").apply { isDaemon = true; start() }
    }

    override fun requestKeyframe() {
        Log.info("keyframe requested: restarting ffmpeg for a fresh IDR")
        process?.destroyForcibly()   // the reader sees stdout end and spawns the next one
    }

    override fun stop() {
        running = false
        process?.destroyForcibly()
        reader?.interrupt()
        process = null
    }

    private fun gopSeconds2() = fps * 2

    private fun nvencCommand(exe: String) = listOf(
        exe, "-y", "-hide_banner", "-loglevel", "warning",
        // ddagrab hands D3D11 frames straight to NVENC: no copy through system memory.
        "-init_hw_device", "d3d11va", "-filter_complex", "ddagrab=output_idx=$monitorIndex:framerate=$fps",
        "-c:v", "hevc_nvenc", "-preset", "p1", "-tune", "ull", "-bf", "0", "-delay", "0", "-zerolatency", "1",
        "-rc", "cbr", "-b:v", bitrate.toString(), "-maxrate", bitrate.toString(), "-bufsize", (bitrate / 2).toString(),
        "-g", gopSeconds2().toString(), "-r", fps.toString(), "-forced-idr", "1",
        "-f", "hevc", "-",
    )

    private fun mfCommand(exe: String) = listOf(
        exe, "-y", "-hide_banner", "-loglevel", "warning",
        "-f", "gdigrab", "-framerate", fps.toString(), "-i", "desktop",
        "-c:v", "hevc_mf", "-b:v", bitrate.toString(), "-g", gopSeconds2().toString(), "-r", fps.toString(),
        "-f", "hevc", "-",
    )

    private fun x265Command(exe: String) = listOf(
        exe, "-y", "-hide_banner", "-loglevel", "warning",
        "-f", "gdigrab", "-framerate", fps.toString(), "-i", "desktop",
        "-c:v", "libx265", "-preset", "ultrafast", "-tune", "zerolatency", "-b:v", bitrate.toString(),
        "-g", gopSeconds2().toString(), "-r", fps.toString(), "-x265-params", "repeat-headers=1",
        "-f", "hevc", "-",
    )

    companion object {
        /** The known WinGet install path (docs/windows-host-plan.md), then PATH. */
        fun findFfmpeg(): String? {
            val known = File(System.getenv("LOCALAPPDATA") ?: "", "Microsoft\\WinGet\\Packages")
            if (known.isDirectory) {
                known.walkTopDown().maxDepth(4).firstOrNull { it.name.equals("ffmpeg.exe", true) }?.let { return it.absolutePath }
            }
            val path = System.getenv("PATH") ?: return null
            val sep = File.pathSeparator
            for (dir in path.split(sep)) {
                val f = File(dir, if (isWindows()) "ffmpeg.exe" else "ffmpeg")
                if (f.isFile) return f.absolutePath
            }
            return null
        }

        fun isWindows() = System.getProperty("os.name", "").lowercase().contains("win")
    }
}

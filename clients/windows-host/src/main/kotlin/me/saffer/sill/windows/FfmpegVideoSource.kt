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
                process = p
                Thread({ p.errorStream.bufferedReader().forEachLine { Log.info("ffmpeg: $it") } }, "ffmpeg-stderr").apply { isDaemon = true }.start()
                startReader(p)
                return
            } catch (e: Exception) {
                Log.warn("ffmpeg attempt ${i + 1} failed to start: ${e.message}")
            }
        }
        Log.error("every ffmpeg attempt failed; no video will stream")
    }

    private fun startReader(p: Process) {
        val assembler = AccessUnitAssembler({ s -> onParams?.invoke(s) }, { key, au -> onFrame?.invoke(key, au) })
        reader = Thread({
            val buf = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(1 shl 16)
            val input = p.inputStream
            try {
                while (running) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    buf.write(chunk, 0, n)
                    // Keep the tail after the last start code: it may be an incomplete NAL.
                    val all = buf.toByteArray()
                    val units = AnnexBParser.splitUnits(all)
                    if (units.isEmpty()) continue
                    // Re-run on everything but the final ~64 bytes kept for the next read is wasteful at
                    // this scale (a few Mbit/s); simplicity over micro-optimisation for phase 1.
                    units.dropLast(1).forEach(assembler::feed)
                    val keepFrom = lastStartCodeOffset(all)
                    buf.reset()
                    if (keepFrom >= 0) { buf.write(all, keepFrom, all.size - keepFrom); assembler.feed(units.last()) }
                    else buf.write(all)
                }
            } catch (_: Exception) {
            } finally {
                if (running) { Log.warn("ffmpeg's stdout ended; restarting the capture"); spawn() }
            }
        }, "ffmpeg-reader").apply { isDaemon = true; start() }
    }

    private fun lastStartCodeOffset(data: ByteArray): Int {
        for (i in data.size - 4 downTo 0) if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()) return i
        return -1
    }

    override fun requestKeyframe() {
        Log.info("keyframe requested: restarting ffmpeg for a fresh IDR")
        restart()
    }

    private fun restart() {
        process?.destroyForcibly()
        spawn()
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
        "-filter_complex", "ddagrab=output_idx=$monitorIndex,hwdownload,format=bgra",
        "-f", "lavfi", "-i", "-",
        "-c:v", "hevc_nvenc", "-preset", "p1", "-tune", "ull", "-bf", "0", "-delay", "0", "-zerolatency", "1",
        "-rc", "cbr", "-b:v", bitrate.toString(), "-maxrate", bitrate.toString(), "-bufsize", (bitrate / 2).toString(),
        "-g", gopSeconds2().toString(), "-r", fps.toString(), "-forced-idr", "1", "-repeat-headers", "1",
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

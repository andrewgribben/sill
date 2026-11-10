package me.saffer.sill.windows

import java.io.File
import java.io.PrintStream
import java.text.SimpleDateFormat
import java.util.Date

/** Logs to %LOCALAPPDATA%\Sill\Sill.log and stdout (both best-effort: a log that fails never stops the host). */
object Log {
    @Volatile private var file: PrintStream? = null
    @Volatile var path: File? = null; private set
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")

    fun start(dir: File) {
        try {
            dir.mkdirs()
            path = File(dir, "Sill.log")
            file = PrintStream(java.io.FileOutputStream(path!!, true), true, "UTF-8")
        } catch (e: Exception) {
            println("could not open the log file: ${e.message}")
        }
    }

    @Synchronized private fun line(level: String, msg: String) {
        val s = "${fmt.format(Date())} [$level] $msg"
        println(s)
        file?.println(s)
    }

    fun info(msg: String) = line("INFO", msg)
    fun warn(msg: String) = line("WARN", msg)
    fun error(msg: String, t: Throwable? = null) = line("ERROR", msg + (t?.let { ": ${it.message}" } ?: ""))
}

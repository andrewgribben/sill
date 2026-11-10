package me.saffer.sill.windows

import me.saffer.sill.core.ParameterSets
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Splits an Annex B HEVC byte stream (what ffmpeg's `-f hevc` writes to stdout) into NAL units, and
 * groups them into parameter sets and access units for the wire (StreamMessage.kt: kind 0's
 * hvcC-style blob, and kind 1's 4-byte length-prefixed NAL units per frame).
 *
 * shortcut: one VCL NAL is treated as one access unit (right for the common case of a single slice
 * segment per picture, true of hevc_nvenc's low-latency presets and of libx265 with one slice);
 * a multi-slice picture would wrongly become several frames. Upgrade: track
 * first_slice_segment_in_pic_flag (the slice header's first bit after the NAL header) to merge them.
 */
object AnnexBParser {
    class Nal(val type: Int, val bytes: ByteArray)

    fun isParameterSet(type: Int) = type in 32..34          // VPS, SPS, PPS
    fun isVcl(type: Int) = type in 0..31
    fun isKeyframe(type: Int) = type in 16..21               // BLA_W_LP .. RSV_IRAP_VCL23 (IDR included)

    /** NAL units in `data`, without their start codes. Trailing bytes after the last start code with
     * no following start code are kept as a last, possibly incomplete, unit only when `complete` is false. */
    fun splitUnits(data: ByteArray): List<Nal> {
        val starts = ArrayList<Int>()
        var i = 0
        while (i + 2 < data.size) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()) { starts.add(i + 3); i += 3 }
            else i++
        }
        val out = ArrayList<Nal>()
        for ((idx, s) in starts.withIndex()) {
            // `starts[idx + 1]` is just after the next start code's 00 00 01, i.e. inside the next
            // NAL already; back up to where that start code began (its 00 00 01, and the extra 00
            // before it for a 4-byte 00 00 00 01) to find where this NAL actually ends.
            var end = if (idx + 1 < starts.size) starts[idx + 1] - 3 else data.size
            while (end > s && data[end - 1] == 0.toByte()) end--
            if (end <= s) continue
            val bytes = data.copyOfRange(s, end)
            if (bytes.isEmpty()) continue
            val type = (bytes[0].toInt() shr 1) and 0x3F
            out.add(Nal(type, bytes))
        }
        return out
    }

    fun lengthPrefixed(nals: List<ByteArray>): ByteArray {
        val o = ByteArrayOutputStream()
        for (n in nals) { o.write(ByteBuffer.allocate(4).putInt(n.size).array()); o.write(n) }
        return o.toByteArray()
    }
}

/**
 * Reads ffmpeg's stdout in whatever chunks the pipe gives, and hands on each NAL unit once, whole.
 * shortcut: a NAL counts as complete only when the next start code arrives, so each frame waits for
 * the next one (a frame's time, 17 ms at 60 fps). Upgrade: have ffmpeg insert an AUD after each
 * access unit, or read packets from a container with lengths.
 */
class AnnexBStream(private val onNal: (AnnexBParser.Nal) -> Unit) {
    private var pending = ByteArray(0)

    fun write(chunk: ByteArray, n: Int = chunk.size) {
        val all = pending + chunk.copyOf(n)
        val lastStart = lastStartCode(all)
        if (lastStart <= 0) { pending = all; return }
        AnnexBParser.splitUnits(all.copyOf(lastStart)).forEach(onNal)
        pending = all.copyOfRange(lastStart, all.size)   // the unit still arriving
    }

    private fun lastStartCode(data: ByteArray): Int {
        for (i in data.size - 3 downTo 0) if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()) return i
        return -1
    }
}

/** Feed [feed] every NAL as ffmpeg emits it; it calls back with parameter sets (only when changed) and access units. */
class AccessUnitAssembler(private val onParams: (ParameterSets) -> Unit, private val onFrame: (keyframe: Boolean, lengthPrefixed: ByteArray) -> Unit) {
    private var vps: ByteArray? = null
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var lastEmitted: ParameterSets? = null

    fun feed(nal: AnnexBParser.Nal) {
        when {
            nal.type == 32 -> vps = nal.bytes
            nal.type == 33 -> sps = nal.bytes
            nal.type == 34 -> { pps = nal.bytes; maybeEmitParams() }
            AnnexBParser.isVcl(nal.type) -> onFrame(AnnexBParser.isKeyframe(nal.type), AnnexBParser.lengthPrefixed(listOf(nal.bytes)))
            // AUD, SEI and other non-VCL units: dropped for phase 1 (shortcut).
        }
    }

    private fun maybeEmitParams() {
        val v = vps; val s = sps; val p = pps
        if (v == null || s == null || p == null) return
        val sets = ParameterSets(4, listOf(v, s, p))
        if (sets != lastEmitted) { lastEmitted = sets; onParams(sets) }
    }
}

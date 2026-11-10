package me.saffer.sill.windows

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Splitting an Annex B byte stream (ffmpeg's `-f hevc` output) into NAL units, parameter sets and access units. */
class AnnexBParserTest {
    private fun nal(type: Int, vararg extra: Int) = byteArrayOf((type shl 1).toByte(), 1) + extra.map { it.toByte() }.toByteArray()
    private val start = byteArrayOf(0, 0, 0, 1)

    @Test
    fun splitsUnitsAtStartCodes() {
        val vps = nal(32, 9, 9); val sps = nal(33, 8, 8); val pps = nal(34, 7, 7); val idr = nal(19, 1, 2, 3)
        val stream = start + vps + start + sps + start + pps + start + idr
        val units = AnnexBParser.splitUnits(stream)
        assertEquals(listOf(32, 33, 34, 19), units.map { it.type })
        assertContentEquals(idr, units.last().bytes)
    }

    @Test
    fun classifiesTypes() {
        assertTrue(AnnexBParser.isParameterSet(32)); assertTrue(AnnexBParser.isParameterSet(34)); assertTrue(!AnnexBParser.isParameterSet(19))
        assertTrue(AnnexBParser.isVcl(0)); assertTrue(AnnexBParser.isVcl(19)); assertTrue(!AnnexBParser.isVcl(32))
        assertTrue(AnnexBParser.isKeyframe(19)); assertTrue(AnnexBParser.isKeyframe(20)); assertTrue(!AnnexBParser.isKeyframe(1))
    }

    @Test
    fun assemblerEmitsParamsOnceThenFrames() {
        val vps = nal(32); val sps = nal(33); val pps = nal(34); val idr = nal(19, 9); val trail = nal(1, 8)
        val params = mutableListOf<me.saffer.sill.core.ParameterSets>()
        val frames = mutableListOf<Pair<Boolean, ByteArray>>()
        val a = AccessUnitAssembler({ params.add(it) }, { key, au -> frames.add(key to au) })
        for (nalUnit in AnnexBParser.splitUnits(start + vps + start + sps + start + pps + start + idr + start + trail)) a.feed(nalUnit)
        // A repeat of the same parameter sets (as ffmpeg sends before each IDR) changes nothing.
        for (nalUnit in AnnexBParser.splitUnits(start + vps + start + sps + start + pps + start + idr)) a.feed(nalUnit)
        assertEquals(1, params.size, "the unchanged parameter sets must be emitted once")
        assertEquals(listOf(true, false, true), frames.map { it.first })
        // Each access unit is 4-byte length prefixed, hvcC style.
        val first = frames[0].second
        assertEquals(idr.size, java.nio.ByteBuffer.wrap(first, 0, 4).int)
        assertContentEquals(idr, first.copyOfRange(4, 4 + idr.size))
    }
}

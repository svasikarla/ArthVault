package com.arthvault

import com.arthvault.data.parser.SenderMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** F1.1 — the allowlist is only as good as the header normalisation behind it. */
class SenderMatcherTest {

    @Test
    fun `normalises DLT headers to the bank code`() {
        assertEquals("HDFCBK", SenderMatcher.normalize("AD-HDFCBK-S"))
        assertEquals("ICICIB", SenderMatcher.normalize("VM-ICICIB"))
        assertEquals("SBIINB", SenderMatcher.normalize("JD-SBIINB-T"))
        assertEquals("HDFCBK", SenderMatcher.normalize("hdfcbk"))
    }

    @Test
    fun `allows a configured bank and rejects an unknown sender`() {
        val allowlist = setOf("HDFCBK", "ICICIB")
        assertTrue(SenderMatcher.isAllowed("AD-HDFCBK-S", allowlist))
        assertFalse(SenderMatcher.isAllowed("VK-SWIGGY", allowlist))
        assertFalse(SenderMatcher.isAllowed("+919812345678", allowlist))
    }

    @Test
    fun `an unconfigured allowlist allows everything rather than hiding the inbox`() {
        assertTrue(SenderMatcher.isAllowed("VK-SWIGGY", emptySet()))
    }
}

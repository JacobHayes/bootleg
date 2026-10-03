package dev.jacobhayes.bootleg.service

import dev.jacobhayes.bootleg.core.gadget.ApplyResult
import dev.jacobhayes.bootleg.core.gadget.DefaultGadgetController
import dev.jacobhayes.bootleg.core.gadget.GadgetState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class EjectAllFeedbackTest {
    @Test
    fun `a clean eject says nothing`() {
        assertNull(EjectAllFeedback.of(ApplyResult.Applied(GadgetState("", emptySet(), emptyMap(), emptyMap()), reenumerated = false)))
    }

    @Test
    fun `a host refusal is explained instead of looking like a dead button`() {
        val refused = ApplyResult.NeedsAttention("${DefaultGadgetController.EJECT_PREVENTED_REASON} (lun 0): EBUSY")
        assertEquals(EjectAllFeedback.HostUsing, EjectAllFeedback.of(refused))
    }

    @Test
    fun `other problems carry their reason`() {
        assertEquals(EjectAllFeedback.Failed("root gone"), EjectAllFeedback.of(ApplyResult.Failed("root gone")))
        assertEquals(EjectAllFeedback.Failed("UDC lost"), EjectAllFeedback.of(ApplyResult.NeedsAttention("UDC lost")))
    }
}

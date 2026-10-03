package dev.jacobhayes.bootleg.service

import dev.jacobhayes.bootleg.core.gadget.ApplyResult
import dev.jacobhayes.bootleg.core.gadget.DefaultGadgetController
import dev.jacobhayes.bootleg.core.gadget.GadgetState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

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
    fun `the refusal fits in a toast`() {
        // Android 12+ cuts toasts off at two lines (about 70 characters on a phone).
        val strings = File("src/main/res/values/strings_system.xml").readText()
        val text = Regex("""<string name="eject_all_host_using">([^<]*)</string>""").find(strings)!!.groupValues[1]
        assertTrue(text.length <= TOAST_TEXT_LENGTH_MAX, "${text.length} characters: $text")
    }

    @Test
    fun `other problems carry their reason`() {
        assertEquals(EjectAllFeedback.Failed("root gone"), EjectAllFeedback.of(ApplyResult.Failed("root gone")))
        assertEquals(EjectAllFeedback.Failed("UDC lost"), EjectAllFeedback.of(ApplyResult.NeedsAttention("UDC lost")))
    }

    private companion object {
        const val TOAST_TEXT_LENGTH_MAX = 64
    }
}

package dev.jacobhayes.bootleg.root

import android.content.Context
import dev.jacobhayes.bootleg.core.gadget.UsbFunctions
import java.io.IOException
import java.lang.reflect.InvocationTargetException

/**
 * Android's USB function setting through `UsbManager`'s system APIs `getCurrentFunctions()` and
 * `setCurrentFunctions(long)`, reached by reflection from the root process (uid 0 passes the
 * `MANAGE_USB` check; hidden-API checks don't apply to the root `app_process`).
 */
class RootUsbFunctions(
    context: Context,
) : UsbFunctions {
    private val manager: Any = checkNotNull(context.getSystemService(Context.USB_SERVICE)) { "no USB service" }
    private val getCurrent = manager.javaClass.getMethod("getCurrentFunctions")
    private val setCurrent = manager.javaClass.getMethod("setCurrentFunctions", Long::class.javaPrimitiveType)

    override fun current(): Long = call { getCurrent.invoke(manager) as Long }

    override fun set(functions: Long) {
        require(functions >= 0) { "bad USB functions: $functions" }
        call { setCurrent.invoke(manager, functions) }
    }

    private inline fun <T> call(block: () -> T): T =
        try {
            block()
        } catch (e: InvocationTargetException) {
            val cause = e.targetException
            throw IOException("UsbManager: ${cause.message}", cause)
        }
}

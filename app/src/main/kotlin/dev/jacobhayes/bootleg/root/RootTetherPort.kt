package dev.jacobhayes.bootleg.root

import android.content.Context
import android.util.Log
import dev.jacobhayes.bootleg.core.tether.RndisTetherPort
import dev.jacobhayes.bootleg.core.tether.TetherPolicy
import dev.jacobhayes.bootleg.core.tether.TetherPort
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Android's `TetheringManager` from the root process. Its start/stop and event callback are system
 * APIs, reached by reflection: uid 0 passes the `TETHER_PRIVILEGED` check, and hidden-API checks
 * don't apply to the root `app_process`. The entitlement check stays on (the plain
 * `startTethering(type, ...)` request never sets `exemptFromEntitlementCheck`).
 *
 * One event callback stays registered for the process's life; it keeps whether a USB interface is
 * tethered, which [usbTethered] returns without a binder call.
 */
class RootTetherPort(
    context: Context,
) : TetherPort {
    private val manager: Any = checkNotNull(context.getSystemService(TETHERING_SERVICE)) { "no tethering service" }
    private val managerClass = manager.javaClass
    private val callbackExecutor = Executor { it.run() }

    /** Null until Android's first report; the callback replays the current state when registered. */
    private val usbTethered = AtomicReference<Boolean?>(null)

    /** Every tethered interface's name, from the same callback. */
    private val tetheredNames = AtomicReference<Set<String>>(emptySet())
    private val firstReport = CountDownLatch(1)

    init {
        val eventCallbackClass = Class.forName("$MANAGER_CLASS\$TetheringEventCallback")
        val callback =
            proxy(eventCallbackClass) { method, arguments ->
                if (method.name == "onTetheredInterfacesChanged") onTethered(arguments?.firstOrNull())
            }
        managerClass
            .getMethod(
                "registerTetheringEventCallback",
                Executor::class.java,
                eventCallbackClass,
            ).invoke(manager, callbackExecutor, callback)
    }

    override fun start(timeoutMs: Long): TetherPort.StartOutcome = start(Int::class.javaPrimitiveType!!, TETHERING_USB, timeoutMs)

    /** `startTethering(type or request, executor, callback)`, waiting ≤ [timeoutMs] for the answer. */
    private fun start(
        requestClass: Class<*>,
        request: Any,
        timeoutMs: Long,
    ): TetherPort.StartOutcome {
        require(timeoutMs > 0) { "timeoutMs must be positive, got $timeoutMs" }
        val startCallbackClass = Class.forName("$MANAGER_CLASS\$StartTetheringCallback")
        // A fresh callback per attempt: an answer that comes after the timeout lands on an abandoned latch.
        val outcome = AtomicReference<TetherPort.StartOutcome>(TetherPort.StartOutcome.TimedOut)
        val answered = CountDownLatch(1)
        val callback =
            proxy(startCallbackClass) { method, arguments ->
                when (method.name) {
                    "onTetheringStarted" -> outcome.set(TetherPort.StartOutcome.Started)
                    "onTetheringFailed" -> outcome.set(TetherPort.StartOutcome.Failed(arguments?.firstOrNull() as? Int ?: -1))
                    else -> return@proxy
                }
                answered.countDown()
            }
        managerClass
            .getMethod("startTethering", requestClass, Executor::class.java, startCallbackClass)
            .invoke(manager, request, callbackExecutor, callback)
        if (!answered.await(timeoutMs, TimeUnit.MILLISECONDS)) return TetherPort.StartOutcome.TimedOut
        return outcome.get()
    }

    override fun stop() {
        managerClass.getMethod("stopTethering", Int::class.javaPrimitiveType).invoke(manager, TETHERING_USB)
    }

    override fun usbTethered(): Boolean {
        awaitFirstReport()
        return checkNotNull(usbTethered.get())
    }

    private fun awaitFirstReport() =
        check(firstReport.await(FIRST_REPORT_WAIT_MS_MAX, TimeUnit.MILLISECONDS)) {
            "Android hasn't reported the tethering state within ${FIRST_REPORT_WAIT_MS_MAX}ms"
        }

    /**
     * Bootleg's RNDIS transport: Android tethers the daemon's TAP by name (`TetheringRequest` of type
     * `TETHERING_VIRTUAL`, root only) and the daemon's medium follows. Null when this Android can't
     * tether an interface by name (probed once).
     */
    fun rndis(daemons: RndisDaemons): RndisTetherPort? {
        val request =
            runCatching {
                val builderClass = Class.forName("$MANAGER_CLASS\$TetheringRequest\$Builder")
                val builder = builderClass.getConstructor(Int::class.javaPrimitiveType).newInstance(TETHERING_VIRTUAL)
                builderClass.getMethod("setInterfaceName", String::class.java).invoke(builder, TetherPolicy.RNDIS_INTERFACE)
                checkNotNull(builderClass.getMethod("build").invoke(builder))
            }.onFailure { Log.i(TAG, "tethering an interface by name isn't available: RNDIS is unsupported", it) }
                .getOrNull() ?: return null
        val stopCallbackClass = Class.forName("$MANAGER_CLASS\$StopTetheringCallback")
        val stop = managerClass.getMethod("stopTethering", request.javaClass, Executor::class.java, stopCallbackClass)
        return object : RndisTetherPort {
            override fun start(timeoutMs: Long) = start(request.javaClass, request, timeoutMs)

            override fun stop() {
                stop.invoke(manager, request, callbackExecutor, proxy(stopCallbackClass) { _, _ -> })
            }

            override fun tethered(): Boolean {
                awaitFirstReport()
                return TetherPolicy.RNDIS_INTERFACE in tetheredNames.get()
            }

            override fun mediaConnected(): Boolean = daemons.mediaConnected()

            override fun setMedia(connected: Boolean) = daemons.setMedia(connected)
        }
    }

    /**
     * `onTetheredInterfacesChanged` comes as a `Set<TetheringInterface>` (type + name) on current
     * releases and as a `List<String>` of names on older ones: the type decides when there is one,
     * else the name ([TetherPolicy.isUsbTetherInterface]).
     */
    private fun onTethered(argument: Any?) {
        val elements = (argument as? Collection<*>).orEmpty().filterNotNull()
        val tethered =
            elements.any { element ->
                when (element) {
                    is String -> TetherPolicy.isUsbTetherInterface(element)
                    else -> (element.javaClass.getMethod("getType").invoke(element) as Int) in USB_TETHERING_TYPES
                }
            }
        tetheredNames.set(elements.map { it as? String ?: it.javaClass.getMethod("getInterface").invoke(it) as String }.toSet())
        usbTethered.set(tethered)
        firstReport.countDown()
    }

    /** A callback interface implemented by [onCall]; Object's methods behave as identity. */
    private fun proxy(
        callbackInterface: Class<*>,
        onCall: (Method, Array<out Any?>?) -> Unit,
    ): Any =
        Proxy.newProxyInstance(
            callbackInterface.classLoader,
            arrayOf(callbackInterface),
            InvocationHandler { self, method, arguments ->
                when (method.name) {
                    "equals" -> self === arguments?.firstOrNull()
                    "hashCode" -> System.identityHashCode(self)
                    "toString" -> "${callbackInterface.simpleName}@${System.identityHashCode(self)}"
                    else -> {
                        try {
                            onCall(method, arguments)
                        } catch (e: ReflectiveOperationException) {
                            Log.w(TAG, "tethering callback ${method.name} failed", e)
                        }
                        null
                    }
                }
            },
        )

    private companion object {
        const val TAG = "BootlegTether"
        const val TETHERING_SERVICE = "tethering"
        const val MANAGER_CLASS = "android.net.TetheringManager"

        // TetheringManager.TETHERING_USB / TETHERING_NCM / TETHERING_VIRTUAL.
        const val TETHERING_USB = 1
        const val TETHERING_NCM = 4
        const val TETHERING_VIRTUAL = 7
        val USB_TETHERING_TYPES = setOf(TETHERING_USB, TETHERING_NCM)

        /** Android replays the state as soon as the callback is registered (≈ 1ms); this bounds a stuck service. */
        const val FIRST_REPORT_WAIT_MS_MAX = 2_000L
    }
}

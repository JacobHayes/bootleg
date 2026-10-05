package dev.jacobhayes.bootleg.root

import android.content.Context
import android.util.Log
import dev.jacobhayes.bootleg.core.tether.TetherPolicy
import dev.jacobhayes.bootleg.core.tether.TetherPort
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Android's `TetheringManager` from the root process, tethering an interface by name
 * (`TetheringRequest` of type `TETHERING_VIRTUAL`). Its requests and event callback are system APIs,
 * reached by reflection: uid 0 passes the `TETHER_PRIVILEGED` check, and hidden-API checks don't
 * apply to the root `app_process`. Android runs no carrier entitlement check for this type.
 *
 * One event callback stays registered for the process's life; it keeps the tethered interfaces
 * (name to type), which [tethered] returns without a binder call, and calls [onChanged] on every
 * report (the first one replays the current state).
 */
class RootTetherPort private constructor(
    context: Context,
    private val onChanged: () -> Unit,
) : TetherPort {
    private val manager: Any = checkNotNull(context.getSystemService(TETHERING_SERVICE)) { "no tethering service" }
    private val managerClass = manager.javaClass
    private val callbackExecutor = Executor { it.run() }
    private val builderClass = Class.forName("$MANAGER_CLASS\$TetheringRequest\$Builder")
    private val startCallbackClass = Class.forName("$MANAGER_CLASS\$StartTetheringCallback")
    private val stopCallbackClass = Class.forName("$MANAGER_CLASS\$StopTetheringCallback")
    private val requestClass = request(GADGET_INTERFACE_PROBE).javaClass
    private val startMethod = managerClass.getMethod("startTethering", requestClass, Executor::class.java, startCallbackClass)
    private val stopMethod = managerClass.getMethod("stopTethering", requestClass, Executor::class.java, stopCallbackClass)

    /** Null until Android's first report. */
    private val tetheredInterfaces = AtomicReference<Map<String, Int>?>(null)
    private val firstReport = CountDownLatch(1)
    private val bootlegDropped = AtomicBoolean(false)

    init {
        val eventCallbackClass = Class.forName("$MANAGER_CLASS\$TetheringEventCallback")
        val callback =
            proxy(eventCallbackClass) { method, arguments ->
                if (method.name == "onTetheredInterfacesChanged") onTethered(arguments?.firstOrNull())
            }
        managerClass
            .getMethod("registerTetheringEventCallback", Executor::class.java, eventCallbackClass)
            .invoke(manager, callbackExecutor, callback)
    }

    override fun start(
        interfaceName: String,
        timeoutMs: Long,
    ): TetherPort.StartOutcome {
        require(timeoutMs > 0) { "timeoutMs must be positive, got $timeoutMs" }
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
        startMethod.invoke(manager, request(interfaceName), callbackExecutor, callback)
        if (!answered.await(timeoutMs, TimeUnit.MILLISECONDS)) return TetherPort.StartOutcome.TimedOut
        return outcome.get()
    }

    override fun stop(
        interfaceName: String,
        timeoutMs: Long,
    ): Int? {
        require(timeoutMs > 0) { "timeoutMs must be positive, got $timeoutMs" }
        val error = AtomicReference<Int?>(null)
        val answered = CountDownLatch(1)
        val callback =
            proxy(stopCallbackClass) { method, arguments ->
                when (method.name) {
                    "onStopTetheringSucceeded" -> Unit
                    "onStopTetheringFailed" -> error.set(arguments?.firstOrNull() as? Int ?: -1)
                    else -> return@proxy
                }
                answered.countDown()
            }
        stopMethod.invoke(manager, request(interfaceName), callbackExecutor, callback)
        if (!answered.await(timeoutMs, TimeUnit.MILLISECONDS)) return null
        return error.get()
    }

    override fun takeBootlegDropped(): Boolean = bootlegDropped.getAndSet(false)

    override fun tethered(): Map<String, Int> {
        check(firstReport.await(FIRST_REPORT_WAIT_MS_MAX, TimeUnit.MILLISECONDS)) {
            "Android hasn't reported the tethering state within ${FIRST_REPORT_WAIT_MS_MAX}ms"
        }
        return checkNotNull(tetheredInterfaces.get())
    }

    private fun request(interfaceName: String): Any {
        val builder = builderClass.getConstructor(Int::class.javaPrimitiveType).newInstance(TetherPolicy.TETHERING_VIRTUAL)
        builderClass.getMethod("setInterfaceName", String::class.java).invoke(builder, interfaceName)
        return checkNotNull(builderClass.getMethod("build").invoke(builder))
    }

    /** `onTetheredInterfacesChanged` comes as a `Set<TetheringInterface>` (type + name); older releases send names only (type unknown, -1). */
    private fun onTethered(argument: Any?) {
        val elements = (argument as? Collection<*>).orEmpty().filterNotNull()
        val previous =
            tetheredInterfaces.getAndSet(
                elements.associate { element ->
                    when (element) {
                        is String -> element to -1
                        else ->
                            element.javaClass.getMethod("getInterface").invoke(element) as String to
                                element.javaClass.getMethod("getType").invoke(element) as Int
                    }
                },
            )
        val now = checkNotNull(tetheredInterfaces.get()).keys
        if (previous.orEmpty().keys.any { TetherPolicy.isBootlegInterface(it) && it !in now }) bootlegDropped.set(true)
        firstReport.countDown()
        onChanged()
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

    companion object {
        private const val TAG = "BootlegTether"
        private const val TETHERING_SERVICE = "tethering"
        private const val MANAGER_CLASS = "android.net.TetheringManager"

        /** Only builds a request to find its class; never sent. */
        private const val GADGET_INTERFACE_PROBE = "bootleg_probe"

        /** Android replays the state as soon as the callback is registered (≈ 1ms); this bounds a stuck service. */
        private const val FIRST_REPORT_WAIT_MS_MAX = 2_000L

        /** Null when this Android can't tether an interface by name (no `TetheringRequest` API). */
        fun create(
            context: Context,
            onChanged: () -> Unit,
        ): RootTetherPort? =
            try {
                RootTetherPort(context, onChanged)
            } catch (e: ReflectiveOperationException) {
                Log.i(TAG, "tethering an interface by name isn't available: USB tethering is unsupported", e)
                null
            } catch (e: IllegalStateException) {
                Log.i(TAG, "no tethering service: USB tethering is unsupported", e)
                null
            }
    }
}

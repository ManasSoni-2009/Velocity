package com.velocity.app.nativebridge

import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Modifier

class VelocityJniContractTest {

    @Test
    fun testNativeMethodsPresentWithExactSignatures() {
        val clazz = VelocityJni::class.java
        val methods = clazz.declaredMethods

        val expectedMethods = mapOf(
            "nativeInit" to listOf(Int::class.javaPrimitiveType),
            "nativeStartTunnel" to listOf(
                Int::class.javaPrimitiveType,
                String::class.java,
                VelocityJni.TelemetryCallback::class.java,
            ),
            "nativeRegisterSocket" to listOf(
                Long::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                String::class.java,
                Boolean::class.javaPrimitiveType,
            ),
            "nativeUnregisterSocket" to listOf(
                Long::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            ),
            "nativeUpdateLinkMetrics" to listOf(
                Long::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            ),
            "nativeSetMode" to listOf(
                Long::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            ),
            "nativeUpdateConfig" to listOf(
                Long::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
            ),
            "nativeGetTelemetryJson" to listOf(Long::class.javaPrimitiveType),
            "nativeProbeServerJson" to listOf(
                String::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            ),
            "nativeRunSpeedTestJson" to listOf(String::class.java),
            "nativeRunMultipathSpeedTestJson" to listOf(
                String::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            ),
            "nativeStopTunnel" to listOf(Long::class.javaPrimitiveType),
        )

        for ((name, paramTypes) in expectedMethods) {
            val matching = methods.firstOrNull { it.name == name }
            assertNotNull("Method $name must exist on VelocityJni", matching)
            assertTrue("Method $name must be native", Modifier.isNative(matching!!.modifiers))
            assertEquals(
                "Method $name parameter count must match",
                paramTypes.size,
                matching.parameterTypes.size,
            )
            for (i in paramTypes.indices) {
                assertEquals(
                    "Method $name parameter $i type mismatch",
                    paramTypes[i],
                    matching.parameterTypes[i],
                )
            }
        }
    }

    @Test
    fun testTelemetryCallbackInterfaceContract() {
        val callbackClass = VelocityJni.TelemetryCallback::class.java
        assertTrue("TelemetryCallback must be an interface", callbackClass.isInterface)

        val onTelemetryUpdate = callbackClass.getMethod("onTelemetryUpdate", String::class.java)
        assertNotNull(onTelemetryUpdate)

        val onStateChanged = callbackClass.getMethod(
            "onStateChanged",
            Int::class.javaPrimitiveType,
            String::class.java,
        )
        assertNotNull(onStateChanged)
    }
}

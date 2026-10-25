package com.tg.ghostd

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Regression tests for the transparent, owner-authorized FGS lifecycle:
 * specialUse (not dataSync) for the persistent management socket, a declared
 * special-use subtype, mediaProjection only promoted per capture, and the
 * numeric version_code in device registration.
 */
class FgsLifecycleTest {

    private val androidNs = "http://schemas.android.com/apk/res/android"

    private fun manifestElement(): Element {
        val candidates = listOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml"),
            File("client-main/app/src/main/AndroidManifest.xml")
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: throw AssertionError(
                "AndroidManifest.xml not found; cwd=${File(".").absolutePath}"
            )
        val doc = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(file)
        return doc.documentElement
    }

    private fun ghostService(manifest: Element): Element {
        val services = manifest.getElementsByTagName("service")
        for (i in 0 until services.length) {
            val el = services.item(i) as Element
            if (el.getAttributeNS(androidNs, "name") == ".GhostService") return el
        }
        throw AssertionError("GhostService declaration not found in manifest")
    }

    @Test fun persistentServiceUsesSpecialUseNotDataSync() {
        val type = ghostService(manifestElement())
            .getAttributeNS(androidNs, "foregroundServiceType")
        assertTrue("expected specialUse in '$type'", type.contains("specialUse"))
        assertTrue("expected mediaProjection in '$type'", type.contains("mediaProjection"))
        assertFalse("dataSync must not be the persistent type: '$type'", type.contains("dataSync"))
    }

    @Test fun specialUseSubtypePropertyIsDeclared() {
        val service = ghostService(manifestElement())
        val properties = service.getElementsByTagName("property")
        var subtype: String? = null
        for (i in 0 until properties.length) {
            val el = properties.item(i) as Element
            if (el.getAttributeNS(androidNs, "name") ==
                "android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
            ) {
                subtype = el.getAttributeNS(androidNs, "value")
            }
        }
        assertNotNull("special-use FGS subtype property is required on 14+", subtype)
        assertTrue(
            "subtype must describe the real use case: '$subtype'",
            subtype!!.contains("owner-authorized") &&
                subtype.contains("support") &&
                subtype.contains("management")
        )
    }

    @Test fun manifestDeclaresSpecialUsePermissionAndNotDataSync() {
        val manifest = manifestElement()
        val names = mutableSetOf<String>()
        val permissions = manifest.getElementsByTagName("uses-permission")
        for (i in 0 until permissions.length) {
            names += (permissions.item(i) as Element).getAttributeNS(androidNs, "name")
        }
        assertTrue(names.contains("android.permission.FOREGROUND_SERVICE_SPECIAL_USE"))
        assertFalse(names.contains("android.permission.FOREGROUND_SERVICE_DATA_SYNC"))
    }

    @Test fun pre34HasNoTypedBaseAnd34UsesSpecialUse() {
        assertNull(GhostService.baseForegroundServiceType(29))
        assertNull(GhostService.baseForegroundServiceType(33))
        assertEquals(0x40000000, GhostService.baseForegroundServiceType(34))
        assertEquals(0x40000000, GhostService.baseForegroundServiceType(36))
    }

    @Test fun projectionPromotionKeepsBaseOnlyOn34Plus() {
        // API 29-33: mediaProjection alone (no specialUse constant there).
        assertEquals(0x20, GhostService.projectionForegroundServiceType(29))
        assertEquals(0x20, GhostService.projectionForegroundServiceType(33))
        // API 34+: specialUse | mediaProjection.
        assertEquals(0x40000020, GhostService.projectionForegroundServiceType(34))
        assertEquals(0x40000020, GhostService.projectionForegroundServiceType(36))
    }

    @Test fun api34TimeoutOverloadDelegatesToSameTeardown() {
        // Android 14 calls onTimeout(startId); Android 15+ calls
        // onTimeout(startId, fgsType). Both must be overridden so neither
        // falls through to the default process-killing behavior.
        val oneArg = GhostService::class.java.getDeclaredMethod("onTimeout", Int::class.java)
        val twoArg = GhostService::class.java.getDeclaredMethod(
            "onTimeout", Int::class.java, Int::class.java
        )
        assertEquals(Void.TYPE, oneArg.returnType)
        assertEquals(Void.TYPE, twoArg.returnType)
        // Both delegate to one private handler rather than duplicating the
        // clean teardown (running flag, heartbeat, worker, stopSelf).
        val handler = GhostService::class.java.getDeclaredMethod(
            "handleForegroundTimeout", Int::class.java, Int::class.java
        )
        assertTrue(
            "shared timeout teardown must stay private",
            java.lang.reflect.Modifier.isPrivate(handler.modifiers)
        )
    }

    @Test fun captureCanDemoteToBaseServiceType() {
        GhostService::class.java.getDeclaredMethod("demoteToBase")
    }

    @Test fun helloCarriesNumericVersionCode() {
        val msg = JSONObject(
            GhostService.buildHelloMessage(
                "dev-1", "Pixel 10a", "15", 35, "0.4.0", 22L, 88, "sekret", "tok"
            )
        )
        assertEquals("hello", msg.getString("type"))
        assertEquals("0.4.0", msg.getString("app_version"))
        assertEquals(22L, msg.getLong("version_code"))
        assertEquals("tok", msg.getString("token"))
    }

    @Test fun helloVersionCodeSupportsLongValuesAndOmitsAbsentToken() {
        val code = 0x1_0000_0001L  // > Int.MAX_VALUE: must not be truncated
        val msg = JSONObject(
            GhostService.buildHelloMessage(
                "dev-1", "Pixel 10a", "15", 35, "0.4.0", code, 88, "sekret", null
            )
        )
        assertEquals(code, msg.getLong("version_code"))
        assertFalse(msg.has("token"))
    }
}

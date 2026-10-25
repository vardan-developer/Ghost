package com.tg.ghosta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandRouterTest {

    private class FakeDriver : UiDriver {
        val calls = ArrayList<String>()
        private fun rec(s: String): Map<String, Any?> {
            calls.add(s)
            return CommandRouter.ok(mapOf("echo" to s))
        }

        override fun dump() = rec("dump")
        override fun find(text: String?, id: String?) = rec("find:$text:$id")
        override fun tap(x: Int?, y: Int?, id: String?) = rec("tap:$x:$y:$id")
        override fun longClick(x: Int?, y: Int?, id: String?) = rec("long:$x:$y:$id")
        override fun type(text: String, id: String?) = rec("type:$text:$id")
        override fun key(code: Int) = rec("key:$code")
        override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, duration: Long) =
            rec("swipe:$x1,$y1,$x2,$y2,$duration")
        override fun unlock(pin: String) = rec("unlock:$pin")
        override fun setArmed(on: Boolean) = rec("armed:$on")
        override fun status() = rec("status")
    }

    private val driver = FakeDriver()

    private fun handle(line: String) = CommandRouter.handle(line, driver)

    @Test
    fun `routes verbs with json number coercion`() {
        handle("""{"cmd":"tap","x":540,"y":1800}""")
        handle("""{"cmd":"tap","id":"root/0/3"}""")
        handle("""{"cmd":"swipe","x1":0,"y1":1,"x2":2,"y2":3,"duration":250}""")
        handle("""{"cmd":"key","code":66}""")
        handle("""{"cmd":"type","text":"hi there","id":"root/1"}""")
        handle("""{"cmd":"find","text":"Allow"}""")
        handle("""{"cmd":"find","id":"pin_entry"}""")
        handle("""{"cmd":"unlock","pin":"1234"}""")
        handle("""{"cmd":"arm"}""")
        handle("""{"cmd":"disarm"}""")
        handle("""{"cmd":"dump"}""")
        handle("""{"cmd":"status"}""")
        handle("""{"cmd":"ping"}""")
        assertEquals(
            listOf(
                "tap:540:1800:null",
                "tap:null:null:root/0/3",
                "swipe:0,1,2,3,250",
                "key:66",
                "type:hi there:root/1",
                "find:Allow:null",
                "find:null:pin_entry",
                "unlock:1234",
                "armed:true",
                "armed:false",
                "dump",
                "status"
            ),
            driver.calls
        )
    }

    @Test
    fun `bad json yields error response`() {
        val r = handle("not json at all")
        assertTrue(r.startsWith("""{"ok":false,"error":"bad json:"""))
    }

    @Test
    fun `missing and unknown cmds yield errors`() {
        assertEquals("""{"ok":false,"error":"missing cmd"}""", handle("""{"x":1}"""))
        assertEquals("""{"ok":false,"error":"unknown cmd: nope"}""", handle("""{"cmd":"nope"}"""))
    }

    @Test
    fun `missing required fields yield errors`() {
        val r = handle("""{"cmd":"type"}""")
        assertEquals("""{"ok":false,"error":"missing text"}""", r)
        val r2 = handle("""{"cmd":"key"}""")
        assertEquals("""{"ok":false,"error":"missing code"}""", r2)
    }

    @Test
    fun `ping responds ok`() {
        assertEquals("""{"ok":true,"pong":true}""", handle("""{"cmd":"ping"}"""))
    }

    @Test
    fun `cmd is case-insensitive`() {
        handle("""{"cmd":"DUMP"}""")
        assertTrue(driver.calls.contains("dump"))
    }
}

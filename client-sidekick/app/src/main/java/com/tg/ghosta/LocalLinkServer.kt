package com.tg.ghosta

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Local link: plain TCP server on 127.0.0.1:7777 for the main app (ghostd).
 *
 * Protocol (newline-delimited JSON, one request per line, one response per
 * line):
 *   1. first line MUST be {"auth":"<SECRET>"} — wrong secret closes the socket
 *   2. then any number of command lines (see [CommandRouter])
 *
 * Loopback only — no internet exposure, no INTERNET permission needed.
 */
class LocalLinkServer(private val driver: UiDriver) {

    companion object {
        /** Shared secret baked in at build; ghostd must send the same value. */
        const val SECRET = "gh0stA-9fXq2Lm7Pz4t"
        const val PORT = 7777
    }

    @Volatile private var running = false
    private var server: ServerSocket? = null

    fun start() {
        if (running) return
        running = true
        Thread({
            try {
                val ss = ServerSocket(PORT, 8, InetAddress.getByName("127.0.0.1"))
                server = ss
                while (running) {
                    val client = try {
                        ss.accept()
                    } catch (e: Exception) {
                        if (running) continue else break
                    }
                    Thread({ handle(client) }, "ghosta-link").start()
                }
            } catch (_: Exception) {
                // port taken or socket closed — service restart rebinds anyway
            } finally {
                server = null
            }
        }, "ghosta-link-accept").start()
    }

    fun stop() {
        running = false
        try {
            server?.close()
        } catch (_: Exception) {
        }
        server = null
    }

    private fun handle(client: Socket) {
        try {
            client.tcpNoDelay = true
            val input = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
            val output = OutputStreamWriter(client.getOutputStream(), Charsets.UTF_8)

            // --- auth handshake ---
            val authLine = input.readLine() ?: return
            val authed = try {
                @Suppress("UNCHECKED_CAST")
                (JsonParser.parse(authLine) as? Map<String, Any?>)
                    ?.get("auth") == SECRET
            } catch (_: Exception) {
                false
            }
            if (!authed) {
                output.write(Json.stringify(CommandRouter.err("auth failed")) + "\n")
                output.flush()
                return
            }
            output.write(Json.stringify(CommandRouter.ok(mapOf("auth" to true))) + "\n")
            output.flush()

            // --- command loop ---
            while (running) {
                val line = input.readLine() ?: break
                if (line.isBlank()) continue
                val response = CommandRouter.handle(line, driver)
                output.write(response + "\n")
                output.flush()
            }
        } catch (_: Exception) {
            // client dropped — fine
        } finally {
            try {
                client.close()
            } catch (_: Exception) {
            }
        }
    }
}

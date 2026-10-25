package com.tg.ghostd

import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * WSS trust policy.
 *
 * Publicly trusted certificates are accepted through the platform trust store.
 * A self-signed device-listener certificate is accepted only when its
 * SubjectPublicKeyInfo SHA-256 matches a pinned value. There is deliberately
 * no "trust all" mode and no plaintext fallback.
 */
object TlsPinning {
    fun spkiSha256(publicKeyEncoded: ByteArray): String =
        Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(publicKeyEncoded)
        )

    fun createContext(pins: List<String> = C2.TLS_SPKI_SHA256): SSLContext {
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf(trustManager(pins)), SecureRandom())
        return context
    }

    fun trustManager(pins: List<String> = C2.TLS_SPKI_SHA256): X509TrustManager =
        PinnedTrustManager(systemTrustManager(), pins.filter { it.isNotBlank() })

    private fun systemTrustManager(): X509TrustManager {
        val factory = TrustManagerFactory.getInstance(
            TrustManagerFactory.getDefaultAlgorithm()
        )
        factory.init(null as KeyStore?)
        return factory.trustManagers
            .filterIsInstance<X509TrustManager>()
            .firstOrNull()
            ?: throw IllegalStateException("no platform X509 trust manager")
    }

    private class PinnedTrustManager(
        private val system: X509TrustManager,
        private val pins: List<String>
    ) : X509TrustManager {

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
            // The device is the TLS client; the server never asks for a
            // client certificate.
        }

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            if (chain.isEmpty()) {
                throw CertificateException("empty TLS certificate chain")
            }
            try {
                system.checkServerTrusted(chain, authType)
                return
            } catch (_: CertificateException) {
                // Self-signed/private-CA listener: fall through to the pin.
            }
            if (pins.isEmpty()) {
                throw CertificateException("C2 TLS certificate is not trusted")
            }
            val leaf = chain[0]
            leaf.checkValidity()
            val actual = spkiSha256(leaf.publicKey.encoded)
            if (pins.none { pinMatches(it, actual) }) {
                throw CertificateException("C2 TLS certificate pin mismatch")
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> =
            system.acceptedIssuers
    }

    private fun pinMatches(expected: String, actual: String): Boolean =
        MessageDigest.isEqual(
            expected.toByteArray(Charsets.UTF_8),
            actual.toByteArray(Charsets.UTF_8)
        )
}

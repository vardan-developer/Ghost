package com.tg.ghostd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

class TlsPinningTest {

    private fun fixtureCert(): X509Certificate {
        val pem = javaClass.getResourceAsStream("/device-tls.crt")!!
            .use { it.readBytes() }
        return CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(pem)) as X509Certificate
    }

    @Test fun configuredPinMatchesListenerCertificate() {
        val cert = fixtureCert()
        assertEquals(
            C2.TLS_SPKI_SHA256.single(),
            TlsPinning.spkiSha256(cert.publicKey.encoded)
        )
    }

    @Test fun pinnedSelfSignedCertificateIsAccepted() {
        val cert = fixtureCert()
        TlsPinning.trustManager(C2.TLS_SPKI_SHA256)
            .checkServerTrusted(arrayOf(cert), "RSA")
    }

    @Test fun unrelatedSelfSignedCertificateIsRejected() {
        val cert = fixtureCert()
        val wrong = TlsPinning.trustManager(listOf("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="))
        assertThrows(CertificateException::class.java) {
            wrong.checkServerTrusted(arrayOf(cert), "RSA")
        }
    }

    @Test fun emptyPinListRejectsSelfSignedCertificate() {
        val cert = fixtureCert()
        assertThrows(CertificateException::class.java) {
            TlsPinning.trustManager(emptyList())
                .checkServerTrusted(arrayOf(cert), "RSA")
        }
    }
}

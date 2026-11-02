package com.naminfo.core

import android.content.Context
import com.naminfo.R
import org.linphone.core.tools.Log
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Adds the current Mobion intermediate CA as a fallback trust anchor for Android versions
 * that predate its public root. The platform trust manager remains first and hostname
 * validation is deliberately left untouched.
 */
object MobionTlsCompatibility {
    private const val TAG = "[Mobion TLS Compatibility]"

    @JvmStatic
    fun install(context: Context) {
        try {
            val systemTrustManager = trustManagerFor(null)
            val compatibilityKeyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null)
                val certificate = context.resources.openRawResource(R.raw.certera_rsa_dv_ssl_ca_2).use {
                    CertificateFactory.getInstance("X.509").generateCertificate(it)
                }
                setCertificateEntry("mobion-certera-ca-2", certificate)
            }
            val compatibilityTrustManager = trustManagerFor(compatibilityKeyStore)
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(
                null,
                arrayOf(FallbackTrustManager(systemTrustManager, compatibilityTrustManager)),
                SecureRandom()
            )

            // Keep HttpsURLConnection's default hostname verifier: the certificate must
            // still match the requested domain (for example mobionglobal.com).
            HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.socketFactory)
            Log.i("$TAG Installed Android legacy trust-chain compatibility")
        } catch (exception: Exception) {
            // Never weaken the platform policy if the compatibility setup itself fails.
            Log.e("$TAG Couldn't install compatibility trust manager: $exception")
        }
    }

    private fun trustManagerFor(keyStore: KeyStore?): X509TrustManager {
        return TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).run {
            init(keyStore)
            trustManagers.filterIsInstance<X509TrustManager>().first()
        }
    }

    private class FallbackTrustManager(
        private vararg val delegates: X509TrustManager
    ) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
            delegates.first().checkClientTrusted(chain, authType)
        }

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            var lastFailure: CertificateException? = null
            for (delegate in delegates) {
                try {
                    delegate.checkServerTrusted(chain, authType)
                    return
                } catch (exception: CertificateException) {
                    lastFailure = exception
                }
            }
            throw lastFailure ?: CertificateException("No trust manager accepted server certificate")
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> =
            delegates.flatMap { it.acceptedIssuers.asList() }.toTypedArray()
    }
}

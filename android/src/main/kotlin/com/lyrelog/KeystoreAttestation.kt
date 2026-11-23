package com.lyrelog

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import com.lyrelog.core.AttestationKeyInvalid
import com.lyrelog.core.AttestationProvider
import com.lyrelog.core.AttestationUnsupported
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.ProviderException
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * The install's Key Attestation key in the Android Keystore: P-256, signing only, in StrongBox
 * when the device has one, else the TEE. Its attestation carries the server's challenge; the
 * chain (leaf first) goes to the server once, then the key signs fresh challenges to renew the
 * token. The key never leaves the secure hardware and isn't in backups.
 */
internal class KeystoreAttestation(private val context: Context) : AttestationProvider {
    private fun keyStore(): KeyStore = KeyStore.getInstance(STORE).apply { load(null) }

    override fun createKey(challenge: ByteArray): List<ByteArray> {
        val strongBox = Build.VERSION.SDK_INT >= 28 &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
        try {
            generate(challenge, strongBox)
        } catch (e: Exception) {
            if (strongBox && (Build.VERSION.SDK_INT >= 28 && e is StrongBoxUnavailableException || e is ProviderException)) {
                generate(challenge, false)
            } else {
                throw wrap(e)
            }
        }
        val chain = try {
            keyStore().getCertificateChain(ALIAS)
        } catch (e: Exception) {
            throw wrap(e)
        }
        if (chain == null || chain.size < 2) throw AttestationUnsupported("no attestation chain")
        return chain.map { it.encoded }
    }

    private fun generate(challenge: ByteArray, strongBox: Boolean) {
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setAttestationChallenge(challenge)
            .apply { if (strongBox && Build.VERSION.SDK_INT >= 28) setIsStrongBoxBacked(true) }
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, STORE).apply {
            initialize(spec)
            generateKeyPair()
        }
    }

    override fun hasKey(): Boolean = try {
        keyStore().containsAlias(ALIAS)
    } catch (e: Exception) {
        false
    }

    override fun sign(data: ByteArray): ByteArray {
        val key = try {
            keyStore().getKey(ALIAS, null) as? PrivateKey
        } catch (e: Exception) {
            null
        } ?: throw AttestationKeyInvalid("no key")
        return try {
            Signature.getInstance("SHA256withECDSA").run {
                initSign(key)
                update(data)
                sign()
            }
        } catch (e: java.security.InvalidKeyException) {
            throw AttestationKeyInvalid(e.javaClass.name)
        }
    }

    override fun deleteKey() {
        try {
            keyStore().deleteEntry(ALIAS)
        } catch (e: Exception) {
            // nothing to delete
        }
    }

    private fun wrap(e: Exception): Exception =
        if (e is java.security.NoSuchAlgorithmException || e is java.security.NoSuchProviderException ||
            e is java.security.InvalidAlgorithmParameterException
        ) {
            AttestationUnsupported(e.javaClass.name)
        } else {
            e
        }

    companion object {
        const val STORE = "AndroidKeyStore"
        const val ALIAS = "com.lyrelog.attestation"
    }
}

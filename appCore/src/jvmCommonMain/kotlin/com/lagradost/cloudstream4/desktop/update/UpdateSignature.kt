package com.lagradost.cloudstream4.desktop.update

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Desktop updates are checked against a signature made with the project's own key, not only the
 * checksum GitHub lists next to the file: someone who could change a release (a leaked token, a
 * broken build) could change both the file and its checksum, but not sign it. Each installer comes
 * with a ".sig" file made by scripts/SignRelease.java. Android needs none: Android itself only
 * installs an update signed with the same key as the installed app.
 */
object UpdateSignature {
    /**
     * The release key's public half (Ed25519, X.509, base64), from `java scripts/SignRelease.java generate`.
     * Empty until a key is set up: then releases aren't signed and nothing is checked.
     */
    const val PUBLIC_KEY = ""

    val required: Boolean get() = PUBLIC_KEY.isNotBlank()

    /** What is signed: the file's name (so one platform's file can't stand in for another's) and its SHA-256. */
    fun message(name: String, sha256: String): ByteArray = "hyperstream-update-v1\n$name\n${sha256.lowercase()}\n".toByteArray()

    fun verify(name: String, sha256: String, signature: String, publicKey: String = PUBLIC_KEY): Boolean = runCatching {
        val key = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKey.trim())))
        Signature.getInstance("Ed25519").run {
            initVerify(key)
            update(message(name, sha256))
            verify(Base64.getDecoder().decode(signature.trim()))
        }
    }.getOrDefault(false)
}

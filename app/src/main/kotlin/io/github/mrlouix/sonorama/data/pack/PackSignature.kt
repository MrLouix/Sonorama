package io.github.mrlouix.sonorama.data.pack

import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** Niveau de confiance d'un pack, déduit de sa signature. */
enum class PackTrust { UNSIGNED, SIGNED, OFFICIAL }

/** Ce qu'il faut signaler quand la clé d'un pack importé ne correspond pas à celle déjà connue. */
enum class KeyWarning { KEY_CHANGED, SIGNATURE_DROPPED }

/** Ce que l'appli sait déjà d'un pack portant le même identifiant. */
data class KnownPack(val version: Int, val installed: Boolean, val signerKey: String?)

sealed interface ImportDecision {
    /** Pack inconnu, ou réinstallation sans changement de clé : installer sans question. */
    data object Install : ImportDecision

    /** Demander confirmation, en signalant [warning] le cas échéant. */
    data class Confirm(val warning: KeyWarning?) : ImportDecision

    data class Refuse(val error: PackError) : ImportDecision
}

/**
 * Signature des packs : ECDSA P-256 avec SHA-256 sur les octets de `manifest.json`.
 * Le manifeste portant l'empreinte de chaque fichier audio, la signature couvre tout le pack.
 */
object PackSignature {
    /**
     * Empreintes (SHA-256 de la clé publique encodée en DER, en hexadécimal) des clés de l'équipe
     * Sonorama. Un pack signé par l'une d'elles est présenté comme « officiel ».
     * La marche à suivre pour créer la clé et renseigner cette liste est dans `docs/format-pack.md`.
     */
    val OFFICIAL_KEYS: Set<String> = emptySet()

    fun sha256Hex(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    /** Lit une clé publique P-256 au format PEM (`-----BEGIN PUBLIC KEY-----`). */
    fun parsePublicKey(pem: String): PublicKey {
        try {
            val body = pem.lineSequence().filterNot { it.startsWith("-----") }.joinToString("").filterNot { it.isWhitespace() }
            val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(body)))
            val bits = (key as java.security.interfaces.ECPublicKey).params.order.bitLength()
            if (bits != 256) throw PackException(PackError.SIGNATURE_INVALID, "courbe de $bits bits")
            return key
        } catch (e: PackException) {
            throw e
        } catch (e: Exception) {
            throw PackException(PackError.SIGNATURE_INVALID, SIGNING_KEY_NAME)
        }
    }

    /** Empreinte qui identifie une clé : c'est elle que l'appli mémorise au premier import. */
    fun fingerprint(key: PublicKey): String = sha256Hex(key.encoded)

    /**
     * Vérifie [signatureBase64] sur [manifestBytes] avec la clé [keyPem].
     * Renvoie l'empreinte de la clé, ou lève [PackError.SIGNATURE_INVALID].
     */
    fun verify(manifestBytes: ByteArray, signatureBase64: String, keyPem: String): String {
        val key = parsePublicKey(keyPem)
        val valid = try {
            val signature = Base64.getMimeDecoder().decode(signatureBase64.trim())
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(key)
                update(manifestBytes)
                verify(signature)
            }
        } catch (e: Exception) {
            false
        }
        if (!valid) throw PackException(PackError.SIGNATURE_INVALID)
        return fingerprint(key)
    }

    fun trustOf(signerKey: String?, officialKeys: Set<String> = OFFICIAL_KEYS): PackTrust = when (signerKey) {
        null -> PackTrust.UNSIGNED
        in officialKeys -> PackTrust.OFFICIAL
        else -> PackTrust.SIGNED
    }

    /**
     * Règle d'import d'un pack dont l'identifiant est peut-être déjà connu.
     *
     * - Toute mise à jour d'un pack installé demande confirmation.
     * - La clé mémorisée au premier import fait foi : une autre clé est signalée, et un pack
     *   non signé ne peut pas remplacer un pack signé encore installé.
     */
    fun decide(known: KnownPack?, incomingKey: String?): ImportDecision {
        if (known == null) return ImportDecision.Install
        val warning = when {
            known.signerKey == null || known.signerKey == incomingKey -> null
            incomingKey == null -> KeyWarning.SIGNATURE_DROPPED
            else -> KeyWarning.KEY_CHANGED
        }
        return when {
            known.installed && warning == KeyWarning.SIGNATURE_DROPPED -> ImportDecision.Refuse(PackError.SIGNATURE_REQUIRED)
            !known.installed && warning == null -> ImportDecision.Install
            else -> ImportDecision.Confirm(warning)
        }
    }

    private const val SIGNING_KEY_NAME = PackFormat.SIGNING_KEY
}

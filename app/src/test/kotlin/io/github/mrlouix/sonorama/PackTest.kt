package io.github.mrlouix.sonorama

import io.github.mrlouix.sonorama.audio.VolumeCurve
import io.github.mrlouix.sonorama.data.PageExchange
import io.github.mrlouix.sonorama.data.pack.ExtractedPack
import io.github.mrlouix.sonorama.data.pack.ImportDecision
import io.github.mrlouix.sonorama.data.pack.KeyWarning
import io.github.mrlouix.sonorama.data.pack.KnownPack
import io.github.mrlouix.sonorama.data.pack.PackArchive
import io.github.mrlouix.sonorama.data.pack.PackSignature
import io.github.mrlouix.sonorama.data.pack.PackTrust
import io.github.mrlouix.sonorama.data.pack.PackError
import io.github.mrlouix.sonorama.data.pack.PackException
import io.github.mrlouix.sonorama.data.pack.PackFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PackTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val ogg = "OggS".toByteArray() + ByteArray(64)

    private fun manifest(
        format: Int = 1,
        id: String = "demo.pack",
        file: String = "audio/rain.ogg",
        license: String = "CC0-1.0",
        page: String = "meteo",
        extra: String = "",
    ) = """
        {"format": $format, "id": "$id", "version": 2, "name": {"fr": "Démo", "en": "Demo"},
         "pages": [{"id": "maison", "name": {"fr": "Maison", "en": "Home"}, "symbol": "home"}],
         "sounds": [{"id": "rain", "file": "$file", "name": {"fr": "Pluie", "en": "Rain"},
                     "page": "$page", "license": "$license", "futureField": true$extra}]}
    """.trimIndent()

    private val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    private fun pem(key: PublicKey) =
        "-----BEGIN PUBLIC KEY-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(key.encoded) +
            "\n-----END PUBLIC KEY-----\n"

    private fun sign(manifest: ByteArray, key: PrivateKey = keys.private): ByteArray {
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(key)
            update(manifest)
            sign()
        }
        return Base64.getEncoder().encode(signature)
    }

    private fun hashOf(bytes: ByteArray) = PackSignature.sha256Hex(bytes)

    /** Archive signée : manifeste de format 2 avec l'empreinte du son, signature et clé publique. */
    private fun signedZip(audio: ByteArray = ogg, declaredHash: String? = hashOf(ogg), tamper: (ByteArray) -> ByteArray = { it }): ByteArray {
        val extra = declaredHash?.let { """, "sha256": "$it"""" }.orEmpty()
        val manifest = manifest(format = 2, extra = extra).toByteArray()
        return zip(
            "manifest.json" to tamper(manifest),
            "manifest.sig" to sign(manifest),
            "signing-key.pub" to pem(keys.public).toByteArray(),
            "audio/rain.ogg" to audio,
        )
    }

    private fun extract(archive: ByteArray, folder: String): ExtractedPack =
        PackArchive.extract(PackArchive.readManifest { archive.inputStream() }, { archive.inputStream() }, temp.newFolder(folder))

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { out ->
            for ((name, data) in entries) {
                out.putNextEntry(ZipEntry(name))
                out.write(data)
                out.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun errorOf(block: () -> Unit): PackError = assertThrows(PackException::class.java) { block() }.error

    private fun validate(text: String) = PackFormat.validate(PackFormat.parse(text))

    @Test
    fun `a valid manifest is accepted and unknown fields are ignored`() {
        val parsed = PackFormat.parse(manifest())
        PackFormat.validate(parsed)
        assertEquals("demo.pack", parsed.id)
        assertEquals(70, parsed.sounds.single().defaultVolume)
    }

    @Test
    fun `invalid manifests are refused with a precise reason`() {
        assertEquals(PackError.MANIFEST_UNREADABLE, errorOf { PackFormat.parse("{ not json") })
        assertEquals(PackError.UNKNOWN_FORMAT, errorOf { validate(manifest(format = 9)) })
        assertEquals(PackError.INVALID_ID, errorOf { validate(manifest(id = "Bad Id")) })
        assertEquals(PackError.UNSAFE_PATH, errorOf { validate(manifest(file = "../evil.ogg")) })
        assertEquals(PackError.UNSUPPORTED_AUDIO, errorOf { validate(manifest(file = "audio/rain.mp3")) })
        assertEquals(PackError.UNKNOWN_PAGE, errorOf { validate(manifest(page = "nowhere")) })
        assertEquals(PackError.FORBIDDEN_LICENSE, errorOf { validate(manifest(license = "CC-BY-NC-4.0")) })
        assertEquals(PackError.FIELD_TOO_LONG, errorOf { validate(manifest(extra = """, "symbol": "${"x".repeat(65)}"""")) })
        assertEquals(PackError.FIELD_TOO_LONG, errorOf { validate(manifest(extra = """, "source": "${"x".repeat(501)}"""")) })
        assertEquals(PackError.HASH_MISMATCH, errorOf { validate(manifest(extra = """, "sha256": "nope"""")) })
        validate(manifest(format = 2))
    }

    @Test
    fun `voice keywords are optional, read per language and bounded`() {
        val parsed = PackFormat.parse(manifest(extra = """, "keywords": {"fr": ["pluie", "il pleut"], "en": ["rain"]}"""))
        PackFormat.validate(parsed)
        assertEquals(listOf("pluie", "il pleut"), parsed.sounds.single().keywords["fr"])
        assertEquals(PackError.FIELD_TOO_LONG, errorOf { validate(manifest(extra = """, "keywords": {"fr": ["${"x".repeat(41)}"]}""")) })
        assertEquals(PackError.FIELD_TOO_LONG, errorOf { validate(manifest(extra = """, "keywords": {"French": ["pluie"]}""")) })
        val many = (1..31).joinToString(",") { "\"w$it\"" }
        assertEquals(PackError.FIELD_TOO_LONG, errorOf { validate(manifest(extra = """, "keywords": {"fr": [$many]}""")) })
    }

    @Test
    fun `archive paths are checked`() {
        assertTrue(PackFormat.isSafePath("audio/rain.ogg"))
        assertTrue(PackFormat.isSafePath("rain.ogg"))
        for (bad in listOf("", "/abs.ogg", "../up.ogg", "a/../b.ogg", "a//b.ogg", "a\\b.ogg", "C:/x.ogg", "./x.ogg")) {
            assertFalse(bad, PackFormat.isSafePath(bad))
        }
    }

    @Test
    fun `only free licences are allowed`() {
        for (ok in listOf("CC0", "CC0-1.0", "Public-Domain", "CC-BY", "CC-BY-4.0", "CC BY 4.0", "CC-BY-SA-4.0", "cc-by-3.0", "CC-BY-3.0-FR")) {
            assertTrue(ok, PackFormat.isLicenseAllowed(ok))
        }
        val bad = listOf(
            "", "CC-BY-NC-4.0", "CC-BY-ND-4.0", "CC-BY-NC-SA-4.0", "Proprietary", "MIT",
            "CC-BY-NonCommercial-4.0", "CC-BY-NoDerivs", "CC-BYNC", "CC-BY-NC", "CC-BY-4.0-NC", "CC0-but-not-really",
        )
        for (bad in bad) {
            assertFalse(bad, PackFormat.isLicenseAllowed(bad))
        }
    }

    @Test
    fun `extraction copies declared files only`() {
        val archive = zip(
            "audio/rain.ogg" to ogg,
            "manifest.json" to manifest().toByteArray(),
            "LICENSES.md" to "CC0".toByteArray(),
            "extra/script.sh" to "rm -rf".toByteArray(),
        )
        val parsed = PackArchive.readManifest { archive.inputStream() }
        val target = temp.newFolder("out")
        val progress = ArrayList<Float>()
        val extracted = PackArchive.extract(parsed, { archive.inputStream() }, target, onProgress = { progress += it })
        assertNull(extracted.signerKey)
        assertEquals(hashOf(archive), extracted.archiveSha256)
        assertTrue(File(target, "audio/rain.ogg").isFile)
        assertTrue(File(target, "LICENSES.md").isFile)
        assertFalse(File(target, "extra").exists())
        assertFalse(File(target, "manifest.json").exists())
        assertEquals(listOf(1f), progress)
    }

    @Test
    fun `archives are refused with a precise reason`() {
        assertEquals(PackError.NOT_A_ZIP, errorOf { PackArchive.readManifest { "plain text".byteInputStream() } })
        val noManifest = zip("audio/rain.ogg" to ogg)
        assertEquals(PackError.MANIFEST_MISSING, errorOf { PackArchive.readManifest { noManifest.inputStream() } })

        val missing = zip("manifest.json" to manifest().toByteArray())
        val parsed = PackArchive.readManifest { missing.inputStream() }
        assertEquals(PackError.FILE_MISSING, errorOf { PackArchive.extract(parsed, { missing.inputStream() }, temp.newFolder("a")) })

        val notOgg = zip("manifest.json" to manifest().toByteArray(), "audio/rain.ogg" to "RIFF....".toByteArray())
        assertEquals(PackError.UNSUPPORTED_AUDIO, errorOf { PackArchive.extract(parsed, { notOgg.inputStream() }, temp.newFolder("b")) })

        val big = zip("manifest.json" to manifest().toByteArray(), "audio/rain.ogg" to ogg + ByteArray(4096))
        assertEquals(
            PackError.TOO_LARGE,
            errorOf { PackArchive.extract(parsed, { big.inputStream() }, temp.newFolder("c"), maxBytes = 1024) },
        )
    }

    @Test
    fun `volume curve is perceptual, monotonic and invertible`() {
        assertEquals(0f, VolumeCurve.gainForPercent(0), 0f)
        assertEquals(1f, VolumeCurve.gainForPercent(100), 1e-6f)
        assertTrue(VolumeCurve.gainForPercent(50) < 0.2f)
        val gains = (0..100).map(VolumeCurve::gainForPercent)
        assertEquals(gains.sorted(), gains)
        for (percent in 0..100) assertEquals(percent, VolumeCurve.percentForGain(VolumeCurve.gainForPercent(percent)))
        assertEquals(60, VolumeCurve.combine(60, 100))
        assertTrue(VolumeCurve.combine(60, 50) < 50)
    }

    @Test
    fun `page files are validated`() {
        val file = PageExchange.parse(
            """{"type":"sonorama-page","format":1,"name":"Soir","symbol":"bedtime",
               "cards":[{"sound":"sonorama.base/wind"},
                        {"composite":{"name":"Orage","symbol":"storm","volume":80,
                                      "sounds":[{"sound":"sonorama.base/wind","volume":60}]}}]}""",
        )
        assertEquals("Soir", file.name)
        assertEquals(2, file.cards.size)
        assertThrows(PageExchange.InvalidPageFile::class.java) { PageExchange.parse("""{"type":"other","name":"x","symbol":"y","cards":[]}""") }
        assertThrows(PageExchange.InvalidPageFile::class.java) { PageExchange.parse("nope") }
    }

    @Test
    fun `a signed pack is verified and identified by its key`() {
        val archive = signedZip()
        val extracted = extract(archive, "signed")
        assertEquals(PackSignature.fingerprint(keys.public), extracted.signerKey)
        assertEquals(hashOf(archive), extracted.archiveSha256)
    }

    @Test
    fun `a pack signed with openssl by the signing tool is accepted`() {
        val archive = javaClass.getResourceAsStream("/signed-pack.zip")!!.readBytes()
        val extracted = extract(archive, "openssl")
        assertEquals("9c8a5a9f998ec640e68faa4fc2f5a13bc7d3ecbaa2447c9398a5e5d8b3a356a6", extracted.signerKey)
        assertEquals("fdd1c289aed105bb04bae76033c4ac4c4893ff1b57081c7eca1fb3b7292f2acf", extracted.archiveSha256)
    }

    @Test
    fun `tampering with a signed pack is detected`() {
        // Fichier audio modifié après signature.
        assertEquals(PackError.HASH_MISMATCH, errorOf { extract(signedZip(audio = ogg + 1), "audio") })
        // Manifeste modifié après signature.
        val renamed = signedZip(tamper = { it.decodeToString().replace("Pluie", "Pluje").toByteArray() })
        assertEquals(PackError.SIGNATURE_INVALID, errorOf { extract(renamed, "manifest") })
        // Signature d'une autre clé que celle fournie.
        val other = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val manifest = manifest(format = 2, extra = """, "sha256": "${hashOf(ogg)}"""").toByteArray()
        val forged = zip(
            "manifest.json" to manifest,
            "manifest.sig" to sign(manifest, other.private),
            "signing-key.pub" to pem(keys.public).toByteArray(),
            "audio/rain.ogg" to ogg,
        )
        assertEquals(PackError.SIGNATURE_INVALID, errorOf { extract(forged, "forged") })
    }

    @Test
    fun `a signature must cover every audio file and come with its key`() {
        assertEquals(PackError.SIGNATURE_INVALID, errorOf { extract(signedZip(declaredHash = null), "nohash") })
        val manifest = manifest(format = 2, extra = """, "sha256": "${hashOf(ogg)}"""").toByteArray()
        val noKey = zip("manifest.json" to manifest, "manifest.sig" to sign(manifest), "audio/rain.ogg" to ogg)
        assertEquals(PackError.SIGNATURE_INVALID, errorOf { extract(noKey, "nokey") })
        val noSignature = zip("manifest.json" to manifest, "signing-key.pub" to pem(keys.public).toByteArray(), "audio/rain.ogg" to ogg)
        assertEquals(PackError.SIGNATURE_INVALID, errorOf { extract(noSignature, "nosig") })
        val garbageKey = zip(
            "manifest.json" to manifest, "manifest.sig" to sign(manifest),
            "signing-key.pub" to "not a key".toByteArray(), "audio/rain.ogg" to ogg,
        )
        assertEquals(PackError.SIGNATURE_INVALID, errorOf { extract(garbageKey, "badkey") })
    }

    @Test
    fun `an unsigned pack may still declare fingerprints, which are checked`() {
        val good = zip("manifest.json" to manifest(extra = """, "sha256": "${hashOf(ogg)}"""").toByteArray(), "audio/rain.ogg" to ogg)
        assertNull(extract(good, "good").signerKey)
        val bad = zip("manifest.json" to manifest(extra = """, "sha256": "${hashOf(ogg)}"""").toByteArray(), "audio/rain.ogg" to ogg + 7)
        assertEquals(PackError.HASH_MISMATCH, errorOf { extract(bad, "bad") })
    }

    @Test
    fun `updates always ask, and the first key seen is remembered`() {
        val a = "a".repeat(64)
        val b = "b".repeat(64)
        fun decide(known: KnownPack?, key: String?) = PackSignature.decide(known, key)
        // Pack inconnu : installation directe, signé ou non.
        assertEquals(ImportDecision.Install, decide(null, null))
        assertEquals(ImportDecision.Install, decide(null, a))
        // Pack installé : toujours une confirmation, même pour une version supérieure.
        assertEquals(ImportDecision.Confirm(null), decide(KnownPack(1, true, null), null))
        assertEquals(ImportDecision.Confirm(null), decide(KnownPack(1, true, a), a))
        assertEquals(ImportDecision.Confirm(null), decide(KnownPack(1, true, null), a))
        // Clé différente : confirmation avec avertissement.
        assertEquals(ImportDecision.Confirm(KeyWarning.KEY_CHANGED), decide(KnownPack(1, true, a), b))
        // Un pack non signé ne remplace pas un pack signé encore installé.
        assertEquals(ImportDecision.Refuse(PackError.SIGNATURE_REQUIRED), decide(KnownPack(1, true, a), null))
        // Pack désinstallé : la clé mémorisée fait toujours foi.
        assertEquals(ImportDecision.Install, decide(KnownPack(1, false, a), a))
        assertEquals(ImportDecision.Install, decide(KnownPack(1, false, null), null))
        assertEquals(ImportDecision.Confirm(KeyWarning.KEY_CHANGED), decide(KnownPack(1, false, a), b))
        assertEquals(ImportDecision.Confirm(KeyWarning.SIGNATURE_DROPPED), decide(KnownPack(1, false, a), null))
    }

    @Test
    fun `trust level follows the signing key`() {
        val official = "a".repeat(64)
        assertEquals(PackTrust.UNSIGNED, PackSignature.trustOf(null, setOf(official)))
        assertEquals(PackTrust.SIGNED, PackSignature.trustOf("b".repeat(64), setOf(official)))
        assertEquals(PackTrust.OFFICIAL, PackSignature.trustOf(official, setOf(official)))
    }
}

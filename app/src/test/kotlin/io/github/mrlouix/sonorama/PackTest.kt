package io.github.mrlouix.sonorama

import io.github.mrlouix.sonorama.audio.VolumeCurve
import io.github.mrlouix.sonorama.data.PageExchange
import io.github.mrlouix.sonorama.data.pack.PackArchive
import io.github.mrlouix.sonorama.data.pack.PackError
import io.github.mrlouix.sonorama.data.pack.PackException
import io.github.mrlouix.sonorama.data.pack.PackFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
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
    ) = """
        {"format": $format, "id": "$id", "version": 2, "name": {"fr": "Démo", "en": "Demo"},
         "pages": [{"id": "maison", "name": {"fr": "Maison", "en": "Home"}, "symbol": "home"}],
         "sounds": [{"id": "rain", "file": "$file", "name": {"fr": "Pluie", "en": "Rain"},
                     "page": "$page", "license": "$license", "futureField": true}]}
    """.trimIndent()

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
        for (ok in listOf("CC0", "CC0-1.0", "CC-BY-4.0", "CC BY 4.0", "CC-BY-SA-4.0", "cc-by-3.0")) {
            assertTrue(ok, PackFormat.isLicenseAllowed(ok))
        }
        for (bad in listOf("", "CC-BY-NC-4.0", "CC-BY-ND-4.0", "CC-BY-NC-SA-4.0", "Proprietary", "MIT")) {
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
        PackArchive.extract(parsed, { archive.inputStream() }, target, onProgress = { progress += it })
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
}

package com.yapt.planttracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
private const val MANIFEST_PATH = "src/main/AndroidManifest.xml"
private val CLOUD_BACKUP_DOMAINS = setOf(
    "root",
    "file",
    "database",
    "sharedpref",
    "external",
    "device_root",
    "device_file",
    "device_database",
    "device_sharedpref"
)

// Plain JVM parse of the manifest + its referenced res/xml files (no Robolectric — there is
// nothing Android-runtime about parsing XML). Regression guard for the #824 root-tag bug: on API
// 31+ the platform requires android:dataExtractionRules to resolve to a <data-extraction-rules>
// root, throws if it doesn't, and silently disables both cloud backup and device-transfer with no
// fallback to fullBackupContent (product ADR-0053).
class BackupRulesTest {

    private fun parseXml(path: String): Document {
        val file = File(path)
        assertTrue("expected \"$path\" to exist relative to the app module dir", file.exists())
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        return factory.newDocumentBuilder().parse(file)
    }

    private fun elementChildren(parent: Element, tagName: String? = null): List<Element> {
        val nodes = parent.childNodes
        val result = mutableListOf<Element>()
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            if (node is Element && (tagName == null || node.tagName == tagName)) {
                result.add(node)
            }
        }
        return result
    }

    private fun singleChild(parent: Element, tagName: String, consequenceIfMissing: String): Element {
        val matches = elementChildren(parent, tagName)
        assertEquals(
            "expected exactly one <$tagName> under <${parent.tagName}>, found ${matches.size} — " +
                consequenceIfMissing,
            1,
            matches.size
        )
        return matches.single()
    }

    private fun manifestApplication(): Element {
        val manifest = parseXml(MANIFEST_PATH).documentElement
        return singleChild(manifest, "application", "the manifest is malformed")
    }

    private fun resolveXmlResource(application: Element, attributeName: String): Document {
        val value = application.getAttributeNS(ANDROID_NS, attributeName)
        assertTrue(
            "android:$attributeName should reference \"@xml/<name>\", was \"$value\"",
            value.startsWith("@xml/")
        )
        val resourceName = value.removePrefix("@xml/")
        return parseXml("src/main/res/xml/$resourceName.xml")
    }

    @Test
    fun allowBackupIsTrue() {
        val application = manifestApplication()
        assertEquals(
            "android:allowBackup must stay \"true\" — turning it off would also disable Android " +
                "8-11 cloud backup and device-transfer, not just API 31+",
            "true",
            application.getAttributeNS(ANDROID_NS, "allowBackup")
        )
    }

    @Test
    fun dataExtractionRulesHasCorrectRootAndSections() {
        val root = resolveXmlResource(manifestApplication(), "dataExtractionRules").documentElement
        assertEquals(
            "android:dataExtractionRules must resolve to a <data-extraction-rules> root, or the " +
                "platform's verifyTopLevelTag() throws and disables cloud backup AND device-transfer " +
                "entirely on API 31+ with no fallback to fullBackupContent",
            "data-extraction-rules",
            root.tagName
        )
        singleChild(
            root,
            "cloud-backup",
            "without it, cloud backup defaults to including everything instead of being turned off"
        )
        singleChild(
            root,
            "device-transfer",
            "without it, phone-to-phone transfer falls back to a different default rule set"
        )
    }

    @Test
    fun cloudBackupExcludesAllNineDomainsAndHasNoInclude() {
        val root = resolveXmlResource(manifestApplication(), "dataExtractionRules").documentElement
        val cloudBackup = singleChild(
            root,
            "cloud-backup",
            "without it, cloud backup defaults to including everything instead of being turned off"
        )

        assertEquals(
            "<cloud-backup> must contain no <include> — an include here flips the section into " +
                "allow-list mode, which would start uploading YAPT data to Google cloud backup " +
                "from API 31+ devices for the first time",
            0,
            elementChildren(cloudBackup, "include").size
        )

        val excludes = elementChildren(cloudBackup, "exclude")
        assertEquals(
            "expected exactly nine <exclude> elements in <cloud-backup>, one per backup domain",
            9,
            excludes.size
        )

        val excludedDomains = excludes.map { it.getAttribute("domain") }
        assertEquals(
            "cloud-backup must exclude exactly these nine domains — each domain is pruned by exact " +
                "canonical-path match, not by prefix, so a missing domain keeps that domain's data " +
                "uploading to Google cloud backup despite the \"cloud off\" intent",
            CLOUD_BACKUP_DOMAINS,
            excludedDomains.toSet()
        )
        assertEquals(
            "expected no duplicate domain excludes in <cloud-backup>",
            CLOUD_BACKUP_DOMAINS.size,
            excludedDomains.size
        )

        excludes.forEach { exclude ->
            assertEquals(
                "exclude for domain=\"${exclude.getAttribute("domain")}\" must use path=\".\" to " +
                    "exclude that whole domain",
                ".",
                exclude.getAttribute("path")
            )
        }
    }

    @Test
    fun deviceTransferHasNoIncludeOrExcludeRules() {
        val root = resolveXmlResource(manifestApplication(), "dataExtractionRules").documentElement
        val deviceTransfer = singleChild(
            root,
            "device-transfer",
            "without it, phone-to-phone transfer falls back to a different default rule set"
        )

        assertEquals(
            "<device-transfer> must stay empty — any include/exclude rule here would stop " +
                "phone-to-phone transfer from carrying everything, photos included",
            emptyList<Element>(),
            elementChildren(deviceTransfer)
        )
    }

    @Test
    fun fullBackupContentRootAndRulesAreUnchanged() {
        val root = resolveXmlResource(manifestApplication(), "fullBackupContent").documentElement
        assertEquals(
            "android:fullBackupContent must still resolve to a <full-backup-content> root for API 26-30",
            "full-backup-content",
            root.tagName
        )

        val rules = elementChildren(root)
        assertEquals(
            "backup_rules.xml's <full-backup-content> must contain exactly one rule — an extra or " +
                "changed rule here changes what Android 8-11 cloud backup and device-transfer include, " +
                "e.g. by newly excluding the photo directories (\"images\"/\"restored_photos\") or the " +
                "whole filesDir subtree",
            1,
            rules.size
        )

        val rule = rules.single()
        assertEquals(
            "backup_rules.xml's one rule must stay an <exclude>, never an <include> — an include here " +
                "would flip the file into allow-list mode and drop everything else on Android 8-11",
            "exclude",
            rule.tagName
        )
        assertEquals(
            "backup_rules.xml's exclude must stay domain=\"file\"",
            "file",
            rule.getAttribute("domain")
        )
        assertEquals(
            "backup_rules.xml's exclude must stay path=\"cache\" — a broader path (e.g. \".\") would " +
                "prune the whole filesDir subtree, photos included, instead of the no-op it is today",
            "cache",
            rule.getAttribute("path")
        )
    }
}

package eu.kanade.tachiyomi.util.storage

import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * Wrapper over ZipFile to load files in epub format.
 */
class EpubFile(file: File) : Closeable {

    /**
     * Zip file of this epub.
     */
    private val zip = ZipFile(file)

    /**
     * Path separator used by this epub.
     */
    private val pathSeparator = getPathSeparator()

    /**
     * Closes the underlying zip file.
     */
    override fun close() {
        zip.close()
    }

    /**
     * Returns an input stream for reading the contents of the specified zip file entry.
     */
    fun getInputStream(entry: ZipEntry): InputStream {
        return zip.getInputStream(entry)
    }

    /**
     * Returns the zip file entry for the specified name, or null if not found.
     */
    fun getEntry(name: String): ZipEntry? {
        return zip.getEntry(name)
    }

    /**
     * Returns the path of all the images found in the epub file.
     */
    fun getImagesFromPages(): List<String> {
        val ref = getPackageHref()
        val doc = getPackageDocument(ref)
        val pages = getPagesFromDocument(doc)
        return getImagesFromPages(pages, ref)
    }

    /**
     * Returns the path to the package document.
     */
    private fun getPackageHref(): String {
        val meta = zip.getEntry(resolveZipPath("META-INF", "container.xml"))
        if (meta != null) {
            val metaDoc = zip.getInputStream(meta).use { Jsoup.parse(it, null, "") }
            val path = metaDoc.getElementsByTag("rootfile").first()?.attr("full-path")
            if (path != null) {
                return path
            }
        }
        return resolveZipPath("OEBPS", "content.opf")
    }

    /**
     * Returns the package document where all the files are listed.
     */
    private fun getPackageDocument(ref: String): Document {
        val entry = zip.getEntry(ref)
        return zip.getInputStream(entry).use { Jsoup.parse(it, null, "") }
    }

    /**
     * Returns all the pages from the epub.
     */
    private fun getPagesFromDocument(document: Document): List<String> {
        val pages = document.select("manifest > item")
            .filter { "application/xhtml+xml" == it.attr("media-type") }
            .associateBy { it.attr("id") }

        val spine = document.select("spine > itemref").map { it.attr("idref") }
        return spine.mapNotNull { pages[it] }.map { it.attr("href") }
    }

    /**
     * Returns all the images contained in every page from the epub.
     */
    private fun getImagesFromPages(pages: List<String>, packageHref: String): List<String> {
        val result = ArrayList<String>()
        val basePath = getParentDirectory(packageHref)
        pages.forEach { page ->
            val entryPath = resolveZipPath(basePath, page)
            val entry = zip.getEntry(entryPath)
            val document = zip.getInputStream(entry).use { Jsoup.parse(it, null, "") }
            val imageBasePath = getParentDirectory(entryPath)

            document.allElements.forEach {
                if (it.tagName() == "img") {
                    result.add(resolveZipPath(imageBasePath, it.attr("src")))
                } else if (it.tagName() == "image") {
                    result.add(resolveZipPath(imageBasePath, it.attr("xlink:href")))
                }
            }
        }

        return result
    }

    /**
     * Returns the path separator used by the epub file.
     */
    private fun getPathSeparator(): String {
        val meta = zip.getEntry("META-INF\\container.xml")
        if (meta != null) {
            return "\\"
        } else {
            return "/"
        }
    }

    /**
     * Resolves a zip path from base and relative components and a path separator.
     */
    private fun resolveZipPath(basePath: String, relativePath: String): String {
        if (relativePath.startsWith(pathSeparator)) {
            // Path is absolute, so return as-is.
            return relativePath
        }

        var fixedBasePath = basePath.replace(pathSeparator, File.separator)
        if (!fixedBasePath.startsWith(File.separator)) {
            fixedBasePath = "${File.separator}$fixedBasePath"
        }

        val fixedRelativePath = relativePath.replace(pathSeparator, File.separator)
        val resolvedPath = File(fixedBasePath, fixedRelativePath).canonicalPath
        return resolvedPath.replace(File.separator, pathSeparator).substring(1)
    }

    /**
     * Gets the parent directory of a path.
     */
    private fun getParentDirectory(path: String): String {
        val separatorIndex = path.lastIndexOf(pathSeparator)
        if (separatorIndex >= 0) {
            return path.substring(0, separatorIndex)
        } else {
            return ""
        }
    }
}

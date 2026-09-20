/*
 * Copyright 2022 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

@file:OptIn(InternalReadiumApi::class)

package expo.modules.readium

import org.readium.r2.shared.DelicateReadiumApi
import org.readium.r2.shared.InternalReadiumApi
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.indexOfFirstWithHref
import org.readium.r2.shared.publication.services.positionsByReadingOrder
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.data.decodeString
import org.readium.r2.shared.util.data.readDecodeOrNull
import org.readium.r2.shared.util.fromEpubHref
import org.readium.r2.shared.util.mediatype.MediaType
import org.readium.r2.shared.util.xml.ElementNode

internal object SmilParser {
    /* According to https://www.w3.org/publishing/epub3/epub-mediaoverlays.html#sec-overlays-content-conf
       a Media Overlay Document MAY refer to more than one EPUB Content Document
       This might be possible only using Canonical Fragment Identifiers
       since the unique body and each seq element MUST reference
       one EPUB Content Document by means of its attribute epub:textref
     */

    fun parse(
        publication: Publication,
        document: ElementNode,
        link: Link
    ): STMediaOverlays? {
        val body = document.getFirst("body", Namespaces.SMIL) ?: return null
        return parseSeq(publication, body, link.url())?.let { STMediaOverlays(link, it) }
    }

    @OptIn(DelicateReadiumApi::class)
    private fun parseSeq(
        publication: Publication,
        node: ElementNode,
        filePath: Url
    ): List<MediaOverlayNode>? {
        val textref = node.getAttrNs("textref", Namespaces.OPS)
            ?.let { Url.fromEpubHref(it) }

        val children: MutableList<MediaOverlayNode> = mutableListOf()
        for (child in node.getAll()) {
            if (child.name == "par" && child.namespace == Namespaces.SMIL) {

                val node = parsePar(
                    child,
                    filePath
                )
                node?.let {
                    children.add(it)
                }
            } else if (child.name == "seq" && child.namespace == Namespaces.SMIL) {
                parseSeq(publication, child, filePath)?.let { children.addAll(it) }
            }
        }

        /* No wrapping media overlay can be created unless:
       - all child media overlays reference the same audio file
       - the seq element has an textref attribute (this is mandatory according to the EPUB spec)
         */
        val audioFiles = children.mapNotNull(MediaOverlayNode::audioFile)
        return if (textref != null && audioFiles.distinct().size == 1) { // hierarchy
            val normalizedTextref = filePath.resolve(textref)
            listOf(mediaOverlayFromChildren(normalizedTextref, children))
        } else {
            children
        }
    }

    // Url.fromEpubHref encodes # as %23 when falling back to path-based
    // encoding for hrefs with spaces. split the fragment out first so it
    // survives the encoding, then resolve against a base url.
    private fun resolveEpubHref(href: String, base: Url): Url? {
        val hashIndex = href.indexOf('#')

        if (hashIndex < 0) {
            val url = Url.fromEpubHref(href) ?: return null
            return base.resolve(url)
        }

        val pathPart = href.substring(0, hashIndex)
        val fragment = href.substring(hashIndex + 1)
        val pathUrl = Url.fromEpubHref(pathPart) ?: return null
        val resolved = base.resolve(pathUrl)

        return Url("$resolved#$fragment")
    }

    private fun parsePar(
        node: ElementNode,
        filePath: Url
    ): MediaOverlayNode? {
        val textSrc = node.getFirst("text", Namespaces.SMIL)?.getAttr("src")
            ?: return null

        val resolvedText = resolveEpubHref(textSrc, filePath)
            ?: return null

        val resolvedAudio = node.getFirst("audio", Namespaces.SMIL)
            ?.let { audioNode ->
                val src = audioNode.getAttr("src") ?: return@let null
                val begin = audioNode.getAttr("clipBegin")?.let { ClockValueParser.parse(it) } ?: ""
                val end = audioNode.getAttr("clipEnd")?.let { ClockValueParser.parse(it) } ?: ""
                resolveEpubHref("$src#t=$begin,$end", filePath)
            }

        return MediaOverlayNode(
                resolvedText,
                resolvedAudio,
            )
    }

    private fun mediaOverlayFromChildren(
        text: Url,
        children: List<MediaOverlayNode>
    ): MediaOverlayNode {
        require(children.isNotEmpty() && children.mapNotNull { it.audioFile }.distinct().size <= 1)
        val audioChildren = children.mapNotNull { if (it.audioFile != null) it else null }
        val file = audioChildren.first().audioFile
        val start = audioChildren.first().clip!!.start
        val end = audioChildren.last().clip!!.end
        val audio = Url.fromEpubHref("$file#t=$start,$end")
        return MediaOverlayNode(text, audio, children, listOf("section"))
    }
}

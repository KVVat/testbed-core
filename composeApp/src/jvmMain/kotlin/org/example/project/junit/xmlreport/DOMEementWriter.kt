package org.example.project.junit.xmlreport


import org.w3c.dom.Attr
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.w3c.dom.Text
import java.io.IOException
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Writer


/**
 * NOTE: This class is from Apache Ant 1.7.1
 * Writes a DOM tree to a given Writer.
 * warning: this utility currently does not declare XML Namespaces.
 *
 * Utility class used by [ XmlLogger][org.apache.tools.ant.XmlLogger] and
 * org.apache.tools.ant.taskdefs.optional.junit.XMLJUnitResultFormatter
 * XMLJUnitResultFormatter}.
 */
internal class DOMElementWriter {
    /** xml declaration is on by default  */
    private var xmlDeclaration = true

    /**
     * XML Namespaces are ignored by default.
     */
    private var namespacePolicy = XmlNamespacePolicy.IGNORE

    /**
     * Map (URI to prefix) of known namespaces.
     */
    private val nsPrefixMap: MutableMap<Any?, Any?> = mutableMapOf();// HashMap<Any?, Any?>()

    /**
     * Number of generated prefix to use next.
     */
    private var nextPrefix = 0

    /**
     * Map (Element to URI) of namespaces defined on a given element.
     */
    private val nsURIByElement: MutableMap<Any?, Any?> = mutableMapOf();/// HashMap<Any?, Any?>()

    /**
     * Whether namespaces should be ignored for elements and attributes.
     *
     * @since Ant 1.7
     */
    internal class XmlNamespacePolicy
    /**
     * @param qualifyElements whether to qualify elements
     * @param qualifyAttributes whether to qualify elements
     */(val qualifyElements: Boolean, val qualifyAttributes: Boolean) {
        companion object {
            /**
             * Ignores namespaces for elements and attributes, the default.
             */
            val IGNORE = XmlNamespacePolicy(false, false)

            /**
             * Ignores namespaces for attributes.
             */
            val ONLY_QUALIFY_ELEMENTS = XmlNamespacePolicy(true, false)

            /**
             * Qualifies namespaces for elements and attributes.
             */
            val QUALIFY_ALL = XmlNamespacePolicy(true, true)
        }
    }

    /**
     * Create an element writer.
     * The ?xml? declaration will be included, namespaces ignored.
     */
    constructor()
    /**
     * Create an element writer
     * XML namespaces will be ignored.
     * @param xmlDeclaration flag to indicate whether the ?xml? declaration
     * should be included.
     * @param namespacePolicy the policy to use.
     * @since Ant1.7
     */
    /**
     * Create an element writer
     * XML namespaces will be ignored.
     * @param xmlDeclaration flag to indicate whether the ?xml? declaration
     * should be included.
     * @since Ant1.7
     */
    @JvmOverloads
    constructor(
        xmlDeclaration: Boolean,
        namespacePolicy: XmlNamespacePolicy = XmlNamespacePolicy.IGNORE
    ) {
        this.xmlDeclaration = xmlDeclaration
        this.namespacePolicy = namespacePolicy
    }
    // CheckStyle:VisibilityModifier OFF - bc
    /**
     * Don't try to be too smart but at least recognize the predefined
     * entities.
     */
    var knownEntities = arrayOf("gt", "amp", "lt", "apos", "quot")
    // CheckStyle:VisibilityModifier ON
    /**
     * Writes a DOM tree to a stream in UTF8 encoding. Note that
     * it prepends the &lt;?xml version='1.0' encoding='UTF-8'?&gt; if
     * the xmlDeclaration field is true.
     * The indent number is set to 0 and a 2-space indent.
     * @param root the root element of the DOM tree.
     * @param out the outputstream to write to.
     * @throws IOException if an error happens while writing to the stream.
     */
    @Throws(IOException::class)
    fun write(root: Element, out: OutputStream?) {
        val wri: Writer = OutputStreamWriter(out, "UTF8")
        writeXMLDeclaration(wri)
        write(root, wri, 0, "  ")
        wri.flush()
    }

    /**
     * Writes the XML declaration if xmlDeclaration is true.
     * @param wri the writer to write to.
     * @throws IOException if there is an error.
     * @since Ant 1.7.0
     */
    @Throws(IOException::class)
    fun writeXMLDeclaration(wri: Writer) {
        if (xmlDeclaration) {
            wri.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        }
    }

    /**
     * Writes a DOM tree to a stream.
     *
     * @param element the Root DOM element of the tree
     * @param out where to send the output
     * @param indent number of
     * @param indentWith string that should be used to indent the
     * corresponding tag.
     * @throws IOException if an error happens while writing to the stream.
     */
    @Throws(IOException::class)
    fun write(element: Element, out: Writer, indent: Int, indentWith: String?) {

        // Write child elements and text
        val children = element.childNodes
        val hasChildren = children.length > 0
        var hasChildElements = false
        openElement(element, out, indent, indentWith, hasChildren)
        if (hasChildren) {
            for (i in 0 until children.length) {
                val child = children.item(i)
                when (child.nodeType) {
                    Node.ELEMENT_NODE -> {
                        hasChildElements = true
                        if (i == 0) {
                            out.write(lSep)
                        }
                        write(child as Element, out, indent + 1, indentWith)
                    }

                    Node.TEXT_NODE -> out.write(encode(child.nodeValue))
                    Node.COMMENT_NODE -> {
                        out.write("<!--")
                        out.write(encode(child.nodeValue))
                        out.write("-->")
                    }

                    Node.CDATA_SECTION_NODE -> {
                        out.write("<![CDATA[")
                        out.write(encodedata((child as Text).data))
                        out.write("]]>")
                    }

                    Node.ENTITY_REFERENCE_NODE -> {
                        out.write('&'.code)
                        out.write(child.nodeName)
                        out.write(';'.code)
                    }

                    Node.PROCESSING_INSTRUCTION_NODE -> {
                        out.write("<?")
                        out.write(child.nodeName)
                        val data = child.nodeValue
                        if (data != null && data.length > 0) {
                            out.write(' '.code)
                            out.write(data)
                        }
                        out.write("?>")
                    }

                    else -> {}
                }
            }
            closeElement(element, out, indent, indentWith, hasChildElements)
        }
    }
    /**
     * Writes the opening tag - including all attributes -
     * corresponding to a DOM element.
     *
     * @param element the DOM element to write
     * @param out where to send the output
     * @param indent number of
     * @param indentWith string that should be used to indent the
     * corresponding tag.
     * @param hasChildren whether this element has children.
     * @throws IOException if an error happens while writing to the stream.
     * @since Ant 1.7
     */
    /**
     * Writes the opening tag - including all attributes -
     * corresponding to a DOM element.
     *
     * @param element the DOM element to write
     * @param out where to send the output
     * @param indent number of
     * @param indentWith string that should be used to indent the
     * corresponding tag.
     * @throws IOException if an error happens while writing to the stream.
     */
    @JvmOverloads
    @Throws(IOException::class)
    fun openElement(
        element: Element,
        out: Writer,
        indent: Int,
        indentWith: String?,
        hasChildren: Boolean = true
    ) {
        // Write indent characters
        for (i in 0 until indent) {
            out.write(indentWith)
        }

        // Write element
        out.write("<")
        if (namespacePolicy.qualifyElements) {
            val uri = getNamespaceURI(element)
            var prefix = nsPrefixMap[uri] as String?
            if (prefix == null) {
                prefix = if (nsPrefixMap.isEmpty()) {
                    // steal default namespace
                    ""
                } else {
                    NS + nextPrefix++
                }
                nsPrefixMap[uri] = prefix
                addNSDefinition(element, uri)
            }
            if ("" != prefix) {
                out.write(prefix)
                out.write(":")
            }
        }
        out.write(element.tagName)

        // Write attributes
        val attrs = element.attributes
        for (i in 0 until attrs.length) {
            val attr = attrs.item(i) as Attr
            out.write(" ")
            if (namespacePolicy.qualifyAttributes) {
                val uri = getNamespaceURI(attr)
                var prefix = nsPrefixMap[uri] as String?
                if (prefix == null) {
                    prefix = NS + nextPrefix++
                    nsPrefixMap[uri] = prefix
                    addNSDefinition(element, uri)
                }
                out.write(prefix)
                out.write(":")
            }
            out.write(attr.name)
            out.write("=\"")
            out.write(encode(attr.value))
            out.write("\"")
        }

        // write namespace declarations
        val al = nsURIByElement[element] as ArrayList<*>?
        if (al != null) {
            val iter: Iterator<*> = al.iterator()
            while (iter.hasNext()) {
                val uri = iter.next() as String
                val prefix = nsPrefixMap[uri] as String?
                out.write(" xmlns")
                if ("" != prefix) {
                    out.write(":")
                    out.write(prefix)
                }
                out.write("=\"")
                out.write(uri)
                out.write("\"")
            }
        }
        if (hasChildren) {
            out.write(">")
        } else {
            removeNSDefinitions(element)
            out.write(" />")
            out.write(lSep)
            out.flush()
        }
    }

    /**
     * Writes a DOM tree to a stream.
     *
     * @param element the Root DOM element of the tree
     * @param out where to send the output
     * @param indent number of
     * @param indentWith string that should be used to indent the
     * corresponding tag.
     * @param hasChildren if true indent.
     * @throws IOException if an error happens while writing to the stream.
     */
    @Throws(IOException::class)
    fun closeElement(
        element: Element,
        out: Writer,
        indent: Int,
        indentWith: String?,
        hasChildren: Boolean
    ) {
        // If we had child elements, we need to indent before we close
        // the element, otherwise we're on the same line and don't need
        // to indent
        if (hasChildren) {
            for (i in 0 until indent) {
                out.write(indentWith)
            }
        }

        // Write element close
        out.write("</")
        if (namespacePolicy.qualifyElements) {
            val uri = getNamespaceURI(element)
            val prefix = nsPrefixMap[uri] as String?
            if (prefix != null && "" != prefix) {
                out.write(prefix)
                out.write(":")
            }
            removeNSDefinitions(element)
        }
        out.write(element.tagName)
        out.write(">")
        out.write(lSep)
        out.flush()
    }

    /**
     * Escape &lt;, &gt; &amp; &apos;, &quot; as their entities and
     * drop characters that are illegal in XML documents.
     * @param value the string to encode.
     * @return the encoded string.
     */
    fun encode(value: String): String {
        val sb = StringBuffer()
        val len = value.length
        for (i in 0 until len) {
            val c = value[i]
            when (c) {
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '\'' -> sb.append("&apos;")
                '\"' -> sb.append("&quot;")
                '&' -> {
                    val nextSemi = value.indexOf(";", i)
                    if (nextSemi < 0 || !isReference(value.substring(i, nextSemi + 1))) {
                        sb.append("&amp;")
                    } else {
                        sb.append('&')
                    }
                }

                else -> if (isLegalCharacter(c)) {
                    sb.append(c)
                }
            }
        }
        return sb.substring(0)
    }

    /**
     * Drop characters that are illegal in XML documents.
     *
     *
     * Also ensure that we are not including an `]]>`
     * marker by replacing that sequence with
     * `&#x5d;&#x5d;&gt;`.
     *
     *
     * See XML 1.0 2.2 [
     * http://www.w3.org/TR/1998/REC-xml-19980210#charsets](http://www.w3.org/TR/1998/REC-xml-19980210#charsets) and
     * 2.7 [http://www.w3.org/TR/1998/REC-xml-19980210#sec-cdata-sect](http://www.w3.org/TR/1998/REC-xml-19980210#sec-cdata-sect).
     * @param value the value to be encoded.
     * @return the encoded value.
     */
    fun encodedata(value: String): String {
        val sb = StringBuffer()
        val len = value.length
        for (i in 0 until len) {
            val c = value[i]
            if (isLegalCharacter(c)) {
                sb.append(c)
            }
        }
        var result = sb.substring(0)
        var cdEnd = result.indexOf("]]>")
        while (cdEnd != -1) {
            sb.setLength(cdEnd)
            // CheckStyle:MagicNumber OFF
            sb.append("&#x5d;&#x5d;&gt;").append(result.substring(cdEnd + 3))
            // CheckStyle:MagicNumber ON
            result = sb.substring(0)
            cdEnd = result.indexOf("]]>")
        }
        return result
    }

    /**
     * Is the given argument a character or entity reference?
     * @param ent the value to be checked.
     * @return true if it is an entity.
     */
    fun isReference(ent: String): Boolean {
        if (ent[0] != '&' || !ent.endsWith(";")) {
            return false
        }
        if (ent[1] == '#') {
            return if (ent[2] == 'x') {
                try {
                    // CheckStyle:MagicNumber OFF
                    ent.substring(3, ent.length - 1).toInt(HEX)
                    // CheckStyle:MagicNumber ON
                    true
                } catch (nfe: NumberFormatException) {
                    false
                }
            } else {
                try {
                    ent.substring(2, ent.length - 1).toInt()
                    true
                } catch (nfe: NumberFormatException) {
                    false
                }
            }
        }
        val name = ent.substring(1, ent.length - 1)
        for (i in knownEntities.indices) {
            if (name == knownEntities[i]) {
                return true
            }
        }
        return false
    }

    /**
     * Is the given character allowed inside an XML document?
     *
     *
     * See XML 1.0 2.2 [
     * http://www.w3.org/TR/1998/REC-xml-19980210#charsets](http://www.w3.org/TR/1998/REC-xml-19980210#charsets).
     * @param c the character to test.
     * @return true if the character is allowed.
     * @since 1.10, Ant 1.5
     */
    fun isLegalCharacter(c: Char): Boolean {
        // CheckStyle:MagicNumber OFF
        if (c.code == 0x9 || c.code == 0xA || c.code == 0xD) {
            return true
        } else if (c.code < 0x20) {
            return false
        } else if (c.code <= 0xD7FF) {
            return true
        } else if (c.code < 0xE000) {
            return false
        } else if (c.code <= 0xFFFD) {
            return true
        }
        // CheckStyle:MagicNumber ON
        return false
    }

    private fun removeNSDefinitions(element: Element) {
        val al = nsURIByElement[element] as ArrayList<*>?
        if (al != null) {
            val iter: Iterator<*> = al.iterator()
            while (iter.hasNext()) {
                nsPrefixMap.remove(iter.next())
            }
            nsURIByElement.remove(element)
        }
    }

    private fun addNSDefinition(element: Element, uri: String) {
        var al = nsURIByElement[element] as MutableList<Any?>
        if (al == null) {
            al = mutableListOf();// ArrayList<Any?>()
            nsURIByElement[element] = al
        }
        al.add(uri)
    }

    companion object {
        private const val HEX = 16

        /** prefix for generated prefixes  */
        private const val NS = "ns"
        private val lSep = System.getProperty("line.separator")
        private fun getNamespaceURI(n: Node): String {
            var uri = n.namespaceURI
            if (uri == null) {
                // FIXME: Is "No Namespace is Empty Namespace" really OK?
                uri = ""
            }
            return uri
        }
    }
}
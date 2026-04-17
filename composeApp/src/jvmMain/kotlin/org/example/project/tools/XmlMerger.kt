package org.example.project.tools

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory

object XmlMerger {
    fun merge(reportFile: File, patchFile: File) {
        try {
            println("XmlMerger: Merging $patchFile into $reportFile")
            val dbf = DocumentBuilderFactory.newInstance()
            val db = dbf.newDocumentBuilder()
            
            val reportDoc = db.parse(reportFile)
            val patchDoc = db.parse(patchFile)
            
            val xpath = XPathFactory.newInstance().newXPath()
            
            val addNodes = patchDoc.getElementsByTagName("add")
            for (i in 0 until addNodes.length) {
                val addNode = addNodes.item(i) as Element
                val sel = addNode.getAttribute("sel")
                
                if (sel.isNotEmpty()) {
                    val targetNode = xpath.evaluate(sel, reportDoc, XPathConstants.NODE) as? Node
                    if (targetNode != null) {
                        val children = addNode.childNodes
                        for (j in 0 until children.length) {
                            val child = children.item(j)
                            val importedChild = reportDoc.importNode(child, true)
                            targetNode.appendChild(importedChild)
                        }
                        println("XmlMerger: Applied patch for selector $sel")
                    } else {
                        println("XmlMerger: Target node not found for selector: $sel")
                    }
                }
            }
            
            val updateNodes = patchDoc.getElementsByTagName("update")
            for (i in 0 until updateNodes.length) {
                val updateNode = updateNodes.item(i) as Element
                val sel = updateNode.getAttribute("sel")
                
                if (sel.isNotEmpty()) {
                    val targetNode = xpath.evaluate(sel, reportDoc, XPathConstants.NODE) as? Node
                    if (targetNode != null) {
                        // 既存の子ノードを削除
                        while (targetNode.hasChildNodes()) {
                            targetNode.removeChild(targetNode.firstChild)
                        }
                        // 新しい子ノードを追加
                        val children = updateNode.childNodes
                        for (j in 0 until children.length) {
                            val child = children.item(j)
                            val importedChild = reportDoc.importNode(child, true)
                            targetNode.appendChild(importedChild)
                        }
                        println("XmlMerger: Applied update patch for selector $sel")
                    } else {
                        println("XmlMerger: Target node not found for selector: $sel")
                    }
                }
            }
            
            // Save the merged document
            val transformer = TransformerFactory.newInstance().newTransformer()
            val source = DOMSource(reportDoc)
            val result = StreamResult(reportFile)
            transformer.transform(source, result)
            
            println("XmlMerger: Successfully merged patch $patchFile into $reportFile")
        } catch (e: Exception) {
            println("XmlMerger: Failed to merge XML patches: ${e.message}")
            e.printStackTrace()
        }
    }
}

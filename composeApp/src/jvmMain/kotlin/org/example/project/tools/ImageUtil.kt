package org.example.project.tools

import java.awt.Image
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

object ImageUtil {
    /**
     * Resizes a base64-encoded image so that its maximum dimension fits within [maxDimension] pixels,
     * maintaining the aspect ratio, and compresses it to JPEG format with [jpegQuality].
     * Returns the compressed image as a base64-encoded string.
     */
    fun resizeBase64Image(base64Str: String, maxDimension: Int = 1024, jpegQuality: Float = 0.75f): String? {
        return try {
            val imageBytes = Base64.getDecoder().decode(base64Str.trim())
            val inputStream = ByteArrayInputStream(imageBytes)
            val originalImage = ImageIO.read(inputStream) ?: return null

            val width = originalImage.width
            val height = originalImage.height

            // Calculate new dimensions to fit within maxDimension bounding box
            val newWidth: Int
            val newHeight: Int
            if (width > height) {
                newWidth = maxDimension
                newHeight = (height * maxDimension) / width
            } else {
                newHeight = maxDimension
                newWidth = (width * maxDimension) / height
            }

            // Perform smooth scaling
            val resizedImage = originalImage.getScaledInstance(newWidth, newHeight, Image.SCALE_SMOOTH)
            val bufferedResizedImage = BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB)
            val g2d = bufferedResizedImage.createGraphics()
            g2d.drawImage(resizedImage, 0, 0, null)
            g2d.dispose()

            // Compress to JPEG with specified quality
            val outputStream = ByteArrayOutputStream()
            val writers = ImageIO.getImageWritersByFormatName("jpeg")
            if (!writers.hasNext()) return null
            val writer = writers.next()
            
            val writeParam = writer.defaultWriteParam
            if (writeParam.canWriteCompressed()) {
                writeParam.compressionMode = ImageWriteParam.MODE_EXPLICIT
                writeParam.compressionType = "JPEG"
                writeParam.compressionQuality = jpegQuality
            }

            writer.output = ImageIO.createImageOutputStream(outputStream)
            writer.write(null, IIOImage(bufferedResizedImage, null, null), writeParam)
            writer.dispose()

            val compressedBytes = outputStream.toByteArray()
            Base64.getEncoder().encodeToString(compressedBytes)
        } catch (e: Exception) {
            System.err.println("[ImageUtil] Failed to resize and compress image: ${e.message}")
            e.printStackTrace()
            null
        }
    }
}

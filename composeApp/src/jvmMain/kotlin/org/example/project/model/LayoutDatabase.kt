package org.example.project.model

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import org.example.project.JUnitBridge

data class LayoutRecord(
    val id: Int,
    val uuid: String,
    val timestamp: Long,
    val jsonFilepath: String,
    val pngFilepath: String?,
    val tag: String?
)

object LayoutDatabase {
    private val baseDir: File get() = if (JUnitBridge.baseDir.isNotBlank()) File(JUnitBridge.baseDir) else File(".")
    private val dbFile: File get() = File(baseDir, "saved_layouts/layouts.db").apply {
        parentFile.mkdirs()
    }

    private fun getConnection(): Connection {
        Class.forName("org.sqlite.JDBC")
        return DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}?busy_timeout=5000")
    }

    init {
        try {
            getConnection().use { conn ->
                val sql = """
                    CREATE TABLE IF NOT EXISTS layouts (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        uuid TEXT NOT NULL UNIQUE,
                        timestamp INTEGER NOT NULL,
                        json_filepath TEXT NOT NULL,
                        png_filepath TEXT,
                        tag TEXT
                    );
                    CREATE INDEX IF NOT EXISTS idx_layouts_uuid ON layouts(uuid);
                    CREATE INDEX IF NOT EXISTS idx_layouts_tag ON layouts(tag);
                    CREATE INDEX IF NOT EXISTS idx_layouts_timestamp ON layouts(timestamp);
                """.trimIndent()
                conn.createStatement().use { stmt ->
                    sql.split(";").forEach { subSql ->
                        if (subSql.trim().isNotEmpty()) {
                            stmt.execute(subSql)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            System.err.println("[DB] Failed to initialize layout database: ${e.message}")
            e.printStackTrace()
        }
    }

    fun saveLayoutArtifact(jsonLayout: String, screenshotBase64: String?, tag: String?): String {
        val uuid = java.util.UUID.randomUUID().toString()
        val timestampStr = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        
        val savedDir = File(baseDir, "saved_layouts").apply { mkdirs() }
        val jsonFile = File(savedDir, "layout_${timestampStr}_$uuid.json")
        try {
            jsonFile.writeText(jsonLayout)
        } catch (e: Exception) {
            System.err.println("[DB] Failed to write layout JSON: ${e.message}")
        }

        var pngFile: File? = null
        if (!screenshotBase64.isNullOrEmpty()) {
            try {
                val imgBytes = java.util.Base64.getDecoder().decode(screenshotBase64)
                pngFile = File(savedDir, "layout_${timestampStr}_$uuid.png")
                pngFile.writeBytes(imgBytes)
            } catch (e: Exception) {
                System.err.println("[DB] Failed to write layout PNG: ${e.message}")
            }
        }

        insertRecord(
            uuid = uuid,
            timestamp = System.currentTimeMillis(),
            jsonPath = jsonFile.name,
            pngPath = pngFile?.name,
            tag = tag
        )
        return uuid
    }

    fun insertRecord(uuid: String, timestamp: Long, jsonPath: String, pngPath: String?, tag: String?): Boolean = synchronized(this) {
        val sql = "INSERT INTO layouts (uuid, timestamp, json_filepath, png_filepath, tag) VALUES (?, ?, ?, ?, ?)"
        try {
            getConnection().use { conn ->
                conn.prepareStatement(sql).use { pstmt ->
                    pstmt.setString(1, uuid)
                    pstmt.setLong(2, timestamp)
                    pstmt.setString(3, jsonPath)
                    pstmt.setString(4, pngPath)
                    pstmt.setString(5, tag)
                    pstmt.executeUpdate() > 0
                }
            }
        } catch (e: Exception) {
            System.err.println("[DB] Failed to insert record: ${e.message}")
            e.printStackTrace()
            false
        }
    }

    fun getRecordByUuid(uuid: String): LayoutRecord? = synchronized(this) {
        val sql = "SELECT * FROM layouts WHERE uuid = ?"
        try {
            getConnection().use { conn ->
                conn.prepareStatement(sql).use { pstmt ->
                    pstmt.setString(1, uuid)
                    pstmt.executeQuery().use { rs ->
                        if (rs.next()) return rs.toRecord()
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        null
    }

    fun getLatestRecordByTag(tag: String): LayoutRecord? = synchronized(this) {
        val sql = "SELECT * FROM layouts WHERE tag = ? ORDER BY timestamp DESC LIMIT 1"
        try {
            getConnection().use { conn ->
                conn.prepareStatement(sql).use { pstmt ->
                    pstmt.setString(1, tag)
                    pstmt.executeQuery().use { rs ->
                        if (rs.next()) return rs.toRecord()
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        null
    }

    fun getRecordByOffset(offset: Int): LayoutRecord? = synchronized(this) {
        val sql = "SELECT * FROM layouts ORDER BY timestamp DESC LIMIT 1 OFFSET ?"
        try {
            getConnection().use { conn ->
                conn.prepareStatement(sql).use { pstmt ->
                    pstmt.setInt(1, offset)
                    pstmt.executeQuery().use { rs ->
                        if (rs.next()) return rs.toRecord()
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        null
    }

    fun getAllRecords(): List<LayoutRecord> = synchronized(this) {
        val sql = "SELECT * FROM layouts ORDER BY timestamp DESC"
        val list = mutableListOf<LayoutRecord>()
        try {
            getConnection().use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.executeQuery(sql).use { rs ->
                        while (rs.next()) {
                            list.add(rs.toRecord())
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        list
    }

    private fun ResultSet.toRecord() = LayoutRecord(
        id = getInt("id"),
        uuid = getString("uuid"),
        timestamp = getLong("timestamp"),
        jsonFilepath = getString("json_filepath"),
        pngFilepath = getString("png_filepath"),
        tag = getString("tag")
    )
}

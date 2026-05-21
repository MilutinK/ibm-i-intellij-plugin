package com.github.milutink.ibmiintellijplugin.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.net.UnknownHostException
import java.sql.Connection
import java.sql.SQLException
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

data class TableInfo(val name: String, val schema: String, val description: String)
data class ColumnInfo(val tableName: String, val columnName: String, val description: String, val dataType: String = "")

@Service(Service.Level.PROJECT)
class DatabaseService(@Suppress("UNUSED_PARAMETER") project: Project) : Disposable {

    private var connection: Connection? = null
    private var currentSchemas: List<String> = emptyList()

    private val tableDescCache  = ConcurrentHashMap<String, String>() // TABLE_NAME.uppercase -> description
    private val columnDescCache = ConcurrentHashMap<String, String>() // COLUMN_NAME.uppercase -> description

    val isConnected: Boolean
        get() = connection?.isClosed == false

    fun connectIBMi(host: String, port: String, user: String, password: String, schemas: List<String> = emptyList()) {
        currentSchemas = schemas
        // Load driver via plugin classloader so it is found even when DriverManager uses the system classloader.
        val driver = Class.forName(
            "com.ibm.as400.access.AS400JDBCDriver",
            true,
            DatabaseService::class.java.classLoader
        ).getDeclaredConstructor().newInstance() as java.sql.Driver
        val props = Properties().apply {
            setProperty("user", user)
            setProperty("password", password)
            setProperty("login timeout", "20")
            setProperty("soTimeout", "20000")
            if (port.isNotBlank() && port != "446") setProperty("portnumber", port)
        }
        // JT400 login timeout property does not cover the TCP connect phase.
        // Wrap in a Future so the overall attempt is bounded to 25 s.
        val executor = Executors.newSingleThreadExecutor()
        val future = executor.submit<Connection> {
            driver.connect("jdbc:as400://$host", props)
                ?: throw RuntimeException("Treiber hat die URL nicht akzeptiert.")
        }
        try {
            connection = try {
                future.get(25, TimeUnit.SECONDS)
            } catch (e: TimeoutException) {
                future.cancel(true)
                throw RuntimeException("Verbindungs-Timeout: Server antwortet nicht (25 s).")
            } catch (e: ExecutionException) {
                val cause = e.cause as? Exception ?: e
                throw RuntimeException(translateError(cause), cause)
            }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun translateError(e: Exception): String {
        val sqlState = (e as? SQLException)?.sqlState ?: ""
        val msg      = e.message ?: ""
        val cause    = e.cause
        return when {
            sqlState == "28000"                                          -> "Falscher Benutzername oder Passwort."
            sqlState == "42501" || sqlState == "42000"                   -> "Keine Berechtigung für QSYS2-Katalog. Benutzer hat kein *USE-Recht auf QSYS2."
            sqlState.startsWith("08")                                    -> "Verbindung fehlgeschlagen: Server nicht erreichbar."
            cause is UnknownHostException                                -> "Host '${cause.message}' nicht gefunden."
            msg.contains("timed out",          ignoreCase = true) ||
            msg.contains("timeout",            ignoreCase = true)       -> "Verbindungs-Timeout: Server antwortet nicht (15 s)."
            msg.contains("Connection refused", ignoreCase = true)       -> "Verbindung abgelehnt: falscher Host oder Port gesperrt."
            else                                                         -> cause?.message ?: msg
        }
    }

    fun getTables(): List<TableInfo> {
        val conn = connection ?: error("Keine Datenbankverbindung")
        val result = mutableListOf<TableInfo>()
        val sql = buildQuery(
            "SELECT TABLE_NAME, TABLE_SCHEMA, TABLE_TEXT FROM QSYS2.SYSTABLES",
            "TABLE_SCHEMA",
            "ORDER BY TABLE_SCHEMA, TABLE_NAME"
        )
        try {
            conn.prepareStatement(sql).apply { queryTimeout = 60 }.applySchemas().executeQuery().use { rs ->
                while (rs.next()) {
                    result += TableInfo(
                        name        = rs.getString("TABLE_NAME"),
                        schema      = rs.getString("TABLE_SCHEMA"),
                        description = rs.getString("TABLE_TEXT") ?: ""
                    )
                }
            }
        } catch (e: SQLException) {
            throw RuntimeException(translateError(e), e)
        }
        tableDescCache.clear()
        result.forEach { tableDescCache[it.name.uppercase()] = it.description }
        // Only preload columns when schemas are explicitly filtered;
        // without a filter this query returns millions of rows on real IBM i systems.
        if (currentSchemas.isNotEmpty()) preloadAllColumns()
        return result
    }

    private fun preloadAllColumns() {
        val conn = connection ?: return
        columnDescCache.clear()
        val sql = buildQuery(
            "SELECT COLUMN_NAME, COLUMN_TEXT FROM QSYS2.SYSCOLUMNS",
            "TABLE_SCHEMA"
        )
        conn.prepareStatement(sql).apply { queryTimeout = 60 }.applySchemas().executeQuery().use { rs ->
            while (rs.next()) {
                val colName = rs.getString("COLUMN_NAME") ?: return@use
                val colText = rs.getString("COLUMN_TEXT") ?: return@use
                columnDescCache[colName.uppercase()] = colText
            }
        }
    }

    private fun buildQuery(base: String, schemaColumn: String, suffix: String = ""): String {
        val filter = if (currentSchemas.isEmpty()) "" else {
            val placeholders = currentSchemas.joinToString(",") { "?" }
            " WHERE $schemaColumn IN ($placeholders)"
        }
        return if (suffix.isEmpty()) "$base$filter" else "$base$filter $suffix"
    }

    private fun java.sql.PreparedStatement.applySchemas(): java.sql.PreparedStatement {
        currentSchemas.forEachIndexed { i, s -> setString(i + 1, s) }
        return this
    }

    fun getColumns(tableName: String, schema: String): List<ColumnInfo> {
        val conn = connection ?: error("Keine Datenbankverbindung")
        val result = mutableListOf<ColumnInfo>()
        conn.prepareStatement(
            """SELECT COLUMN_NAME, COLUMN_TEXT, DATA_TYPE, LENGTH, NUMERIC_PRECISION, NUMERIC_SCALE
               FROM QSYS2.SYSCOLUMNS
               WHERE TABLE_NAME = ? AND TABLE_SCHEMA = ?
               ORDER BY ORDINAL_POSITION, COLUMN_NAME"""
        ).apply {
            queryTimeout = 30
            setString(1, tableName)
            setString(2, schema)
        }.executeQuery().use { rs ->
            while (rs.next()) {
                result += ColumnInfo(
                    tableName   = tableName,
                    columnName  = rs.getString("COLUMN_NAME"),
                    description = rs.getString("COLUMN_TEXT") ?: "",
                    dataType    = formatDataType(
                        rs.getString("DATA_TYPE") ?: "",
                        rs.getInt("LENGTH"),
                        rs.getInt("NUMERIC_PRECISION"),
                        rs.getInt("NUMERIC_SCALE")
                    )
                )
            }
        }
        result.forEach { columnDescCache.putIfAbsent(it.columnName.uppercase(), it.description) }
        return result
    }

    private fun formatDataType(type: String, length: Int, precision: Int, scale: Int): String =
        when (type.trim().uppercase()) {
            "CHARACTER", "CHAR"            -> "CHAR($length)"
            "VARCHAR", "CHARACTER VARYING" -> "VARCHAR($length)"
            "DECIMAL", "NUMERIC"           -> if (scale > 0) "DEC($precision,$scale)" else "DEC($precision)"
            "INTEGER", "INT"               -> "INTEGER"
            "SMALLINT"                     -> "SMALLINT"
            "BIGINT"                       -> "BIGINT"
            "FLOAT", "DOUBLE", "REAL"      -> type.uppercase()
            "DATE"                         -> "DATE"
            "TIME"                         -> "TIME"
            "TIMESTAMP"                    -> "TIMESTAMP"
            else                           -> type.ifBlank { "" }
        }

    fun findTableDescription(name: String): String? =
        tableDescCache[name.uppercase()]?.takeIf { it.isNotBlank() }

    fun findColumnDescription(name: String): String? =
        columnDescCache[name.uppercase()]?.takeIf { it.isNotBlank() }

    fun getTableCompletions(): Map<String, String> = tableDescCache.toMap()
    fun getColumnCompletions(): Map<String, String> = columnDescCache.toMap()

    fun disconnect() {
        connection?.close()
        connection = null
        currentSchemas = emptyList()
        tableDescCache.clear()
        columnDescCache.clear()
    }

    override fun dispose() = disconnect()
}

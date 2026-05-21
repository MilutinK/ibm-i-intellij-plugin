package com.github.milutink.ibmiintellijplugin.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.net.UnknownHostException
import java.sql.Connection
import java.sql.DriverManager
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

    fun connectH2(schemas: List<String> = emptyList()) {
        currentSchemas = schemas
        Class.forName("org.h2.Driver")
        connection = DriverManager.getConnection("jdbc:h2:mem:ibmi_mock;DB_CLOSE_DELAY=-1", "sa", "")
        setupH2Schema()
        insertH2TestData()
    }

    fun connectIBMi(host: String, port: String, user: String, password: String, schemas: List<String> = emptyList()) {
        currentSchemas = schemas
        Class.forName("com.ibm.as400.access.AS400JDBCDriver")
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
            DriverManager.getConnection("jdbc:as400://$host", props)
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

    // --- H2 setup -----------------------------------------------------------

    private fun setupH2Schema() {
        val stmt = connection!!.createStatement()
        stmt.execute("CREATE SCHEMA IF NOT EXISTS QSYS2")
        stmt.execute("""
            CREATE TABLE IF NOT EXISTS QSYS2.SYSTABLES (
                TABLE_NAME   VARCHAR(128) NOT NULL,
                TABLE_SCHEMA VARCHAR(128) NOT NULL,
                TABLE_TEXT   VARCHAR(50)
            )
        """)
        stmt.execute("""
            CREATE TABLE IF NOT EXISTS QSYS2.SYSCOLUMNS (
                TABLE_NAME        VARCHAR(128) NOT NULL,
                TABLE_SCHEMA      VARCHAR(128) NOT NULL,
                COLUMN_NAME       VARCHAR(128) NOT NULL,
                COLUMN_TEXT       VARCHAR(50),
                DATA_TYPE         VARCHAR(30),
                LENGTH            INTEGER DEFAULT 0,
                NUMERIC_PRECISION INTEGER DEFAULT 0,
                NUMERIC_SCALE     INTEGER DEFAULT 0,
                ORDINAL_POSITION  INTEGER DEFAULT 0
            )
        """)
    }

    private fun insertH2TestData() {
        val stmt = connection!!.createStatement()
        stmt.execute("TRUNCATE TABLE QSYS2.SYSCOLUMNS")
        stmt.execute("TRUNCATE TABLE QSYS2.SYSTABLES")
        val tables = listOf(
            Triple("CUSTTBL",  "MYLIB", "Kundenstammdaten"),
            Triple("ORDTBL",   "MYLIB", "Bestellungen"),
            Triple("ARTLST",   "MYLIB", "Artikelliste"),
            Triple("INVTBL",   "MYLIB", "Rechnungen"),
            Triple("SHIPTBL",  "MYLIB", "Lieferungen"),
            Triple("EMPTBL",   "HRLIB", "Mitarbeiterdaten"),
            Triple("DEPTTBL",  "HRLIB", "Abteilungen"),
            Triple("SALPYTBL", "HRLIB", "Gehaltszahlungen"),
        )

        // name, desc, dataType, len, precision, scale
        data class Col(val name: String, val desc: String, val type: String,
                       val len: Int = 0, val prec: Int = 0, val scale: Int = 0)

        val columns = listOf(
            listOf("MYLIB", "CUSTTBL") to listOf(
                Col("CUSTNBR", "Kundennummer",   "DECIMAL",   prec=7),
                Col("CUSTNAM", "Kundenname",      "CHARACTER", len=30),
                Col("CUSTADR", "Kundenadresse",   "CHARACTER", len=50),
                Col("CUSTCTY", "Stadt",            "CHARACTER", len=25),
                Col("CUSTZIP", "Postleitzahl",     "CHARACTER", len=10),
                Col("CUSTCNT", "Land",             "CHARACTER", len=3),
                Col("CUSTPHN", "Telefonnummer",    "CHARACTER", len=15),
                Col("CUSTBAL", "Kontostand",       "DECIMAL",   prec=11, scale=2),
                Col("CREATDT", "Erstellungsdatum", "DATE"),
            ),
            listOf("MYLIB", "ORDTBL") to listOf(
                Col("ORDNBR",  "Bestellnummer",  "DECIMAL",   prec=9),
                Col("CUSTNBR", "Kundennummer",   "DECIMAL",   prec=7),
                Col("ORDDAT",  "Bestelldatum",   "DATE"),
                Col("DELDAT",  "Lieferdatum",    "DATE"),
                Col("ORDSTS",  "Bestellstatus",  "CHARACTER", len=1),
                Col("ORDTOT",  "Gesamtbetrag",   "DECIMAL",   prec=11, scale=2),
            ),
            listOf("MYLIB", "ARTLST") to listOf(
                Col("ARTNBR",  "Artikelnummer",      "DECIMAL",   prec=7),
                Col("ARTDSC",  "Artikelbeschreibung", "VARCHAR",   len=100),
                Col("ARTPRC",  "Artikelpreis",        "DECIMAL",   prec=9, scale=2),
                Col("ARTQTY",  "Lagerbestand",        "INTEGER"),
                Col("ARTCAT",  "Artikelkategorie",    "CHARACTER", len=10),
            ),
            listOf("MYLIB", "INVTBL") to listOf(
                Col("INVNBR",  "Rechnungsnummer", "DECIMAL",   prec=9),
                Col("ORDNBR",  "Bestellnummer",   "DECIMAL",   prec=9),
                Col("INVDAT",  "Rechnungsdatum",  "DATE"),
                Col("INVAMT",  "Rechnungsbetrag", "DECIMAL",   prec=11, scale=2),
                Col("PAYSTS",  "Zahlungsstatus",  "CHARACTER", len=1),
            ),
            listOf("HRLIB", "EMPTBL") to listOf(
                Col("EMPNBR",  "Mitarbeiternummer", "DECIMAL",   prec=7),
                Col("EMPFNM",  "Vorname",            "CHARACTER", len=20),
                Col("EMPLNM",  "Nachname",           "CHARACTER", len=30),
                Col("DEPTNBR", "Abteilungsnummer",   "DECIMAL",   prec=5),
                Col("HIREDT",  "Einstellungsdatum",  "DATE"),
                Col("JOBTTL",  "Berufsbezeichnung",  "VARCHAR",   len=50),
                Col("SALAMT",  "Gehalt",             "DECIMAL",   prec=9, scale=2),
            ),
            listOf("HRLIB", "DEPTTBL") to listOf(
                Col("DEPTNBR", "Abteilungsnummer", "DECIMAL",   prec=5),
                Col("DEPTNM",  "Abteilungsname",   "CHARACTER", len=30),
                Col("MGRNUM",  "Managernummer",    "DECIMAL",   prec=7),
                Col("DEPTLOC", "Standort",         "CHARACTER", len=25),
            ),
        )

        val tblStmt = connection!!.prepareStatement(
            "INSERT INTO QSYS2.SYSTABLES (TABLE_NAME, TABLE_SCHEMA, TABLE_TEXT) VALUES (?, ?, ?)"
        )
        tables.forEach { (name, schema, text) ->
            tblStmt.setString(1, name); tblStmt.setString(2, schema); tblStmt.setString(3, text)
            tblStmt.addBatch()
        }
        tblStmt.executeBatch()

        val colStmt = connection!!.prepareStatement(
            """INSERT INTO QSYS2.SYSCOLUMNS
               (TABLE_NAME, TABLE_SCHEMA, COLUMN_NAME, COLUMN_TEXT,
                DATA_TYPE, LENGTH, NUMERIC_PRECISION, NUMERIC_SCALE, ORDINAL_POSITION)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"""
        )
        columns.forEach { (key, cols) ->
            val (schema, tableName) = key
            cols.forEachIndexed { idx, col ->
                colStmt.setString(1, tableName); colStmt.setString(2, schema)
                colStmt.setString(3, col.name);  colStmt.setString(4, col.desc)
                colStmt.setString(5, col.type);  colStmt.setInt(6, col.len)
                colStmt.setInt(7, col.prec);     colStmt.setInt(8, col.scale)
                colStmt.setInt(9, idx + 1)
                colStmt.addBatch()
            }
        }
        colStmt.executeBatch()
    }
}

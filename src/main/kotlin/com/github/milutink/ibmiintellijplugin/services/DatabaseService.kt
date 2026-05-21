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
data class ColumnInfo(val tableName: String, val columnName: String, val description: String)

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
            """SELECT COLUMN_NAME, COLUMN_TEXT
               FROM QSYS2.SYSCOLUMNS
               WHERE TABLE_NAME = ? AND TABLE_SCHEMA = ?
               ORDER BY COLUMN_NAME"""
        ).apply {
            setString(1, tableName)
            setString(2, schema)
        }.executeQuery().use { rs ->
            while (rs.next()) {
                result += ColumnInfo(
                    tableName   = tableName,
                    columnName  = rs.getString("COLUMN_NAME"),
                    description = rs.getString("COLUMN_TEXT") ?: ""
                )
            }
        }
        result.forEach { columnDescCache.putIfAbsent(it.columnName.uppercase(), it.description) }
        return result
    }

    fun findTableDescription(name: String): String? =
        tableDescCache[name.uppercase()]?.takeIf { it.isNotBlank() }

    fun findColumnDescription(name: String): String? =
        columnDescCache[name.uppercase()]?.takeIf { it.isNotBlank() }

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
                TABLE_NAME   VARCHAR(128) NOT NULL,
                TABLE_SCHEMA VARCHAR(128) NOT NULL,
                COLUMN_NAME  VARCHAR(128) NOT NULL,
                COLUMN_TEXT  VARCHAR(50)
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

        val columns = listOf(
            listOf("MYLIB", "CUSTTBL") to listOf(
                "CUSTNBR" to "Kundennummer",
                "CUSTNAM" to "Kundenname",
                "CUSTADR" to "Kundenadresse",
                "CUSTCTY" to "Stadt",
                "CUSTZIP" to "Postleitzahl",
                "CUSTCNT" to "Land",
                "CUSTPHN" to "Telefonnummer",
                "CUSTBAL" to "Kontostand",
                "CREATDT" to "Erstellungsdatum",
            ),
            listOf("MYLIB", "ORDTBL") to listOf(
                "ORDNBR"  to "Bestellnummer",
                "CUSTNBR" to "Kundennummer",
                "ORDDAT"  to "Bestelldatum",
                "DELDAT"  to "Lieferdatum",
                "ORDSTS"  to "Bestellstatus",
                "ORDTOT"  to "Gesamtbetrag",
            ),
            listOf("MYLIB", "ARTLST") to listOf(
                "ARTNBR"  to "Artikelnummer",
                "ARTDSC"  to "Artikelbeschreibung",
                "ARTPRC"  to "Artikelpreis",
                "ARTQTY"  to "Lagerbestand",
                "ARTCAT"  to "Artikelkategorie",
            ),
            listOf("MYLIB", "INVTBL") to listOf(
                "INVNBR"  to "Rechnungsnummer",
                "ORDNBR"  to "Bestellnummer",
                "INVDAT"  to "Rechnungsdatum",
                "INVAMT"  to "Rechnungsbetrag",
                "PAYSTS"  to "Zahlungsstatus",
            ),
            listOf("HRLIB", "EMPTBL") to listOf(
                "EMPNBR"  to "Mitarbeiternummer",
                "EMPFNM"  to "Vorname",
                "EMPLNM"  to "Nachname",
                "DEPTNBR" to "Abteilungsnummer",
                "HIREDT"  to "Einstellungsdatum",
                "JOBTTL"  to "Berufsbezeichnung",
                "SALAMT"  to "Gehalt",
            ),
            listOf("HRLIB", "DEPTTBL") to listOf(
                "DEPTNBR" to "Abteilungsnummer",
                "DEPTNM"  to "Abteilungsname",
                "MGRNUM"  to "Managernummer",
                "DEPTLOC" to "Standort",
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
            "INSERT INTO QSYS2.SYSCOLUMNS (TABLE_NAME, TABLE_SCHEMA, COLUMN_NAME, COLUMN_TEXT) VALUES (?, ?, ?, ?)"
        )
        columns.forEach { (key, cols) ->
            val (schema, tableName) = key
            cols.forEach { (colName, colText) ->
                colStmt.setString(1, tableName); colStmt.setString(2, schema)
                colStmt.setString(3, colName);   colStmt.setString(4, colText)
                colStmt.addBatch()
            }
        }
        colStmt.executeBatch()
    }
}

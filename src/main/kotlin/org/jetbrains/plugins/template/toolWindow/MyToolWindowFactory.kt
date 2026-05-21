package org.jetbrains.plugins.template.toolWindow

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.table.JBTable
import org.jetbrains.plugins.template.services.ColumnInfo
import org.jetbrains.plugins.template.services.DatabaseService
import org.jetbrains.plugins.template.services.TableInfo
import java.awt.BorderLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.*
import javax.swing.border.EmptyBorder
import javax.swing.border.TitledBorder
import javax.swing.table.DefaultTableModel

class MyToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = IbmiPanel(project)
        val content = ContentFactory.getInstance().createContent(panel.root, null, false)
        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project) = true
}

class IbmiPanel(project: Project) {

    private val db    = project.service<DatabaseService>()
    private val prefs = PropertiesComponent.getInstance()

    // --- Connection form fields ---
    private val testModeCheckbox = JCheckBox("Test-Modus (H2, keine IBM i nötig)", true)
    private val hostField        = JBTextField(20)
    private val portField        = JBTextField("446", 6)
    private val userField        = JBTextField(20)
    private val passwordField    = JBPasswordField().also { it.columns = 20 }
    private val schemaField      = JBTextField(20).also {
        it.toolTipText = "Kommagetrennt, z.B. MYLIB,HRLIB  (Pflicht für IBM i — leer nur im Test-Modus)"
    }
    private val ibmiFieldsPanel  = buildIbmiFieldsPanel()
    private val connectButton    = JButton("Verbinden")
    private val disconnectButton = JButton("Trennen").also { it.isEnabled = false }
    private val statusLabel      = JBLabel("Nicht verbunden")

    // --- Results ---
    private val tableModel  = DefaultTableModel(arrayOf("Tabelle", "Schema", "Beschreibung"), 0)
    private val resultTable = JBTable(tableModel).also { it.isStriped = true }
    private val columnModel = DefaultTableModel(arrayOf("Spalte", "Beschreibung"), 0)
    private val columnTable = JBTable(columnModel).also { it.isStriped = true }
    private val searchField = JBTextField(20)

    val root: JPanel = buildUI()

    init {
        testModeCheckbox.isSelected = prefs.getBoolean("ibmi.testMode", true)
        hostField.text   = prefs.getValue("ibmi.host",    "")
        portField.text   = prefs.getValue("ibmi.port",    "446")
        userField.text   = prefs.getValue("ibmi.user",    "")
        schemaField.text = prefs.getValue("ibmi.schemas", "")

        val savedUser = prefs.getValue("ibmi.user", "")
        if (savedUser.isNotEmpty()) {
            val savedPw = PasswordSafe.instance.getPassword(CredentialAttributes("IBM i Helper", savedUser))
            if (savedPw != null) passwordField.text = savedPw
        }

        updateFieldVisibility()
    }

    private fun buildUI(): JPanel {
        val root = JBPanel<JBPanel<*>>(BorderLayout(0, 8))
        root.border = EmptyBorder(8, 8, 8, 8)

        root.add(buildConnectionPanel(), BorderLayout.NORTH)
        root.add(buildResultsPanel(),    BorderLayout.CENTER)

        testModeCheckbox.addActionListener { updateFieldVisibility() }
        connectButton.addActionListener    { onConnect() }
        disconnectButton.addActionListener { onDisconnect() }

        resultTable.selectionModel.addListSelectionListener {
            if (!it.valueIsAdjusting) onTableSelected()
        }

        searchField.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent?)  = applyFilter()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent?)  = applyFilter()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = applyFilter()
        })

        return root
    }

    private fun buildIbmiFieldsPanel(): JPanel {
        val panel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            insets = Insets(3, 4, 3, 4)
        }
        var row = 0
        fun addRow(label: String, field: JComponent) {
            gbc.gridwidth = 1; gbc.weightx = 0.0; gbc.gridx = 0; gbc.gridy = row
            panel.add(JBLabel(label), gbc)
            gbc.gridx = 1; gbc.weightx = 1.0
            panel.add(field, gbc)
            row++
        }
        addRow("Host:",     hostField)
        addRow("Port:",     portField)
        addRow("Benutzer:", userField)
        addRow("Passwort:", passwordField)
        return panel
    }

    private fun buildConnectionPanel(): JPanel {
        val panel = JPanel(GridBagLayout())
        panel.border = TitledBorder("Verbindung")

        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            insets = Insets(3, 4, 3, 4)
            gridwidth = 1; weightx = 1.0; gridx = 0
        }
        var row = 0

        gbc.gridy = row++; panel.add(testModeCheckbox, gbc)
        gbc.gridy = row++; panel.add(ibmiFieldsPanel, gbc)

        // Schema / library filter — always visible
        val schemaRow = JPanel(GridBagLayout())
        val sgbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL; insets = Insets(3, 4, 3, 4)
        }
        sgbc.gridx = 0; sgbc.weightx = 0.0; schemaRow.add(JBLabel("Bibliotheken:"), sgbc)
        sgbc.gridx = 1; sgbc.weightx = 1.0; schemaRow.add(schemaField, sgbc)
        gbc.gridy = row++; panel.add(schemaRow, gbc)

        val btnPanel = JPanel().apply { add(connectButton); add(disconnectButton) }
        gbc.gridy = row++; panel.add(btnPanel,    gbc)
        gbc.gridy = row;   panel.add(statusLabel, gbc)

        return panel
    }

    private fun buildResultsPanel(): JPanel {
        val panel = JPanel(BorderLayout(0, 6))

        val searchPanel = JPanel(BorderLayout(4, 0))
        searchPanel.add(JBLabel("Suche:"), BorderLayout.WEST)
        searchPanel.add(searchField, BorderLayout.CENTER)
        panel.add(searchPanel, BorderLayout.NORTH)

        val splitPane = JSplitPane(JSplitPane.VERTICAL_SPLIT).apply {
            topComponent    = JBScrollPane(resultTable).also { it.border = TitledBorder("Tabellen") }
            bottomComponent = JBScrollPane(columnTable).also { it.border = TitledBorder("Spalten (Tabelle auswählen)") }
            resizeWeight = 0.6
            isContinuousLayout = true
        }
        panel.add(splitPane, BorderLayout.CENTER)

        return panel
    }

    // --- Actions ------------------------------------------------------------

    private fun parseSchemas(): List<String> =
        schemaField.text.split(",")
            .map { it.trim().uppercase() }
            .filter { it.isNotEmpty() }

    private fun onConnect() {
        val schemas = parseSchemas()

        if (!testModeCheckbox.isSelected && schemas.isEmpty()) {
            statusLabel.text = "Bitte mindestens eine Bibliothek angeben (z.B. deinen IBM i Benutzernamen)."
            return
        }

        connectButton.isEnabled = false
        statusLabel.text = "Verbinde ..."

        object : SwingWorker<List<TableInfo>, Void>() {
            override fun doInBackground(): List<TableInfo> {
                if (testModeCheckbox.isSelected) {
                    db.connectH2(schemas)
                } else {
                    val host     = hostField.text.trim()
                    val port     = portField.text.trim().ifEmpty { "446" }
                    val user     = userField.text.trim()
                    val password = String(passwordField.password)
                    if (host.isEmpty() || user.isEmpty() || password.isEmpty())
                        error("Bitte Host, Benutzer und Passwort angeben.")
                    db.connectIBMi(host, port, user, password, schemas)
                }
                return db.getTables()
            }

            override fun done() {
                try {
                    val tables = get()
                    populateTableModel(tables)
                    val mode = if (testModeCheckbox.isSelected) "H2 Test-Modus" else hostField.text.trim()
                    val schemaInfo = " [${schemas.joinToString(",")}]".takeIf { schemas.isNotEmpty() } ?: ""
                    val schemaCount = tables.map { it.schema }.distinct().size
                    val schemaCountStr = if (schemaCount > 1) ", $schemaCount Schemas" else ""
                    statusLabel.text = if (tables.isEmpty())
                        "Verbunden ($mode$schemaInfo) — keine Tabellen gefunden. Bibliothek existiert, enthält aber keine SQL-Tabellen."
                    else
                        "Verbunden ($mode$schemaInfo) — ${tables.size} Tabellen$schemaCountStr"
                    connectButton.isEnabled    = false
                    disconnectButton.isEnabled = true
                    testModeCheckbox.isEnabled = false
                    saveSettings()
                } catch (e: Exception) {
                    statusLabel.text = "Fehler: ${e.cause?.message ?: e.message}"
                    connectButton.isEnabled = true
                }
            }
        }.execute()
    }

    private fun saveSettings() {
        prefs.setValue("ibmi.testMode", testModeCheckbox.isSelected)
        prefs.setValue("ibmi.host",     hostField.text.trim())
        prefs.setValue("ibmi.port",     portField.text.trim())
        prefs.setValue("ibmi.user",     userField.text.trim())
        prefs.setValue("ibmi.schemas",  schemaField.text.trim())
        val user     = userField.text.trim()
        val password = String(passwordField.password)
        if (user.isNotEmpty() && password.isNotEmpty()) {
            PasswordSafe.instance.set(
                CredentialAttributes("IBM i Helper", user),
                Credentials(user, password)
            )
        }
    }

    private fun onDisconnect() {
        db.disconnect()
        tableModel.rowCount  = 0
        columnModel.rowCount = 0
        statusLabel.text           = "Nicht verbunden"
        connectButton.isEnabled    = true
        disconnectButton.isEnabled = false
        testModeCheckbox.isEnabled = true
        ((columnTable.parent?.parent as? JComponent)?.border as? TitledBorder)
            ?.title = "Spalten (Tabelle auswählen)"
    }

    private fun onTableSelected() {
        val row = resultTable.selectedRow.takeIf { it >= 0 } ?: return
        val tableName = resultTable.getValueAt(row, 0) as String
        val schema    = resultTable.getValueAt(row, 1) as String

        object : SwingWorker<List<ColumnInfo>, Void>() {
            override fun doInBackground() = db.getColumns(tableName, schema)
            override fun done() {
                try {
                    val cols = get()
                    columnModel.rowCount = 0
                    cols.forEach { columnModel.addRow(arrayOf(it.columnName, it.description)) }
                    val scrollPane = columnTable.parent?.parent as? JComponent
                    (scrollPane?.border as? TitledBorder)?.title = "Spalten von $tableName ($schema)"
                    scrollPane?.repaint()
                } catch (_: Exception) {}
            }
        }.execute()
    }

    private fun applyFilter() {
        val query = searchField.text.trim().lowercase()
        if (!db.isConnected) return
        object : SwingWorker<List<TableInfo>, Void>() {
            override fun doInBackground() = db.getTables()
            override fun done() {
                try {
                    val filtered = get().filter {
                        query.isEmpty() ||
                        it.name.lowercase().contains(query) ||
                        it.description.lowercase().contains(query) ||
                        it.schema.lowercase().contains(query)
                    }
                    populateTableModel(filtered)
                } catch (_: Exception) {}
            }
        }.execute()
    }

    private fun populateTableModel(tables: List<TableInfo>) {
        tableModel.rowCount = 0
        tables.forEach { tableModel.addRow(arrayOf(it.name, it.schema, it.description)) }
    }

    private fun updateFieldVisibility() {
        ibmiFieldsPanel.isVisible = !testModeCheckbox.isSelected
    }
}

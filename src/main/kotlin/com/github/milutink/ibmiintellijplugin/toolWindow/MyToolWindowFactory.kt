package com.github.milutink.ibmiintellijplugin.toolWindow

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.table.JBTable
import com.github.milutink.ibmiintellijplugin.services.ColumnInfo
import com.github.milutink.ibmiintellijplugin.services.DatabaseService
import com.github.milutink.ibmiintellijplugin.services.TableInfo
import com.github.milutink.ibmiintellijplugin.statusBar.IbmiStatusBarWidgetFactory
import java.awt.BorderLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
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

class IbmiPanel(private val project: Project) {

    private val db    = project.service<DatabaseService>()
    private val prefs = PropertiesComponent.getInstance()

    // --- Connection form fields ---
    private val hostField        = JBTextField(20)
    private val portField        = JBTextField("446", 6)
    private val userField        = JBTextField(20)
    private val passwordField    = JBPasswordField().also { it.columns = 20 }
    private val schemaField      = JBTextField(20).also {
        it.toolTipText = "Kommagetrennt, z.B. MYLIB,HRLIB"
    }
    private val ibmiFieldsPanel  = buildIbmiFieldsPanel()
    private val connectButton    = JButton("Verbinden")
    private val disconnectButton = JButton("Trennen").also { it.isEnabled = false }
    private val statusLabel      = JBLabel("Nicht verbunden")

    // --- Profiles ---
    private val profileComboBox     = JComboBox<String>().also { it.isEditable = true }
    private val saveProfileButton   = JButton("Speichern")
    private val deleteProfileButton = JButton("Löschen")
    private var suppressProfileLoad = false

    // --- Results ---
    private val tableModel  = DefaultTableModel(arrayOf("Tabelle", "Schema", "Beschreibung"), 0)
    private val resultTable = JBTable(tableModel).also { it.isStriped = true }
    private val columnModel = DefaultTableModel(arrayOf("Spalte", "Typ", "Beschreibung"), 0)
    private val columnTable = JBTable(columnModel).also { it.isStriped = true }
    private val searchField = JBTextField(20)

    val root: JPanel = buildUI()

    init {
        hostField.text   = prefs.getValue("ibmi.host",    "")
        portField.text   = prefs.getValue("ibmi.port",    "446")
        userField.text   = prefs.getValue("ibmi.user",    "")
        schemaField.text = prefs.getValue("ibmi.schemas", "")

        val savedUser = prefs.getValue("ibmi.user", "")
        if (savedUser.isNotEmpty()) {
            val savedPw = PasswordSafe.instance.getPassword(CredentialAttributes("IBM i Helper", savedUser))
            if (savedPw != null) passwordField.text = savedPw
        }

        refreshProfileComboBox()
    }

    private fun buildUI(): JPanel {
        val root = JBPanel<JBPanel<*>>(BorderLayout(0, 8))
        root.border = EmptyBorder(8, 8, 8, 8)

        root.add(buildConnectionPanel(), BorderLayout.NORTH)
        root.add(buildResultsPanel(),    BorderLayout.CENTER)

        connectButton.addActionListener    { onConnect() }
        disconnectButton.addActionListener { onDisconnect() }
        saveProfileButton.addActionListener   { onSaveProfile() }
        deleteProfileButton.addActionListener { onDeleteProfile() }
        profileComboBox.addItemListener { e ->
            if (!suppressProfileLoad && e.stateChange == java.awt.event.ItemEvent.SELECTED) {
                val name = (e.item as? String)?.trim() ?: ""
                if (name.isNotBlank()) loadProfile(name)
            }
        }

        resultTable.selectionModel.addListSelectionListener {
            if (!it.valueIsAdjusting) onTableSelected()
        }

        resultTable.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent)  { if (e.isPopupTrigger) showTablePopup(e) }
            override fun mouseReleased(e: MouseEvent) { if (e.isPopupTrigger) showTablePopup(e) }
        })
        columnTable.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent)  { if (e.isPopupTrigger) showColumnPopup(e) }
            override fun mouseReleased(e: MouseEvent) { if (e.isPopupTrigger) showColumnPopup(e) }
        })

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

        val profileRow = JPanel(GridBagLayout())
        val pgbc = GridBagConstraints().apply { fill = GridBagConstraints.HORIZONTAL; insets = Insets(3, 4, 3, 4) }
        pgbc.gridx = 0; pgbc.weightx = 0.0; profileRow.add(JBLabel("Profil:"), pgbc)
        pgbc.gridx = 1; pgbc.weightx = 1.0; profileRow.add(profileComboBox, pgbc)
        pgbc.gridx = 2; pgbc.weightx = 0.0; profileRow.add(saveProfileButton, pgbc)
        pgbc.gridx = 3; pgbc.weightx = 0.0; profileRow.add(deleteProfileButton, pgbc)
        gbc.gridy = row++; panel.add(profileRow, gbc)

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
        if (schemas.isEmpty()) {
            statusLabel.text = "Bitte mindestens eine Bibliothek angeben (z.B. deinen IBM i Benutzernamen)."
            return
        }

        val host     = hostField.text.trim()
        val port     = portField.text.trim().ifEmpty { "446" }
        val user     = userField.text.trim()
        val password = String(passwordField.password)
        if (host.isEmpty() || user.isEmpty() || password.isEmpty()) {
            statusLabel.text = "Bitte Host, Benutzer und Passwort angeben."
            return
        }

        connectButton.isEnabled = false
        statusLabel.text = "Verbinde ..."

        object : SwingWorker<List<TableInfo>, Void>() {
            override fun doInBackground(): List<TableInfo> {
                db.connectIBMi(host, port, user, password, schemas)
                return db.getTables()
            }

            override fun done() {
                try {
                    val tables = get()
                    populateTableModel(tables)
                    val schemaInfo = " [${schemas.joinToString(",")}]"
                    val schemaCount = tables.map { it.schema }.distinct().size
                    val schemaCountStr = if (schemaCount > 1) ", $schemaCount Schemas" else ""
                    statusLabel.text = if (tables.isEmpty())
                        "Verbunden ($host$schemaInfo) — keine Tabellen gefunden."
                    else
                        "Verbunden ($host$schemaInfo) — ${tables.size} Tabellen$schemaCountStr"
                    connectButton.isEnabled    = false
                    disconnectButton.isEnabled = true
                    saveSettings()
                    updateStatusBar()
                } catch (e: Exception) {
                    statusLabel.text = "Fehler: ${e.cause?.message ?: e.message}"
                    connectButton.isEnabled = true
                }
            }
        }.execute()
    }

    private fun saveSettings() {
        prefs.setValue("ibmi.host",    hostField.text.trim())
        prefs.setValue("ibmi.port",    portField.text.trim())
        prefs.setValue("ibmi.user",    userField.text.trim())
        prefs.setValue("ibmi.schemas", schemaField.text.trim())
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
        ((columnTable.parent?.parent as? JComponent)?.border as? TitledBorder)
            ?.title = "Spalten (Tabelle auswählen)"
        updateStatusBar()
    }

    private fun updateStatusBar() {
        WindowManager.getInstance().getStatusBar(project)
            ?.updateWidget(IbmiStatusBarWidgetFactory.ID)
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
                    cols.forEach { columnModel.addRow(arrayOf(it.columnName, it.dataType, it.description)) }
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

    private fun showTablePopup(e: MouseEvent) {
        val row = resultTable.rowAtPoint(e.point).also {
            if (it < 0) return
            resultTable.setRowSelectionInterval(it, it)
        }
        val name = resultTable.getValueAt(row, 0) as String
        val desc = resultTable.getValueAt(row, 2) as String
        JPopupMenu().apply {
            add(JMenuItem("Name kopieren")).addActionListener          { copyToClipboard(name) }
            add(JMenuItem("Beschreibung kopieren")).addActionListener  { copyToClipboard(desc) }
            add(JMenuItem("Beides kopieren")).addActionListener        { copyToClipboard("$name – $desc") }
        }.show(e.component, e.x, e.y)
    }

    private fun showColumnPopup(e: MouseEvent) {
        val row = columnTable.rowAtPoint(e.point).also {
            if (it < 0) return
            columnTable.setRowSelectionInterval(it, it)
        }
        val name = columnTable.getValueAt(row, 0) as String
        val type = columnTable.getValueAt(row, 1) as String
        val desc = columnTable.getValueAt(row, 2) as String
        val typeStr = if (type.isNotBlank()) " ($type)" else ""
        JPopupMenu().apply {
            add(JMenuItem("Name kopieren")).addActionListener          { copyToClipboard(name) }
            add(JMenuItem("Beschreibung kopieren")).addActionListener  { copyToClipboard(desc) }
            add(JMenuItem("Beides kopieren")).addActionListener        { copyToClipboard("$name$typeStr – $desc") }
        }.show(e.component, e.x, e.y)
    }

    private fun copyToClipboard(text: String) {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }

    // --- Profile management ------------------------------------------------

    private fun profileKey(name: String, field: String) = "ibmi.profile.$name.$field"

    private fun loadProfileNames(): List<String> =
        prefs.getValue("ibmi.profiles", "").split("|").filter { it.isNotBlank() }

    private fun saveProfileNames(names: List<String>) =
        prefs.setValue("ibmi.profiles", names.distinct().joinToString("|"))

    private fun refreshProfileComboBox() {
        suppressProfileLoad = true
        try {
            val current = (profileComboBox.editor.item as? String) ?: ""
            profileComboBox.removeAllItems()
            profileComboBox.addItem("")
            loadProfileNames().forEach { profileComboBox.addItem(it) }
            val names = loadProfileNames()
            if (current.isNotBlank() && names.contains(current)) profileComboBox.selectedItem = current
            else profileComboBox.selectedIndex = 0
        } finally {
            suppressProfileLoad = false
        }
    }

    private fun loadProfile(name: String) {
        hostField.text   = prefs.getValue(profileKey(name, "host"),    "")
        portField.text   = prefs.getValue(profileKey(name, "port"),    "446")
        userField.text   = prefs.getValue(profileKey(name, "user"),    "")
        schemaField.text = prefs.getValue(profileKey(name, "schemas"), "")
        val user = userField.text
        if (user.isNotEmpty()) {
            val pw = PasswordSafe.instance.getPassword(CredentialAttributes("IBM i Helper/$name", user))
            if (pw != null) passwordField.text = pw
        }
    }

    private fun onSaveProfile() {
        val name = (profileComboBox.editor.item as? String)?.trim()?.replace("|", "") ?: ""
        if (name.isEmpty()) {
            JOptionPane.showMessageDialog(root, "Bitte Profilnamen eingeben.", "Profil speichern", JOptionPane.WARNING_MESSAGE)
            return
        }
        prefs.setValue(profileKey(name, "host"),    hostField.text.trim())
        prefs.setValue(profileKey(name, "port"),    portField.text.trim())
        prefs.setValue(profileKey(name, "user"),    userField.text.trim())
        prefs.setValue(profileKey(name, "schemas"), schemaField.text.trim())
        val user = userField.text.trim()
        val password = String(passwordField.password)
        if (user.isNotEmpty() && password.isNotEmpty()) {
            PasswordSafe.instance.set(
                CredentialAttributes("IBM i Helper/$name", user),
                Credentials(user, password)
            )
        }
        saveProfileNames(loadProfileNames() + name)
        refreshProfileComboBox()
        suppressProfileLoad = true
        try { profileComboBox.selectedItem = name } finally { suppressProfileLoad = false }
    }

    private fun onDeleteProfile() {
        val name = (profileComboBox.selectedItem as? String)?.trim()?.takeIf { it.isNotBlank() } ?: return
        saveProfileNames(loadProfileNames().filter { it != name })
        listOf("host", "port", "user", "schemas").forEach {
            prefs.unsetValue(profileKey(name, it))
        }
        refreshProfileComboBox()
    }
}

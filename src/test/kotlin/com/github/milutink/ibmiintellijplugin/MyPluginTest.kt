package com.github.milutink.ibmiintellijplugin

import com.github.milutink.ibmiintellijplugin.services.DatabaseService
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class MyPluginTest : BasePlatformTestCase() {

    private lateinit var db: DatabaseService

    override fun setUp() {
        super.setUp()
        db = DatabaseService(project)
        db.connectH2(listOf("MYLIB", "HRLIB"))
    }

    override fun tearDown() {
        db.disconnect()
        super.tearDown()
    }

    fun testH2ConnectAndLoadTables() {
        assertTrue("Should be connected after connectH2()", db.isConnected)
        val tables = db.getTables()
        assertTrue("H2 test data should contain tables", tables.isNotEmpty())
    }

    fun testTableDescriptions() {
        db.getTables()
        assertNotNull("CUSTTBL should have a description", db.findTableDescription("CUSTTBL"))
        assertNotNull("ORDTBL should have a description", db.findTableDescription("ORDTBL"))
        assertNull("Unknown table should return null", db.findTableDescription("DOESNOTEXIST"))
    }

    fun testColumnDescriptions() {
        db.getTables()
        assertNotNull("CUSTNBR should have a description", db.findColumnDescription("CUSTNBR"))
        assertNotNull("ORDDAT should have a description", db.findColumnDescription("ORDDAT"))
        assertNull("Unknown column should return null", db.findColumnDescription("DOESNOTEXIST"))
    }

    fun testSchemaFilter() {
        db.disconnect()
        db.connectH2(listOf("MYLIB"))
        val tables = db.getTables()
        assertTrue("All tables should be from MYLIB", tables.all { it.schema == "MYLIB" })
        assertTrue("HRLIB tables should be excluded", tables.none { it.schema == "HRLIB" })
    }

    fun testDisconnect() {
        assertTrue(db.isConnected)
        db.disconnect()
        assertFalse("Should not be connected after disconnect()", db.isConnected)
    }
}

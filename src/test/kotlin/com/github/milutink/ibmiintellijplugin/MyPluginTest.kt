package com.github.milutink.ibmiintellijplugin

import com.github.milutink.ibmiintellijplugin.services.DatabaseService
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class MyPluginTest : BasePlatformTestCase() {

    private lateinit var db: DatabaseService

    override fun setUp() {
        super.setUp()
        db = DatabaseService(project)
    }

    override fun tearDown() {
        db.disconnect()
        super.tearDown()
    }

    fun testNotConnectedByDefault() {
        assertFalse("Should not be connected before connecting", db.isConnected)
    }

    fun testDisconnectWhenNotConnected() {
        db.disconnect()
        assertFalse("Should remain disconnected after redundant disconnect()", db.isConnected)
    }

    fun testCachesEmptyWhenNotConnected() {
        assertNull("Table cache should be empty when not connected", db.findTableDescription("CUSTTBL"))
        assertNull("Column cache should be empty when not connected", db.findColumnDescription("CUSTNBR"))
    }

    fun testCompletionsEmptyWhenNotConnected() {
        assertTrue("Table completions should be empty when not connected", db.getTableCompletions().isEmpty())
        assertTrue("Column completions should be empty when not connected", db.getColumnCompletions().isEmpty())
    }
}

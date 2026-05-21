package com.github.milutink.ibmiintellijplugin.statusBar

import com.github.milutink.ibmiintellijplugin.services.DatabaseService
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.util.Consumer
import java.awt.event.MouseEvent

class IbmiStatusBarWidgetFactory : StatusBarWidgetFactory {
    companion object {
        const val ID = "IbmiConnectionStatus"
    }

    override fun getId() = ID
    override fun getDisplayName() = "IBM i Verbindungsstatus"
    override fun isAvailable(project: Project) = true
    override fun createWidget(project: Project) = IbmiStatusBarWidget(project)
    override fun disposeWidget(widget: StatusBarWidget) = widget.dispose()
    override fun canBeEnabledOn(statusBar: StatusBar) = true
}

class IbmiStatusBarWidget(private val project: Project) : StatusBarWidget, StatusBarWidget.TextPresentation {

    override fun ID() = IbmiStatusBarWidgetFactory.ID

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun getText(): String {
        val db = project.service<DatabaseService>()
        return if (db.isConnected) "IBM i: verbunden" else "IBM i: getrennt"
    }

    override fun getTooltipText() = getText()

    override fun getAlignment() = 0f

    override fun getClickConsumer(): Consumer<MouseEvent>? = null

    override fun install(statusBar: StatusBar) {}

    override fun dispose() {}
}

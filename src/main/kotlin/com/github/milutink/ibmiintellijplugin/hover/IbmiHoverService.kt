package com.github.milutink.ibmiintellijplugin.hover

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
class IbmiHoverService(project: Project) : Disposable {

    private val listener = IbmiHoverListener(project)

    init {
        EditorFactory.getInstance().eventMulticaster
            .addEditorMouseMotionListener(listener, this)
    }

    override fun dispose() {
        listener.dispose()
    }
}

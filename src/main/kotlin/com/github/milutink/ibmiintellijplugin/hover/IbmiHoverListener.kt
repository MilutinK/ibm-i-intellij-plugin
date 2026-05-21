package com.github.milutink.ibmiintellijplugin.hover

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseEventArea
import com.intellij.openapi.editor.event.EditorMouseMotionListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.awt.RelativePoint
import com.github.milutink.ibmiintellijplugin.services.DatabaseService
import java.awt.Point
import javax.swing.JLabel
import javax.swing.Timer

class IbmiHoverListener(private val project: Project) : EditorMouseMotionListener, Disposable {

    private var hoverTimer: Timer? = null
    private var activeBalloon: Balloon? = null

    override fun mouseMoved(e: EditorMouseEvent) {
        if (e.area != EditorMouseEventArea.EDITING_AREA) return

        activeBalloon?.hide()
        activeBalloon = null

        val editor = e.editor
        val mousePoint = Point(e.mouseEvent.point)
        val offset = editor.logicalPositionToOffset(editor.xyToLogicalPosition(mousePoint))

        hoverTimer?.stop()
        hoverTimer = Timer(500) { showHint(editor, offset, mousePoint) }.also {
            it.isRepeats = false
            it.start()
        }
    }

    private fun showHint(editor: Editor, offset: Int, mousePoint: Point) {
        if (editor.isDisposed) return
        val db = project.serviceIfCreated<DatabaseService>() ?: return
        if (!db.isConnected) return

        val word = wordAt(editor, offset) ?: return
        val desc = db.findColumnDescription(word) ?: db.findTableDescription(word) ?: return

        val balloon = JBPopupFactory.getInstance()
            .createBalloonBuilder(JLabel("<html><b>$word</b> &mdash; $desc</html>"))
            .setHideOnClickOutside(true)
            .setHideOnKeyOutside(true)
            .setHideOnAction(true)
            .setFadeoutTime(4000)
            .setAnimationCycle(100)
            .createBalloon()

        activeBalloon = balloon
        balloon.show(RelativePoint(editor.contentComponent, mousePoint), Balloon.Position.above)
    }

    private fun wordAt(editor: Editor, offset: Int): String? {
        val text = editor.document.charsSequence
        if (offset < 0 || offset >= text.length) return null
        if (!isWordChar(text[offset])) return null
        var start = offset
        while (start > 0 && isWordChar(text[start - 1])) start--
        var end = offset
        while (end < text.length && isWordChar(text[end])) end++
        val word = text.substring(start, end)
        return if (word.length >= 3) word else null
    }

    private fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '_'

    override fun dispose() {
        hoverTimer?.stop()
        activeBalloon?.hide()
    }
}

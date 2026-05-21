package org.jetbrains.plugins.template.documentation

import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.psi.PsiElement
import org.jetbrains.plugins.template.services.DatabaseService

class IbmiDocumentationProvider : AbstractDocumentationProvider() {

    override fun generateDoc(element: PsiElement?, originalElement: PsiElement?): String? {
        val project = (originalElement ?: element)?.project ?: return null
        val db = project.serviceIfCreated<DatabaseService>() ?: return null
        if (!db.isConnected) return null

        val word = (originalElement ?: element)?.text?.trim() ?: return null
        if (word.isBlank() || word.length > 128) return null

        val tableDesc = db.findTableDescription(word)
        if (tableDesc != null) return buildDoc(word, "Tabelle", tableDesc)

        val colDesc = db.findColumnDescription(word)
        if (colDesc != null) return buildDoc(word, "Spalte", colDesc)

        return null
    }

    private fun buildDoc(name: String, type: String, description: String): String =
        "<div class='definition'><pre>$name</pre></div>" +
        "<div class='content'><p><b>IBM i $type</b></p><p>$description</p></div>"
}

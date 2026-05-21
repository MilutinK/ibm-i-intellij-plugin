package com.github.milutink.ibmiintellijplugin.completion

import com.github.milutink.ibmiintellijplugin.services.DatabaseService
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.patterns.PlatformPatterns
import com.intellij.util.ProcessingContext

class IbmiCompletionContributor : CompletionContributor() {
    init {
        extend(CompletionType.BASIC, PlatformPatterns.psiElement(), IbmiCompletionProvider())
    }
}

class IbmiCompletionProvider : CompletionProvider<CompletionParameters>() {
    override fun addCompletions(
        parameters: CompletionParameters,
        context: ProcessingContext,
        result: CompletionResultSet
    ) {
        val project = parameters.editor.project ?: return
        val db = project.serviceIfCreated<DatabaseService>() ?: return
        if (!db.isConnected) return

        val prefix = result.prefixMatcher.prefix
        if (prefix.length < 2) return

        db.getTableCompletions().forEach { (name, desc) ->
            result.addElement(
                LookupElementBuilder.create(name)
                    .withTypeText("IBM i Tabelle")
                    .withTailText(desc.takeIf { it.isNotBlank() }?.let { "  $it" } ?: "", true)
            )
        }
        db.getColumnCompletions().forEach { (name, desc) ->
            result.addElement(
                LookupElementBuilder.create(name)
                    .withTypeText("IBM i Spalte")
                    .withTailText(desc.takeIf { it.isNotBlank() }?.let { "  $it" } ?: "", true)
            )
        }
    }
}

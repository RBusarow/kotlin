/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.maven

import com.intellij.codeInspection.CommonProblemDescriptor
import com.intellij.codeInspection.QuickFix
import com.intellij.openapi.project.Project

// BUNCH: 193
@Suppress("UNUSED_PARAMETER")
fun <D : CommonProblemDescriptor> applyQuickFix(fix: QuickFix<D>, project: Project, problemDescriptor: D) {
    fix.applyFix(problemDescriptor)
}
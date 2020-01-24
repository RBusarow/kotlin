/*
 * Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.daemon.experimental

import org.jetbrains.kotlin.cli.common.repl.IReplStageState
import org.jetbrains.kotlin.cli.common.repl.ReplCompiler
import org.jetbrains.kotlin.daemon.KotlinJvmReplServiceBase
import org.jetbrains.kotlin.daemon.common.CompileService
import org.jetbrains.kotlin.daemon.common.experimental.socketInfrastructure.ServerSocketWrapper
import org.jetbrains.kotlin.daemon.getValidId
import java.util.*
import kotlin.concurrent.read
import kotlin.concurrent.write

open class KotlinJvmReplServiceAsync(
    val portForServers: ServerSocketWrapper,
    replCompiler: ReplCompiler?
) : KotlinJvmReplServiceBase(replCompiler) {

    protected val states = WeakHashMap<RemoteReplStateFacadeServerSide, Boolean>() // used as (missing) WeakHashSet

    suspend fun createRemoteState(port: ServerSocketWrapper = portForServers): RemoteReplStateFacadeServerSide = statesLock.write {
        val id = getValidId(stateIdCounter) { id -> states.none { it.key.getId() == id } }
        val stateFacade = RemoteReplStateFacadeServerSide(id, createState(), port)
        stateFacade.runServer()
        states.put(stateFacade, true)
        stateFacade
    }

    suspend fun <R> withValidReplState(
        stateId: Int,
        body: (IReplStageState<*>) -> R
    ): CompileService.CallResult<R> = statesLock.read {
        states.keys.firstOrNull { it.getId() == stateId }?.let {
            CompileService.CallResult.Good(body(it.state))
        } ?: CompileService.CallResult.Error("No REPL state with id $stateId found")
    }
}
package com.lagradost.cloudstream4.desktop.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * MVI style holder, as upstream's COMPOSE.md asks: one immutable state that the screen
 * renders, updated only through [update]. Lives outside composition so it survives navigation.
 */
abstract class ScreenModel<S>(initial: S) {
    protected val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<S> = _state.asStateFlow()

    protected fun update(reducer: S.() -> S) = _state.update(reducer)
    protected val current: S get() = _state.value

    open fun dispose() = scope.cancel()
}

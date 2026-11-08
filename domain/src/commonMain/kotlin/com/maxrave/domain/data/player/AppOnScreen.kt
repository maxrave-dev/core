package com.maxrave.domain.data.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether any SimpMusic window is on screen: not minimized, not hidden to the tray.
 *
 * Only the Desktop frame governor writes it. Android never does and it stays true there, because the
 * platform already stops drawing a stopped Activity. Read by the work that exists only for what the
 * user sees — the player handler's 50 ms position ticks and the decoding of video frames — so a
 * Desktop app left running in the background stops spending CPU on pictures nobody is looking at.
 * Audio keeps playing either way.
 */
object AppOnScreen {
    private val _onScreen = MutableStateFlow(true)
    val onScreen: StateFlow<Boolean> = _onScreen.asStateFlow()

    fun set(onScreen: Boolean) {
        _onScreen.value = onScreen
    }
}

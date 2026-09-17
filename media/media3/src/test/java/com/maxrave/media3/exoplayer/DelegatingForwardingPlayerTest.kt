package com.maxrave.media3.exoplayer

import androidx.media3.common.Player
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Test

class DelegatingForwardingPlayerTest {
    private fun delegate(calls: MutableList<String>): Player =
        Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
            calls.add("${method.name}:${args?.toList().orEmpty()}")
            when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                java.lang.Float.TYPE -> 0f
                else -> null
            }
        } as Player

    @Test
    fun deniedFocusDoesNotStartDelegate() {
        val calls = mutableListOf<String>()
        val player = DelegatingForwardingPlayer(delegate(calls))
        player.playbackControlProvider = object : DelegatingForwardingPlayer.PlaybackControlProvider {
            override fun setPlayWhenReady(playWhenReady: Boolean): Boolean = false
        }
        calls.clear()
        player.play()
        player.setPlayWhenReady(true)
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun transportIntentIsSynchronousAndPrecedesDelegate() {
        val calls = mutableListOf<String>()
        val player = DelegatingForwardingPlayer(delegate(calls))
        player.playbackControlProvider = object : DelegatingForwardingPlayer.PlaybackControlProvider {
            override fun setPlayWhenReady(playWhenReady: Boolean): Boolean {
                calls.add("intent:$playWhenReady")
                return true
            }
        }
        calls.clear()
        player.play()
        player.pause()
        player.setPlayWhenReady(true)
        assertEquals(
            listOf("intent:true", "play:[]", "intent:false", "pause:[]", "intent:true", "setPlayWhenReady:[true]"),
            calls,
        )
    }
}

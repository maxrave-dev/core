package com.simpmusic.media_jvm.mpv

import com.maxrave.domain.data.player.EightDDirection
import com.maxrave.domain.data.player.EightDEffect
import com.maxrave.domain.data.player.EightDHead
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EightDGraphsTest {
    @Test
    fun graphPreservesTheInputLayoutAndOnlyRendersStereo() {
        val graph = eightDGraph(EightDEffect(), 0.0)
        assertTrue(graph.startsWith("aeval=exprs='if(eq(nb_in_channels,2),"))
        assertTrue(graph.endsWith(",val(ch))':c=same"))
        // Each ear filters its own channel from its own axis; the song is not folded to mono.
        assertTrue(graph.contains("st(5,val(ch))"))
        assertFalse(graph.contains("val(0)+val(1)"))
        assertTrue(graph.contains("if(eq(ch,0),-PI/2,PI/2)"))
    }

    @Test
    fun headShadowUsesTheDomainConstants() {
        val graph = eightDGraph(EightDEffect(), 0.0)
        // Equation (5) for this ear and the other with α_min = 0.1, θ_min = 150°, then the shadow
        // divided by the brighter ear's: pole β/max(α), gain α/max(α).
        assertTrue(graph.contains("st(2,1.05+0.95*cos(ld(1)/${EightDHead.THETA_MIN_RADIANS}*PI))"))
        assertTrue(graph.contains("st(9,1.05+0.95*cos((PI-ld(1))/${EightDHead.THETA_MIN_RADIANS}*PI))"))
        assertTrue(graph.contains("st(3,${EightDHead.SHADOW_BETA_RADIANS_PER_SECOND}/max(ld(2),ld(9)))"))
        assertTrue(graph.contains("st(2,ld(2)/max(ld(2),ld(9)))"))
        // The filter state is read before it is replaced.
        assertTrue(graph.indexOf("st(8,") < graph.indexOf("st(6,ld(5))"))
    }

    @Test
    fun eachDirectionUsesTheSamePeriodWithOppositeAngularVelocity() {
        val forward = eightDGraph(EightDEffect(), 0.7)
        val reverse = eightDGraph(EightDEffect(EightDDirection.RIGHT_TO_LEFT), 0.7)
        assertTrue(forward.contains("0.7+0.6283185307179586*n/s"))
        assertTrue(reverse.contains("0.7+-0.6283185307179586*n/s"))
    }

    @Test
    fun malformedSettingsNeverBecomeInvalidNativeNumbers() {
        val graph = eightDGraph(EightDEffect(periodSeconds = Float.NaN), Double.NaN)
        assertFalse(graph.contains("NaN"))
        assertFalse(graph.contains("Infinity"))
    }

    @Test
    fun bypassTransitionsAreBoundedAndFinite() {
        assertTrue(eightDGraph(EightDEffect(), 0.0, EightDTransition.FADE_IN).contains("min(n/(s*0.05),1)"))
        assertTrue(eightDGraph(EightDEffect(), 0.0, EightDTransition.FADE_OUT).contains("max(1-n/(s*0.05),0)"))
    }
}

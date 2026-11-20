package dev.trueform.player.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier.Node
import kotlinx.coroutines.launch

/** Haptic feedback on (the "Haptic feedback" setting). */
val LocalHaptics = compositionLocalOf { true }

/**
 * The app's one pressed/hover/focus treatment (docs/plan/04-ui-ux.md "States"): an overlay of the text colour at
 * 16% (12% light) within one frame of the press, fading out in 120 ms; 8% on hover; a 2 dp accent ring on focus;
 * buttons also shrink to 0.97. Material's ripple is too faint on dark surfaces.
 */
class TfIndication(
    private val overlay: Color,
    private val ring: Color,
    private val pressedAlpha: Float,
    private val scaleOnPress: Boolean,
) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        IndicationNode(interactionSource, overlay, ring, pressedAlpha, scaleOnPress)

    override fun equals(other: Any?) = other is TfIndication && other.overlay == overlay && other.ring == ring &&
        other.pressedAlpha == pressedAlpha && other.scaleOnPress == scaleOnPress

    override fun hashCode() = ((overlay.hashCode() * 31 + ring.hashCode()) * 31 + pressedAlpha.hashCode()) * 31 + scaleOnPress.hashCode()

    private class IndicationNode(
        val source: InteractionSource,
        val overlay: Color,
        val ring: Color,
        val pressedAlpha: Float,
        val scaleOnPress: Boolean,
    ) : Node(), DrawModifierNode {
        private val alpha = Animatable(0f)
        private val scale = Animatable(1f)
        private var pressed = 0
        private var hovered = false
        private var focused = false

        override fun onAttach() {
            coroutineScope.launch {
                source.interactions.collect { i ->
                    when (i) {
                        is PressInteraction.Press -> pressed++
                        is PressInteraction.Release, is PressInteraction.Cancel -> pressed = (pressed - 1).coerceAtLeast(0)
                        is HoverInteraction.Enter -> hovered = true
                        is HoverInteraction.Exit -> hovered = false
                        is FocusInteraction.Focus -> focused = true
                        is FocusInteraction.Unfocus -> focused = false
                    }
                    update()
                }
            }
        }

        private fun update() {
            val target = when { pressed > 0 -> pressedAlpha; hovered -> 0.08f; else -> 0f }
            coroutineScope.launch {
                if (pressed > 0) alpha.snapTo(target) else alpha.animateTo(target, tween(120))
            }
            if (scaleOnPress) coroutineScope.launch {
                if (pressed > 0) scale.animateTo(0.97f, tween(60)) else scale.animateTo(1f, tween(120))
            }
            invalidateDraw()
        }

        override fun ContentDrawScope.draw() {
            val s = scale.value
            if (s != 1f) scale(s) { this@draw.drawContent() } else drawContent()
            val a = alpha.value
            if (a > 0f) drawRect(overlay.copy(alpha = a))
            if (focused) {
                val w = 2.dp.toPx()
                drawRect(ring, topLeft = androidx.compose.ui.geometry.Offset(w / 2, w / 2),
                    size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w), style = Stroke(w))
            }
            if (a > 0f || s != 1f) invalidateDraw()
        }
    }
}

/**
 * Every clickable in the app goes through this, so the pressed/hover/focus rule holds everywhere. Apply it after
 * clip() so the overlay takes the element's shape.
 */
fun Modifier.tfClickable(
    enabled: Boolean = true,
    scale: Boolean = false,
    role: Role? = Role.Button,
    haptic: Boolean = false,
    label: String? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val c = LocalTf.current
    val source = remember { MutableInteractionSource() }
    val hapticFeedback = LocalHapticFeedback.current
    val hapticsOn = LocalHaptics.current
    combinedClickable(
        interactionSource = source,
        indication = TfIndication(c.text, c.accent, c.pressedAlpha, scale),
        enabled = enabled,
        role = role,
        onClickLabel = label,
        onLongClick = onLongClick?.let { long -> { if (hapticsOn) hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress); long() } },
        onClick = { if (haptic && hapticsOn) hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove); onClick() },
    )
}

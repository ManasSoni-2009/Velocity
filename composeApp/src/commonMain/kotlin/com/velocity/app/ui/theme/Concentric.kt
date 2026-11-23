package com.velocity.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Apple HIG concentric corner geometry calculation.
 * Ensures inner nested surfaces have perfectly matched concentric corner radii
 * relative to the outer container and padding:
 * R_inner = max(minRadius, R_outer - padding)
 */
fun concentricRadius(outerRadius: Dp, padding: Dp, minRadius: Dp = 4.dp): Dp {
    val inner = outerRadius - padding
    return if (inner < minRadius) minRadius else inner
}

/**
 * Returns a [RoundedCornerShape] with concentric radius.
 */
fun concentricShape(outerRadius: Dp, padding: Dp, minRadius: Dp = 4.dp): RoundedCornerShape =
    RoundedCornerShape(concentricRadius(outerRadius, padding, minRadius))

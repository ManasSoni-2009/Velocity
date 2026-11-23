package com.velocity.app

import androidx.compose.ui.unit.dp
import com.velocity.app.ui.theme.concentricRadius
import kotlin.test.Test
import kotlin.test.assertEquals

class ConcentricGeometryTest {

    @Test
    fun testConcentricRadiusCalculation() {
        val outer = 32.dp
        val padding = 4.dp
        val expected = 28.dp
        assertEquals(expected, concentricRadius(outer, padding))
    }

    @Test
    fun testConcentricRadiusClampingToMin() {
        val outer = 10.dp
        val padding = 12.dp
        val expected = 4.dp // minRadius default
        assertEquals(expected, concentricRadius(outer, padding))
    }

    @Test
    fun testConcentricRadiusCustomMin() {
        val outer = 10.dp
        val padding = 8.dp
        val min = 6.dp
        assertEquals(min, concentricRadius(outer, padding, minRadius = min))
    }
}

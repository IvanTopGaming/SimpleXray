package com.simplexray.an.ui.navigation

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.simplexray.an.ui.components.RightAlignedMenuPosition
import org.junit.Assert.assertEquals
import org.junit.Test

class AddMenuPositionTest {
    private val provider = RightAlignedMenuPosition

    @Test
    fun alignsRightEdgesEvenWhenThereIsRoomOnTheRight() {
        assertEquals(
            IntOffset(120, 100),
            provider.calculatePosition(
                IntRect(200, 52, 300, 100),
                IntSize(420, 900),
                LayoutDirection.Ltr,
                IntSize(180, 112),
            ),
        )
    }

    @Test
    fun staysInsideNarrowAndWidePhoneWindows() {
        listOf(320, 360, 393, 412, 448).forEach { width ->
            assertEquals(
                IntOffset(width - 200, 100),
                provider.calculatePosition(
                    IntRect(width - 130, 52, width - 20, 100),
                    IntSize(width, 900),
                    LayoutDirection.Ltr,
                    IntSize(180, 112),
                ),
            )
        }
    }

    @Test
    fun clampsAtTheLeftEdgeWhenMenuIsWiderThanTheAnchorSpace() {
        assertEquals(
            IntOffset(0, 100),
            provider.calculatePosition(
                IntRect(8, 52, 108, 100),
                IntSize(320, 600),
                LayoutDirection.Ltr,
                IntSize(220, 112),
            ),
        )
    }

    @Test
    fun opensAboveAnchorWhenThereIsNoRoomBelow() {
        assertEquals(
            IntOffset(120, 388),
            provider.calculatePosition(
                IntRect(200, 500, 300, 548),
                IntSize(320, 600),
                LayoutDirection.Ltr,
                IntSize(180, 112),
            ),
        )
    }

    @Test
    fun keepsPhysicalRightAlignmentInBothLayoutDirections() {
        assertEquals(
            IntOffset(120, 100),
            provider.calculatePosition(
                IntRect(200, 52, 300, 100),
                IntSize(420, 900),
                LayoutDirection.Rtl,
                IntSize(180, 112),
            ),
        )
    }
}

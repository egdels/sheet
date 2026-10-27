// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.sheet.core.ocr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ColumnLayoutTest {
    @Test
    fun verticalMarginFragmentsDoNotMakeAColumn() {
        val boxes = listOf(
            Box(129.1, 58.7, 876.8, 173.2),
            Box(915.2, 58.7, 991.8, 173.2),
            Box(1030.2, 58.7, 1413.6, 173.2),
            Box(1451.9, 58.7, 1586.1, 173.2),
            Box(1624.5, 58.7, 1988.7, 173.2),
            Box(483.5, 212.5, 852.1, 296.1),
            Box(963.0, 197.2, 1322.6, 292.2),
            Box(1428.2, 194.9, 1639.9, 286.2),
            Box(119.4, 368.1, 281.6, 475.8),
            Box(790.8, 359.0, 1053.4, 449.8),
            Box(1519.3, 347.1, 1768.2, 436.7),
            Box(123.7, 1401.9, 433.3, 1481.2),
            Box(456.6, 1399.9, 544.3, 1475.2),
            Box(567.6, 1397.2, 692.2, 1473.2),
            Box(727.9, 1394.7, 827.9, 1470.3),
            Box(863.5, 1393.8, 877.2, 1467.9),
            Box(912.9, 1391.6, 1000.5, 1467.0),
            Box(1023.8, 1389.2, 1136.1, 1465.0),
            Box(1159.5, 1388.5, 1173.1, 1462.5),
            Box(1196.4, 1384.5, 1395.1, 1461.9),
            Box(1430.7, 1383.6, 1444.4, 1457.6),
            Box(1480.0, 1381.4, 1567.7, 1456.7),
            Box(1591.0, 1378.9, 1703.3, 1454.7),
            Box(1726.6, 1378.3, 1740.3, 1452.3),
            Box(1763.6, 1373.6, 1999.2, 1451.6),
            Box(252.7, 1469.5, 771.2, 1546.6),
            Box(813.8, 1445.8, 1882.3, 1535.6),
            Box(22.2, 892.6, 90.9, 1100.0),
            Box(22.2, 1150.0, 90.9, 1360.9),
        )
        assertTrue(ColumnLayout.segments(boxes, rtl = false).isEmpty())
    }

    @Test
    fun verticalMarginNoteIsReadAfterTheColumns() {
        val boxes = listOf(
            Box(100.0, 100.0, 900.0, 130.0),
            Box(100.0, 150.0, 900.0, 180.0),
            Box(100.0, 200.0, 900.0, 230.0),
            Box(1100.0, 100.0, 1900.0, 130.0),
            Box(1100.0, 150.0, 1900.0, 180.0),
            Box(1100.0, 200.0, 1900.0, 230.0),
            Box(20.0, 300.0, 50.0, 600.0),
        )
        assertEquals(listOf(listOf(0, 1, 2), listOf(3, 4, 5), listOf(6)), ColumnLayout.segments(boxes, rtl = false))
    }
}

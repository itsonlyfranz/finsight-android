package com.example.finsightai.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class MoneyTest {
    @Test fun formatsPesoWithGroupingAndTwoDecimals() {
        assertEquals("₱0.00", Money.format(0.0))
        assertEquals("₱412.50", Money.format(412.5))
        assertEquals("₱45,000.00", Money.format(45_000.0))
        assertEquals("₱1,234,567.89", Money.format(1_234_567.891))
    }

    @Test fun negativeAmountsKeepSignInFrontOfSymbol() {
        assertEquals("-₱1,234.50", Money.format(-1234.5))
        assertEquals("-₱178", Money.formatWhole(-178.0))
    }

    @Test fun formatWholeRoundsAndGroups() {
        assertEquals("₱178", Money.formatWhole(177.6))
        assertEquals("₱1,404", Money.formatWhole(1403.8))
    }

    @Test fun ignoresDeviceLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("₱1,234.50", Money.format(1234.5))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test fun parseAmountReadsHowPeopleTypeMoney() {
        assertEquals(45_000.0, Money.parseAmount("45000")!!, 0.0)
        assertEquals(45_000.5, Money.parseAmount(" 45,000.50 ")!!, 0.0)
        assertEquals(45_000.0, Money.parseAmount("₱45,000")!!, 0.0)
        assertEquals(45_000.0, Money.parseAmount("45 000")!!, 0.0)
        assertEquals(0.0, Money.parseAmount("0")!!, 0.0)
    }

    @Test fun parseAmountRejectsAnythingThatIsNotAPlainAmount() {
        listOf("", "   ", "abc", "-5", "1e3", "NaN", "Infinity", "1.2.3", "12abc", "₱", "1e999").forEach {
            assertNull("'$it' must not parse", Money.parseAmount(it))
        }
    }
}

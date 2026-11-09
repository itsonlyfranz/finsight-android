package com.example.finsightai.util

import java.util.Locale

/**
 * The single place money is turned into text. FinSight shows Philippine pesos everywhere (screens,
 * rules-generated wording and the text sent to AI backends), so the symbol and grouping are fixed
 * here instead of coming from the device locale.
 */
object Money {
    /** Philippine peso sign. */
    const val SYMBOL = "₱"

    /** `1234.5` -> `₱1,234.50`; negative values keep the sign in front: `-₱1,234.50`. */
    fun format(amount: Double): String = withSymbol(String.format(Locale.US, "%,.2f", amount))

    /** `1234.5` -> `₱1,235` (no decimals), for compact projections. */
    fun formatWhole(amount: Double): String = withSymbol(String.format(Locale.US, "%,.0f", amount))

    private fun withSymbol(number: String): String =
        if (number.startsWith("-")) "-$SYMBOL${number.substring(1)}" else "$SYMBOL$number"
}

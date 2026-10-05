package com.example.finsightai.util

import com.example.finsightai.model.Transaction
import com.example.finsightai.model.TransactionCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CsvExporterTest {

    @Test
    fun `generateCsvContent generates headers and lines correctly`() {
        val transactions = listOf(
            Transaction(
                id = "tx_1",
                title = "Apex Systems Paycheck",
                amount = 3200.0,
                category = TransactionCategory.SALARY,
                date = LocalDate.of(2026, 10, 1),
                isExpense = false,
                notes = "Monthly net direct deposit"
            ),
            Transaction(
                id = "tx_2",
                title = "Philz Mint Mojito",
                amount = 7.25,
                category = TransactionCategory.CAFES_DINING,
                date = LocalDate.of(2026, 10, 3),
                isExpense = true,
                notes = "Iced, sweet"
            )
        )

        val csv = CsvExporter.generateCsvContent(transactions)
        val lines = csv.trim().lines()

        assertEquals(3, lines.size)
        assertEquals("ID,Date,Title,Category,Type,Amount,Notes", lines[0])
        assertTrue("First transaction should be income", lines[1].contains("Income"))
        assertTrue("First transaction amount", lines[1].contains("3200.00"))
        assertTrue("Second transaction should be expense", lines[2].contains("Expense"))
        assertTrue("Second transaction amount", lines[2].contains("7.25"))
        // Comma in notes should be properly quoted
        assertTrue("Comma in notes quoted", lines[2].contains("\"Iced, sweet\""))
    }

    @Test
    fun `generateCsvContent properly escapes quotes and special characters`() {
        val tx = Transaction(
            id = "tx_special",
            title = "Special \"Custom\" Keyboard",
            amount = 199.99,
            category = TransactionCategory.TECH_GADGETS,
            date = LocalDate.of(2026, 10, 5),
            isExpense = true,
            notes = "Note with \"quotes\" and, commas"
        )

        val csv = CsvExporter.generateCsvContent(listOf(tx))
        val lines = csv.trim().lines()

        assertEquals(2, lines.size)
        assertTrue(lines[1].contains("\"Special \"\"Custom\"\" Keyboard\""))
        assertTrue(lines[1].contains("\"Note with \"\"quotes\"\" and, commas\""))
    }
}

package com.example.finsightai.util

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import com.example.finsightai.model.Transaction
import java.io.File
import java.io.FileWriter
import java.util.Locale

object CsvExporter {

    fun generateCsvContent(transactions: List<Transaction>): String {
        val builder = StringBuilder()
        builder.append("ID,Date,Title,Category,Type,Amount,Notes\n")

        for (tx in transactions) {
            val id = escapeCsv(tx.id)
            val date = tx.date.toString()
            val title = escapeCsv(tx.title)
            val category = escapeCsv(tx.category.displayName)
            val type = if (tx.isExpense) "Expense" else "Income"
            val amount = String.format(Locale.US, "%.2f", tx.amount)
            val notes = escapeCsv(tx.notes)

            builder.append("$id,$date,$title,$category,$type,$amount,$notes\n")
        }

        return builder.toString()
    }

    fun exportAndShareCsv(context: Context, transactions: List<Transaction>) {
        try {
            val csvContent = generateCsvContent(transactions)
            val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
            val csvFile = File(exportDir, "finsight_transactions_${System.currentTimeMillis()}.csv")

            FileWriter(csvFile).use { writer ->
                writer.write(csvContent)
            }

            val uri = try {
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    csvFile
                )
            } catch (e: Exception) {
                null
            }

            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_SUBJECT, "FinSight AI Transactions Export")
                if (uri != null) {
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } else {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, csvContent)
                }
            }

            val chooser = Intent.createChooser(sendIntent, "Share Transactions CSV").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (e: Exception) {
            Toast.makeText(context, "Export failed: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun escapeCsv(value: String): String {
        var str = value.trim()
        if (str.contains(",") || str.contains("\"") || str.contains("\n") || str.contains("\r")) {
            str = str.replace("\"", "\"\"")
            return "\"$str\""
        }
        return str
    }
}

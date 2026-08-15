package com.arthvault.data.export

import com.arthvault.data.local.entity.TransactionEntity
import com.arthvault.ui.format.formatDate
import com.arthvault.ui.format.formatTimeOfDay
import java.io.File

object CsvExporter {

    /**
     * Generates a local RFC-4180 compliant CSV file representing the given transactions.
     */
    fun exportToCsv(transactions: List<TransactionEntity>, outputFile: File): File {
        val writer = outputFile.bufferedWriter()
        writer.write("ID,Date,Time,Merchant,Amount,Direction,Category,AccountTail,Channel,Type,Status\n")

        transactions.forEach { txn ->
            val dateStr = formatDate(txn.timestamp)
            val timeStr = formatTimeOfDay(txn.timestamp)
            val line = listOf(
                txn.id.toString(),
                escapeCsv(dateStr),
                escapeCsv(timeStr),
                escapeCsv(txn.merchant),
                txn.amount.toString(),
                txn.direction,
                escapeCsv(txn.category),
                escapeCsv(txn.accountTail ?: ""),
                escapeCsv(txn.channel ?: ""),
                txn.txnType,
                txn.status
            ).joinToString(",")

            writer.write(line)
            writer.write("\n")
        }

        writer.flush()
        writer.close()
        return outputFile
    }

    private fun escapeCsv(field: String): String {
        if (field.contains(",") || field.contains("\"") || field.contains("\n")) {
            return "\"" + field.replace("\"", "\"\"") + "\""
        }
        return field
    }
}

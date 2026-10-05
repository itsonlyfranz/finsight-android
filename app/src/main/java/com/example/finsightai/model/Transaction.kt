package com.example.finsightai.model

import java.time.LocalDate
import java.util.UUID

data class Transaction(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val amount: Double,
    val category: TransactionCategory,
    val date: LocalDate = LocalDate.now(),
    val isExpense: Boolean = true,
    val notes: String = ""
)

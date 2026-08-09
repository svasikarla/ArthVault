package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "merchant_rules")
data class MerchantRuleEntity(
    @PrimaryKey
    val merchantPattern: String, // Normalized upper-case string or keyword e.g. "SWIGGY"
    val assignedCategory: String,
    val updatedTimestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "parser_rules")
data class ParserRuleEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val ruleName: String,
    val senderPattern: String, // e.g. "HDFCBK|ICICIB|SBIBNK|AXISBK|KOTAKB|PAYTM"
    val regexPattern: String,
    val amountGroup: Int,
    val directionGroup: Int,
    val merchantGroup: Int,
    val accountGroup: Int?,
    val channelGroup: Int?,
    val isDebitKeyword: String = "debited|spent|paid|sent|withdrawn|charged|txn",
    val isActive: Boolean = true,
    val isSystemRule: Boolean = true
)

@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey
    val name: String,
    val iconName: String, // e.g. "ShoppingBag", "Fastfood", "DirectionsCar"
    val colorHex: String,
    val isCustom: Boolean = false
)

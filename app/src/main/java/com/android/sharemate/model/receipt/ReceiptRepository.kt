package com.android.sharemate.model.receipt

import kotlinx.coroutines.flow.Flow

/**
 * Interface définissant les opérations possibles sur les Reçus (Receipts).
 */
interface ReceiptRepository {
    
    fun getPrivateReceipts(userId: String): Flow<List<Receipt>>

    fun getSharedReceipts(householdId: String): Flow<List<Receipt>>

    suspend fun addReceipt(receipt: Receipt): String?

    suspend fun deleteReceipt(receiptId: String): Boolean
}

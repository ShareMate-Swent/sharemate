package com.android.sharemate.model.receipt

/** Maximum title length accepted by the receipt server contract. */
const val MAX_RECEIPT_TITLE_LENGTH = 80

/** Trims surrounding whitespace before enforcing the receipt title limit. */
fun normalizeReceiptTitle(title: String): String = title.trim().take(MAX_RECEIPT_TITLE_LENGTH)

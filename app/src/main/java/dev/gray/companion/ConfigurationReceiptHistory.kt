package dev.gray.companion

import dev.gray.companion.core.ConfigurationEditReceipt

/** Apply the existing import retention policy to every newly committed configuration receipt. */
internal object ConfigurationReceiptHistory {
    private const val MAX_RECEIPTS = 20

    fun append(current: List<ConfigurationEditReceipt>, receipt: ConfigurationEditReceipt?): List<ConfigurationEditReceipt> =
        if (receipt == null) current else (current + receipt).takeLast(MAX_RECEIPTS)
}

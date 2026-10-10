package dev.gray.companion

import dev.gray.companion.core.ConfigurationEditReceipt
import org.junit.Assert.*
import org.junit.Test

class ConfigurationReceiptHistoryTest {
    @Test fun sourceEditsAndImportsShareOneChronologicalRetentionWindow() {
        var receipts = emptyList<ConfigurationEditReceipt>()
        repeat(25) { index ->
            receipts = ConfigurationReceiptHistory.append(receipts,
                ConfigurationEditReceipt(id = "source-$index", label = "来源编辑"))
        }
        receipts = ConfigurationReceiptHistory.append(receipts,
            ConfigurationEditReceipt(id = "import", label = "配置导入"))
        assertEquals((6..24).map { "source-$it" } + "import", receipts.map { it.id })
    }

    @Test fun unchangedCommitDoesNotTrimExistingHistoricalReceipts() {
        val historical = (0..24).map { ConfigurationEditReceipt(id = "old-$it") }
        assertSame(historical, ConfigurationReceiptHistory.append(historical, null))
    }
}

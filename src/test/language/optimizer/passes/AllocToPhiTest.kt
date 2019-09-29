package language.optimizer.passes

import language.optimizer.testBeforeAfter
import org.junit.jupiter.api.Test

class AllocToPhiTest {
    @Test
    fun valueInStore() = testBeforeAfter("a2phi_valueInStore.ir", AllocToPhi)

    @Test
    fun stuck() = testBeforeAfter("a2phi_stuck.ir", AllocToPhi)
}
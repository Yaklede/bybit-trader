package dev.yaklede.bybittrader.engine.execution

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class ExecutionLifecycleStoreTest :
    StringSpec({
        "entry lifecycle permits fill protection exit and close progression" {
            ExecutionLifecycleState.ENTRY_SUBMITTED.canTransitionTo(ExecutionLifecycleState.PARTIALLY_FILLED) shouldBe true
            ExecutionLifecycleState.PARTIALLY_FILLED.canTransitionTo(ExecutionLifecycleState.OPEN_UNPROTECTED) shouldBe true
            ExecutionLifecycleState.OPEN_UNPROTECTED.canTransitionTo(ExecutionLifecycleState.OPEN_PROTECTED) shouldBe true
            ExecutionLifecycleState.OPEN_PROTECTED.canTransitionTo(ExecutionLifecycleState.EXIT_SUBMITTED) shouldBe true
            ExecutionLifecycleState.EXIT_SUBMITTED.canTransitionTo(ExecutionLifecycleState.CLOSED) shouldBe true
        }

        "closed lifecycle is terminal" {
            ExecutionLifecycleState.CLOSED.canTransitionTo(ExecutionLifecycleState.CLOSED) shouldBe true
            ExecutionLifecycleState.CLOSED.canTransitionTo(ExecutionLifecycleState.OPEN_PROTECTED) shouldBe false
            ExecutionLifecycleState.CLOSED.canTransitionTo(ExecutionLifecycleState.ENTRY_SUBMITTED) shouldBe false
        }

        "error lifecycle can recover only through observed position or exit states" {
            ExecutionLifecycleState.ERROR.canTransitionTo(ExecutionLifecycleState.OPEN_UNPROTECTED) shouldBe true
            ExecutionLifecycleState.ERROR.canTransitionTo(ExecutionLifecycleState.OPEN_PROTECTED) shouldBe true
            ExecutionLifecycleState.ERROR.canTransitionTo(ExecutionLifecycleState.EXIT_SUBMITTED) shouldBe true
            ExecutionLifecycleState.ERROR.canTransitionTo(ExecutionLifecycleState.CLOSED) shouldBe true
            ExecutionLifecycleState.ERROR.canTransitionTo(ExecutionLifecycleState.ENTRY_SUBMITTED) shouldBe false
        }
    })

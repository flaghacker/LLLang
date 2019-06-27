package language.optimizer

import language.ir.Function
import language.ir.Program

sealed class OptimizerPass {
    abstract fun OptimizerContext.runOnProgram(program: Program, afterPass: (subject: Any?) -> Unit)

    override fun toString(): String = this.javaClass.simpleName
}

abstract class ProgramPass : OptimizerPass() {
    abstract fun OptimizerContext.optimize(program: Program)

    override fun OptimizerContext.runOnProgram(program: Program, afterPass: (subject: Any?) -> Unit) {
        optimize(program)
        afterPass(program)
    }
}

abstract class FunctionPass : OptimizerPass() {
    abstract fun OptimizerContext.optimize(function: Function)

    override fun OptimizerContext.runOnProgram(program: Program, afterPass: (subject: Any?) -> Unit) {
        for (function in program.functions) {
            optimize(function)
            afterPass(function)
        }
    }
}

interface OptimizerContext {
    fun changed()
    fun domInfo(function: Function): DominatorInfo
}

private class OptimizerContextImpl : OptimizerContext {
    var hasChanged = false

    override fun changed() {
        hasChanged = true
    }

    override fun domInfo(function: Function) = DominatorInfo(function)
}

val DEFAULT_PASSES = listOf(
        //program passes
        DeadFunctionElimination,
        DeadSignatureElimination,
        FunctionInlining,

        //function passes
        SplitAggregate,
        AllocToPhi,
        ConstantFolding,
        SCCP,
        DeadInstructionElimination,
        SimplifyBlocks,
        DeadBlockElimination
)

class Optimizer(
        private val passes: Iterable<OptimizerPass> = DEFAULT_PASSES,
        private val repeat: Boolean = true,
        private val doVerify: Boolean
) {
    private val _runPasses = mutableListOf<OptimizerPass>()
    val runPasses: List<OptimizerPass> get() = _runPasses

    private fun verify(program: Program, pass: Any?, subject: Any?) {
        if (doVerify) {
            try {
                program.verify()
            } catch (e: Exception) {
                throw IllegalStateException("verify fail after pass $pass on $subject", e)
            }
        }
    }

    fun optimize(program: Program) {
        verify(program, null, null)

        val context = OptimizerContextImpl()

        while (true) {
            context.hasChanged = false

            for (pass in passes) {
                _runPasses += pass

                with(pass) {
                    context.runOnProgram(program) {
                        verify(program, pass, it)
                    }
                }
            }

            if (!repeat || !context.hasChanged) break
        }
    }
}

/**
 * Finds a minimal list of optimizer passes that causes a crash. [program] must supply independant instances of
 * otherwise identical programs, this is a woraround until full program cloning is possible.
 * Returns `null` if this optimisation doesn't crash at all.
 */
fun findMinimalErrorPasses(program: () -> Program): List<OptimizerPass>? {
    val optimizer = Optimizer(doVerify = true)

    try {
        optimizer.optimize(program())

        //didn't crash
        return null
    } catch (e: Exception) {
    }

    val passes = optimizer.runPasses.toMutableList()

    outer@ while (true) {
        for (i in passes.indices) {
            val skippedPass = passes.removeAt(i)

            try {
                Optimizer(passes, doVerify = true).optimize(program())

                //didn't crash, add back in and try the next index
                passes.add(i, skippedPass)
                continue
            } catch (e: Exception) {
                //crashed, keep pass removed and start looking for the next one
                continue@outer
            }
        }

        //for loop completed normally, no removable pass found
        return passes
    }
}

package language.optimizer

import language.ir.Program
import language.ir.ProgramNameEnv
import language.ir.support.IrParser
import language.ir.support.Verifier
import language.ir.support.programEquals
import org.apache.commons.io.IOUtils
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

fun testBeforeAfter(name: String, vararg passes: OptimizerPass) = testBeforeAfter(name, passes.asList())

fun testBeforeAfter(name: String, passes: List<OptimizerPass>) {
    val (before, after) = readFile(name)

    Verifier.verifyProgram(before)
    Verifier.verifyProgram(after)

    Optimizer(passes = passes, repeat = false, doVerify = true).optimize(before)

    if (!programEquals(before, after)) {
        assertEquals(after.fullString(ProgramNameEnv()), before.fullString(ProgramNameEnv()))
        assertTrue(false)
    }
}

private fun readFile(name: String): Pair<Program, Program> {
    val string = resourceToString("passes/$name.ir").trim()

    val (beforeString, afterString) = when {
        string.startsWith("//before") -> {
            val parts = string.split("//after")

            if (parts.size == 1) error("Missing '//after'")
            if (parts.size > 2) error("Multiple '//after'")

            parts[0] to parts[1]
        }
        string.startsWith("//unchanged") -> {
            val code = string.removePrefix("//unchanged")
            code to code
        }
        else -> error("unrecognized start '${string.substring(0, 10)}'")
    }

    val before = IrParser.parse(beforeString)
    val after = IrParser.parse(afterString)

    return before to after
}

private fun resourceToString(path: String): String {
    val res = ::resourceToString::class.java.getResourceAsStream(path)
    return IOUtils.toString(res, Charsets.UTF_8)
}
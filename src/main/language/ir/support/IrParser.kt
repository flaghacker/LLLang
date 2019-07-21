package language.ir.support

import language.ir.AggregateValue
import language.ir.Alloc
import language.ir.ArrayType
import language.ir.BINARY_OP_TYPES
import language.ir.BasicBlock
import language.ir.BasicInstruction
import language.ir.BinaryOp
import language.ir.Blur
import language.ir.Branch
import language.ir.Call
import language.ir.Constant
import language.ir.Eat
import language.ir.Exit
import language.ir.Function
import language.ir.FunctionType
import language.ir.GetSubPointer
import language.ir.GetSubValue
import language.ir.IntegerType
import language.ir.Jump
import language.ir.Load
import language.ir.NameEnv
import language.ir.Phi
import language.ir.Program
import language.ir.Return
import language.ir.Store
import language.ir.StructType
import language.ir.Terminator
import language.ir.Type
import language.ir.UNARY_OP_TYPES
import language.ir.UnaryOp
import language.ir.UndefinedValue
import language.ir.UnitType
import language.ir.UnitValue
import language.ir.Value
import language.ir.pointer
import language.ir.support.IrTokenType.*
import language.ir.support.IrTokenType.Annotation
import language.ir.support.IrTokenType.Number
import language.parsing.Parser

class IrParser(tokenizer: IrTokenizer) : Parser<IrTokenType>(tokenizer) {
    companion object {
        fun parse(input: String) = IrParser(IrTokenizer(input)).parse()
    }

    private lateinit var structTypes: Map<String, StructType>

    private val globalVariablePlaceholders = mutableMapOf<String, PlaceholderValue>()
    private val localVariablePlaceholders = mutableMapOf<String, PlaceholderValue>()
    private val localBlockPlaceholders = mutableMapOf<String, BasicBlock>()

    fun parse(): Program {
        structTypes = parseStructTypeDeclarations()

        val functions = list(Eof, null) { function() }

        for (func in functions) {
            val ph = globalVariablePlaceholders.remove(func.name) ?: continue
            ph.replaceWith(func)
        }

        if (globalVariablePlaceholders.isNotEmpty())
            error("Missing values: " + globalVariablePlaceholders.keys.joinToString())


        val program = Program()
        for (func in functions)
            program.addFunction(func)

        program.entry = functions.single { it.name == "main" }

        return program
    }

    private fun parseStructTypeDeclarations(): MutableMap<String, StructType> {
        val structTypes = mutableMapOf<String, StructType>()

        while (accept(TypeDef)) {
            val id = idName()
            expect(Assign, OpenC)
            val properties = list(CloseC) { type() }

            if (id in structTypes) error("Duplicate type definition '%$id'")
            structTypes[id] = StructType(id, properties)
        }

        return structTypes
    }

    private fun function(): Function {
        val attributes = mutableSetOf<Function.Attribute>()
        while (at(Annotation)) {
            val attributeName = pop().text.drop(1)
            val attribute = Function.Attribute.findByName(attributeName)
                            ?: error("Invalid attribute name $attributeName")
            if (!attributes.add(attribute))
                error("Specified attribute $attribute multiple times")
        }

        expect(Fun)
        val funcName = idName()
        expect(OpenB)
        val parameters = list(CloseB, ::parameter)
        val retType = if (accept(Colon)) type() else UnitType
        expect(OpenC)

        val func = Function(funcName, parameters, retType, attributes)

        val entryName = if (accept(Entry)) {
            expect(Colon)
            blockName()
        } else null

        localVariablePlaceholders.clear()
        localBlockPlaceholders.clear()

        val blocks = mutableListOf<BasicBlock>()
        while (at(BlockId))
            blocks += block()
        expect(CloseC)

        //replacement map for values to ensure there are no duplicate replacements available
        val valueMap = mutableMapOf<String, Value>()

        //visit all local values
        for (block in blocks) {
            for (instr in block.instructions) {
                if (valueMap.put(instr.name ?: continue, instr) != null)
                    error("Duplicate name ${instr.name}")
            }

            //blocks can't conflict and can be immediatly replaced
            val blockPlaceholder = localBlockPlaceholders.remove(block.name!!) ?: continue
            blockPlaceholder.replaceWith(block)
        }

        for (param in func.parameters) {
            if (valueMap.put(param.name!!, param) != null)
                error("Duplicate name ${param.name}")
        }

        //replace local values
        for ((name, value) in valueMap)
            (localVariablePlaceholders.remove(name) ?: continue).replaceWith(value)

        //there are no global blocks, so there can't be any leftovers
        if (localBlockPlaceholders.isNotEmpty())
            error("Missing blocks: " + localBlockPlaceholders.keys.joinToString())

        //merge leftover variables into global placeholders
        for ((phName, placeholder) in localVariablePlaceholders) {
            val globalPlaceHolder = globalVariablePlaceholders[phName]
            if (globalPlaceHolder == null) {
                globalVariablePlaceholders[phName] = placeholder
            } else {
                placeholder.replaceWith(globalPlaceHolder)
            }
        }

        //finish constructing function
        func.addAll(blocks)
        func.entry = if (entryName == null)
            blocks.firstOrNull() ?: error("No block in function $funcName")
        else
            blocks.find { it.name == entryName } ?: error("Missing entry block '$entryName'")
        return func
    }

    private fun parameter(): Pair<String, Type> {
        val name = idName()
        val type = type()
        return name to type
    }

    private fun block(): BasicBlock {
        val name = blockName()

        val block = BasicBlock(name)

        while (true) {
            val term = terminator()
            if (term != null) {
                block.terminator = term
                return block
            }

            block.append(instruction())
        }
    }

    private fun instruction(): BasicInstruction = when {
        accept(EatToken) -> {
            expect(OpenB)
            Eat(list(CloseB) { typedValue() })
        }
        accept(StoreToken) -> {
            val target = typedValue()
            expect(Assign)
            val value = typedValue()
            Store(target, value)
        }
        else -> valueInstr()
    }

    private fun valueInstr(): BasicInstruction {
        val name = idName()
        val type = type()

        expect(Assign)

        val instr = when {
            accept(AllocToken) -> Alloc(name, type())
            accept(LoadToken) -> Load(name, typedValue())
            at(BinaryOpToken) -> {
                val opName = pop().text
                val op = BINARY_OP_TYPES.first { it.name == opName }
                val left = typedValue()
                expect(Comma)
                val right = typedValue()
                BinaryOp(name, op, left, right)
            }
            at(UnaryOpToken) -> {
                val opName = pop().text
                val op = UNARY_OP_TYPES.first { it.name == opName }
                UnaryOp(name, op, typedValue())
            }
            accept(PhiToken) -> {
                expect(OpenS)
                val sources = list(CloseS) {
                    val block = blockPlaceholder()
                    expect(Colon)
                    val value = typedValue()
                    requireTypeMatch(type, value.type)
                    block to value
                }

                val blocks = sources.map { it.first }
                if (blocks.toSet().size != blocks.size)
                    error("Duplicate source block in phi $name")

                val phi = Phi(name, type)
                phi.sources += sources
                phi
            }
            accept(BlurToken) -> Blur(name, typedValue())
            accept(CallToken) -> {
                val targetName = idName()
                expect(OpenB)
                val arguments = list(CloseB) { typedValue() }
                val targetType = FunctionType(arguments.map { it.type }, type)
                val target = valuePlaceholder(targetName, targetType)
                Call(name, target, arguments)
            }
            accept(StructGetToken) -> GetSubValue.Struct(name, typedValue().also { expect(Comma) }, intergerLiteral())
            accept(ArrayGetToken) -> GetSubValue.Array(name, typedValue().also { expect(Comma) }, typedValue())
            accept(StructPtrToken) -> GetSubPointer.Struct(name, typedValue().also { expect(Comma) }, intergerLiteral())
            accept(ArrayPtrToken) -> GetSubPointer.Array(name, typedValue().also { expect(Comma) }, typedValue())
            accept(OpenC) -> {
                if (type !is ArrayType)
                    error("Array intializer for non-array type $type")
                val values = list(CloseC, ::typedValue)
                AggregateValue(name, type, values)
            }
            at(Id) -> {
                val structType = type() as StructType
                if (type !is StructType)
                    error("Struct intializer for non-struct type $type")
                expect(OpenB)
                val values = list(CloseB, ::typedValue)
                AggregateValue(name, structType, values)
            }
            else -> expected("instruction")
        }

        requireTypeMatch(type, instr.type)
        return instr
    }

    private fun terminator(): Terminator? = when {
        accept(BranchToken) -> Branch(typedValue(), blockPlaceholder(), blockPlaceholder())
        accept(JumpToken) -> Jump(blockPlaceholder())
        accept(ExitToken) -> Exit()
        accept(ReturnToken) -> Return(typedValue())
        else -> null
    }

    private fun typedValue(): Value = when {
        at(Id) -> {
            val name = idName()
            val type = type()
            valuePlaceholder(name, type)
        }
        at(Number) -> {
            val value = intergerLiteral()
            val type = type() as? IntegerType ?: error("Expected integer type")
            Constant(type, value)
        }
        accept(UnitValueToken) -> {
            expect(UnitTypeToken)
            UnitValue
        }
        accept(Undef) -> {
            val type = type()
            UndefinedValue(type)
        }
        else -> expected("value")
    }

    private fun type(): Type {
        val inner = when {
            at(IntegerTypeToken) -> IntegerType.width(pop().text.drop(1).toInt())
            accept(UnitTypeToken) -> UnitType
            at(Id) -> {
                val text = idName()
                structTypes[text] ?: error("Undeclared type '$text'")
            }
            accept(OpenS) -> {
                val inner = type()
                expect(Comma)
                val size = intergerLiteral()
                expect(CloseS)
                ArrayType(inner, size)
            }
            accept(OpenB) -> {
                val paramTypes = list(CloseB) { type() }
                expect(Arrow)
                val retType = type()
                FunctionType(paramTypes, retType)
            }
            else -> expected("type")
        }

        var curr = inner
        while (accept(Star))
            curr = curr.pointer
        return curr
    }

    private fun idName() = expect(Id).text.drop(1)

    private fun blockName(): String {
        val text = expect(BlockId).text
        return text.substring(1, text.length - 1)
    }

    private fun intergerLiteral() = expect(Number).text.toInt()

    private fun blockPlaceholder(): BasicBlock {
        val name = blockName()
        return localBlockPlaceholders.getOrPut(name) { BasicBlock("placeholder-$name") }
    }

    private fun valuePlaceholder(name: String, type: Type): PlaceholderValue {
        val value = localVariablePlaceholders[name]

        return if (value == null) {
            val new = PlaceholderValue(type)
            localVariablePlaceholders[name] = new
            new
        } else {
            if (value.type != type)
                error("Conflicting types for $name: $type and ${value.type}")
            value
        }
    }

    private inline fun <E> list(end: IrTokenType, element: () -> E) = list(end, Comma, element)

    private fun requireTypeMatch(expected: Type, actual: Type) {
        if (expected != actual)
            error("Type mismatch: expected $expected, got $actual")
    }
}

private class PlaceholderValue(type: Type) : Value(type) {
    override fun untypedStr(env: NameEnv) = "PH"
}
package language.ir

abstract class Value(val type: Type) : Node() {
    val users = mutableSetOf<Node>()

    protected open val replaceAble = true

    /**
     * Replace all uses of this [Value] with [to]
     */
    open fun replaceWith(to: Value) {
        require(replaceAble) { "can't replace value $this of type ${this::class.java.name}" }

        for (user in this.users.toSet())
            user.replaceOperand(this, to)

        check(users.isEmpty()) { "value should have no users left after replacement" }
    }

    override fun verify() {
        super.verify()
        for (user in users)
            check(!user.deleted) { "users can't be deleted" }
    }

    abstract fun str(env: NameEnv): String
}

class Constant constructor(type: Type, val value: Int) : Value(type) {
    init {
        require(type is IntegerType)
    }

    override val replaceAble = false
    override fun str(env: NameEnv) = "$value $type"

    override fun doVerify() {}

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Constant
        if (type != other.type) return false
        if (value != other.value) return false

        return true
    }

    override fun hashCode(): Int {
        var result = value
        result = 31 * result + type.hashCode()
        result = 31 * result + replaceAble.hashCode()
        return result
    }
}

object UnitValue : Value(UnitType) {
    override fun doVerify() {}
    override fun str(env: NameEnv) = "unit"
}
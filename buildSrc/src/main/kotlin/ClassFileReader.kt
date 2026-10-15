import java.io.ByteArrayInputStream
import java.io.DataInputStream

/**
 * A small reader for JVM class files, for the egress check (Egress.kt). It reads the constant pool, the class header,
 * fields, methods and their bytecode, and reports every reference to another class or member together with where in the
 * class it sits. No dependency: the format is JVMS chapter 4, and only the parts that can name a type are decoded.
 */
object ClassFileReader {
    /** One thing a class names. [member] is "name descriptor" for a field or method reference, null for a bare type. */
    data class Ref(
        /** Where in the scanned class: "class header", "field x", "method run(I)V", "constant pool". */
        val location: String,
        /** The class or interface named (internal form, e.g. java/net/Socket), or the owner of [member]. */
        val owner: String,
        val member: String? = null,
        /** Types named by the member's descriptor or by a signature, in internal form. */
        val descriptorTypes: List<String> = emptyList(),
        /** A string constant, when this reference is one (owner is then empty). */
        val string: String? = null,
    )

    class ParsedClass(val name: String, val refs: List<Ref>)

    private const val UTF8 = 1
    private const val INTEGER = 3
    private const val FLOAT = 4
    private const val LONG = 5
    private const val DOUBLE = 6
    private const val CLASS = 7
    private const val STRING = 8
    private const val FIELDREF = 9
    private const val METHODREF = 10
    private const val INTERFACE_METHODREF = 11
    private const val NAME_AND_TYPE = 12
    private const val METHOD_HANDLE = 15
    private const val METHOD_TYPE = 16
    private const val DYNAMIC = 17
    private const val INVOKE_DYNAMIC = 18
    private const val MODULE = 19
    private const val PACKAGE = 20

    private val typeInDescriptor = Regex("""L([^;<>.\[]+)[;<]""")

    /** Every class type a field or method descriptor or a generic signature names. */
    fun typesIn(descriptor: String): List<String> = typeInDescriptor.findAll(descriptor).map { it.groupValues[1] }.toList()

    /** The element class of a CONSTANT_Class name, which may be an array descriptor ("[Ljava/net/Socket;"). */
    fun classNameOf(entry: String): String? =
        if (entry.startsWith("[")) typesIn(entry).firstOrNull() else entry

    private class Pool(val tags: IntArray, val values: Array<Any?>) {
        /** invokedynamic and dynamic constants met in code, with where: their bootstrap arguments belong there too. */
        val dynamicUses = mutableListOf<Pair<String, Int>>()

        /** The BootstrapMethods table: per entry, the bootstrap method handle followed by its static arguments. */
        var bootstraps: List<IntArray> = emptyList()

        fun utf8(i: Int): String = values[i] as? String ?: throw IllegalArgumentException("constant $i is not Utf8")
        fun className(i: Int): String = utf8(values[i] as Int)
        fun tag(i: Int): Int = if (i in 1 until tags.size) tags[i] else 0
    }

    fun parse(bytes: ByteArray): ParsedClass {
        val input = DataInputStream(ByteArrayInputStream(bytes))
        require(input.readInt() == 0xCAFEBABE.toInt()) { "not a class file" }
        input.readUnsignedShort() // minor
        input.readUnsignedShort() // major
        val pool = readPool(input)
        val refs = mutableListOf<Ref>()
        input.readUnsignedShort() // access
        val thisClass = pool.className(input.readUnsignedShort())
        val superIndex = input.readUnsignedShort()
        if (superIndex != 0) refs += Ref("class header", pool.className(superIndex))
        repeat(input.readUnsignedShort()) { refs += Ref("class header", pool.className(input.readUnsignedShort())) }

        repeat(input.readUnsignedShort()) {
            input.readUnsignedShort()
            val name = pool.utf8(input.readUnsignedShort())
            val descriptor = pool.utf8(input.readUnsignedShort())
            val where = "field $name"
            refs += descriptorRefs(where, descriptor)
            readAttributes(input, pool, where, refs, code = false)
        }
        repeat(input.readUnsignedShort()) {
            input.readUnsignedShort()
            val name = pool.utf8(input.readUnsignedShort())
            val descriptor = pool.utf8(input.readUnsignedShort())
            val where = "method $name$descriptor"
            refs += descriptorRefs(where, descriptor)
            readAttributes(input, pool, where, refs, code = true)
        }
        readAttributes(input, pool, "class header", refs, code = false)

        // A method reference (`Socket::new`, `InetAddress::getByName`) can be named only as a bootstrap argument of the
        // invokedynamic that makes the lambda: report those arguments at the method that holds the invokedynamic.
        for ((where, index) in pool.dynamicUses) bootstrapRefs(pool, index, where, refs, depth = 0)

        // Whatever the walk above did not reach (bootstrap arguments, nest and inner-class tables, anything a newer class
        // file version adds) is still in the constant pool: report it from there, so nothing named can be missed.
        refs += poolRefs(pool)
        return ParsedClass(thisClass, refs.distinct())
    }

    private fun descriptorRefs(where: String, descriptor: String): List<Ref> = typesIn(descriptor).map { Ref(where, it) }

    private fun readPool(input: DataInputStream): Pool {
        val count = input.readUnsignedShort()
        val tags = IntArray(count)
        val values = arrayOfNulls<Any?>(count)
        var i = 1
        while (i < count) {
            val tag = input.readUnsignedByte()
            tags[i] = tag
            values[i] = when (tag) {
                UTF8 -> input.readUTF()
                INTEGER, FLOAT -> input.readInt()
                LONG, DOUBLE -> input.readLong()
                CLASS, STRING, METHOD_TYPE, MODULE, PACKAGE -> input.readUnsignedShort()
                FIELDREF, METHODREF, INTERFACE_METHODREF, NAME_AND_TYPE, DYNAMIC, INVOKE_DYNAMIC ->
                    intArrayOf(input.readUnsignedShort(), input.readUnsignedShort())
                METHOD_HANDLE -> intArrayOf(input.readUnsignedByte(), input.readUnsignedShort())
                else -> throw IllegalArgumentException("unknown constant pool tag $tag at $i")
            }
            i += if (tag == LONG || tag == DOUBLE) 2 else 1
        }
        return Pool(tags, values)
    }

    /** The reference a constant names, or null for a number, a name-and-type, a module or a package. */
    private fun constantRef(pool: Pool, index: Int, where: String): Ref? = when (pool.tag(index)) {
        CLASS -> classNameOf(pool.className(index))?.let { Ref(where, it) }
        STRING -> Ref(where, "", string = pool.utf8(pool.values[index] as Int))
        FIELDREF, METHODREF, INTERFACE_METHODREF -> {
            val (classIndex, natIndex) = pool.values[index] as IntArray
            val (nameIndex, typeIndex) = pool.values[natIndex] as IntArray
            val owner = pool.className(classIndex)
            val descriptor = pool.utf8(typeIndex)
            Ref(where, classNameOf(owner) ?: owner, "${pool.utf8(nameIndex)} $descriptor", typesIn(descriptor))
        }
        METHOD_TYPE -> pool.utf8(pool.values[index] as Int).let { Ref(where, "", null, typesIn(it)) }
        METHOD_HANDLE -> constantRef(pool, (pool.values[index] as IntArray)[1], where)
        DYNAMIC, INVOKE_DYNAMIC -> {
            val natIndex = (pool.values[index] as IntArray)[1]
            val (nameIndex, typeIndex) = pool.values[natIndex] as IntArray
            val descriptor = pool.utf8(typeIndex)
            Ref(where, "", "${pool.utf8(nameIndex)} $descriptor", typesIn(descriptor))
        }
        else -> null
    }

    private fun poolRefs(pool: Pool): List<Ref> = (1 until pool.tags.size).mapNotNull { constantRef(pool, it, "constant pool") }

    private fun readAttributes(input: DataInputStream, pool: Pool, where: String, refs: MutableList<Ref>, code: Boolean) {
        repeat(input.readUnsignedShort()) {
            val name = pool.utf8(input.readUnsignedShort())
            val body = ByteArray(input.readInt()).also { input.readFully(it) }
            val data = DataInputStream(ByteArrayInputStream(body))
            when (name) {
                "Signature" -> refs += descriptorRefs(where, pool.utf8(data.readUnsignedShort()))
                "ConstantValue" -> constantRef(pool, data.readUnsignedShort(), where)?.let { refs += it }
                "Exceptions" -> repeat(data.readUnsignedShort()) { refs += Ref(where, pool.className(data.readUnsignedShort())) }
                "RuntimeVisibleAnnotations", "RuntimeInvisibleAnnotations" ->
                    repeat(data.readUnsignedShort()) { readAnnotation(data, pool, where, refs) }
                "RuntimeVisibleParameterAnnotations", "RuntimeInvisibleParameterAnnotations" ->
                    repeat(data.readUnsignedByte()) { repeat(data.readUnsignedShort()) { readAnnotation(data, pool, where, refs) } }
                "AnnotationDefault" -> readElementValue(data, pool, where, refs)
                "Code" -> if (code) readCode(data, pool, where, refs)
                "BootstrapMethods" -> pool.bootstraps = List(data.readUnsignedShort()) {
                    val method = data.readUnsignedShort()
                    IntArray(data.readUnsignedShort() + 1) { i -> if (i == 0) method else data.readUnsignedShort() }
                }
            }
        }
    }

    private fun readAnnotation(data: DataInputStream, pool: Pool, where: String, refs: MutableList<Ref>) {
        refs += descriptorRefs(where, pool.utf8(data.readUnsignedShort()))
        repeat(data.readUnsignedShort()) {
            data.readUnsignedShort()
            readElementValue(data, pool, where, refs)
        }
    }

    private fun readElementValue(data: DataInputStream, pool: Pool, where: String, refs: MutableList<Ref>) {
        when (val tag = data.readUnsignedByte().toChar()) {
            'B', 'C', 'D', 'F', 'I', 'J', 'S', 'Z', 's' -> data.readUnsignedShort()
            'e' -> {
                refs += descriptorRefs(where, pool.utf8(data.readUnsignedShort()))
                data.readUnsignedShort()
            }
            'c' -> refs += descriptorRefs(where, pool.utf8(data.readUnsignedShort()))
            '@' -> readAnnotation(data, pool, where, refs)
            '[' -> repeat(data.readUnsignedShort()) { readElementValue(data, pool, where, refs) }
            else -> throw IllegalArgumentException("unknown annotation element tag $tag")
        }
    }

    private fun readCode(data: DataInputStream, pool: Pool, where: String, refs: MutableList<Ref>) {
        data.readUnsignedShort() // max_stack
        data.readUnsignedShort() // max_locals
        val code = ByteArray(data.readInt()).also { data.readFully(it) }
        walkBytecode(code) { index ->
            constantRef(pool, index, where)?.let { refs += it }
            if (pool.tag(index) == INVOKE_DYNAMIC || pool.tag(index) == DYNAMIC) pool.dynamicUses += where to index
        }
        repeat(data.readUnsignedShort()) {
            data.readUnsignedShort(); data.readUnsignedShort(); data.readUnsignedShort()
            val catchType = data.readUnsignedShort()
            if (catchType != 0) refs += Ref(where, pool.className(catchType))
        }
        readAttributes(data, pool, where, refs, code = false)
    }

    /** The bootstrap method and static arguments of the invokedynamic or dynamic constant at [index], as refs at [where]. */
    private fun bootstrapRefs(pool: Pool, index: Int, where: String, refs: MutableList<Ref>, depth: Int) {
        val entry = pool.bootstraps.getOrNull((pool.values[index] as IntArray)[0]) ?: return
        for (argument in entry) {
            constantRef(pool, argument, where)?.let { refs += it }
            val tag = pool.tag(argument)
            if ((tag == DYNAMIC || tag == INVOKE_DYNAMIC) && depth < 4) bootstrapRefs(pool, argument, where, refs, depth + 1)
        }
    }

    /** Calls [onConstant] with each constant pool index an instruction of [code] uses. */
    fun walkBytecode(code: ByteArray, onConstant: (Int) -> Unit) {
        fun u1(at: Int) = code[at].toInt() and 0xFF
        fun u2(at: Int) = (u1(at) shl 8) or u1(at + 1)
        fun s4(at: Int) = (u1(at) shl 24) or (u1(at + 1) shl 16) or (u1(at + 2) shl 8) or u1(at + 3)
        var pc = 0
        while (pc < code.size) {
            val op = u1(pc)
            pc += when (op) {
                0x12 -> { onConstant(u1(pc + 1)); 2 } // ldc
                0x13, 0x14, // ldc_w, ldc2_w
                in 0xB2..0xB8, // get/putstatic, get/putfield, invokevirtual, invokespecial, invokestatic
                0xBB, 0xBD, 0xC0, 0xC1, // new, anewarray, checkcast, instanceof
                -> { onConstant(u2(pc + 1)); 3 }
                0xB9, 0xBA -> { onConstant(u2(pc + 1)); 5 } // invokeinterface, invokedynamic
                0xC5 -> { onConstant(u2(pc + 1)); 4 } // multianewarray
                0x10, 0xBC, 0xA9, in 0x15..0x19, in 0x36..0x3A -> 2 // bipush, newarray, ret, loads, stores
                0x11, 0x84, in 0x99..0xA8, 0xC6, 0xC7 -> 3 // sipush, iinc, branches, ifnull, ifnonnull
                0xC8, 0xC9 -> 5 // goto_w, jsr_w
                0xC4 -> if (u1(pc + 1) == 0x84) 6 else 4 // wide
                0xAA -> { // tableswitch
                    val base = (pc + 4) and 3.inv()
                    val low = s4(base + 4)
                    val high = s4(base + 8)
                    base - pc + 12 + (high - low + 1) * 4
                }
                0xAB -> { // lookupswitch
                    val base = (pc + 4) and 3.inv()
                    base - pc + 8 + s4(base + 4) * 8
                }
                in 0x00..0xC3 -> 1
                // 0xCA (breakpoint) and above never appear in a class file: fail closed rather than guess a length.
                else -> throw IllegalArgumentException("bad opcode $op at $pc")
            }
        }
    }
}

package me.anno.zauber.ast.simple

import me.anno.generation.java.JavaSourceGenerator.Companion.isCast
import me.anno.utils.StringStyles.bold
import me.anno.utils.assertEquals
import me.anno.zauber.ast.reverse.SimpleTailCall
import me.anno.zauber.ast.rich.Flags
import me.anno.zauber.ast.rich.TokenListIndex.resolveOrigin
import me.anno.zauber.ast.rich.expression.Expression
import me.anno.zauber.ast.rich.expression.constants.NumberExpression
import me.anno.zauber.ast.rich.member.Field
import me.anno.zauber.ast.rich.member.MethodLike
import me.anno.zauber.ast.simple.ASTSimplifier.nativeNumbers
import me.anno.zauber.ast.simple.controlflow.SimpleReturn
import me.anno.zauber.ast.simple.expression.SimpleAssignment
import me.anno.zauber.ast.simple.expression.SimpleBoxCast
import me.anno.zauber.ast.simple.expression.SimpleCallable
import me.anno.zauber.ast.simple.expression.SimpleConstructorCall
import me.anno.zauber.ast.simple.fields.*
import me.anno.zauber.scope.Scope
import me.anno.zauber.typeresolution.ResolutionContext
import me.anno.zauber.types.Specialization
import me.anno.zauber.types.Type
import me.anno.zauber.types.impl.ClassType

class SimpleGraph(val method0: Specialization) {

    val method = method0.method
    val expectedReturnType = method.resolveReturnType(method0)

    val blocks = ArrayList<SimpleBlock>()
    val startBlock = SimpleBlock(this)
    val simpleFields = ArrayList<SimpleField>()
    val localFields = ArrayList<LocalField>()

    // these cannot be SimpleField, because we join SimpleFields,
    //  and these cannot be properly joined (except for LLVM)
    var thisField: LocalField? = null
    var selfField: LocalField? = null
    var parameterFields: List<LocalField> = emptyList()

    // typically used lots in graphs to represent no value, e.g. from while-loops
    var unitField: SimpleField? = null

    val capturedFields = HashMap<Capture, SimpleField>()

    // todo these labels are only needed when building the graph, discard them later
    val continueLabels = HashMap<Scope, SimpleBlock>()
    val breakLabels = HashMap<Scope, SimpleBlock>()

    init {
        startBlock.isEntryPoint = true
        blocks.add(startBlock)
    }

    fun addBlock(): SimpleBlock {
        val node = SimpleBlock(this)
        blocks.add(node)
        return node
    }

    fun field(type: Type, constantRef: Expression? = null): SimpleField {
        val id = if (simpleFields.isEmpty()) 0 else simpleFields.last().id + 1
        val field = SimpleField(type, id, constantRef)
        simpleFields.add(field)
        return field
    }

    fun getOrPutLocalField(field: Field, context: ResolutionContext): LocalField {
        var localField = localFields.firstOrNull { it.field == field }
        if (localField == null) {
            val type = field.resolveValueType(context)
            localField = createLocalField(field, field.newName, type, true)
        }
        return localField
    }

    fun initializeSpecialFields(context: ResolutionContext) {

        check(localFields.isEmpty()) {
            "Expected no local fields to be present"
        }

        val method = method
        if (method.ownerScope.isClassLike()) {
            val selfType = method.ownerScope.typeWithArgs.specialize(context)
            thisField = createLocalField(null, "this", selfType, false)
        }
        if (method.hasExplicitSelfType) {
            val selfType = method.selfType!!.specialize(context)
            selfField = createLocalField(null, "__self", selfType, false)
        }
        parameterFields = method.valueParameters.map { parameter ->
            val type = parameter.type.specialize(context)
            val field = parameter.getOrCreateField(null, Flags.NONE)
            createLocalField(field, parameter.newName, type, false)
        }
    }

    fun createLocalField(field: Field?, name: String, type: Type, insideMethod: Boolean): LocalField {
        val localField = LocalField(field, name, type, localFields.size, insideMethod)
        localFields.add(localField)
        return localField
    }

    fun onCapturedField(field: Field) {
        field.isCaptured = true
        method.capturedFields += field // todo may be specialization dependant...
    }

    fun readCapturedField(owner: MethodLike, field: Field, valueType: Type): SimpleField {
        onCapturedField(field)
        return capturedFields.getOrPut(Capture(owner, field)) { field(valueType) }
    }

    override fun toString(): String {
        return "${bold("SimpleGraph")}[$method]\n" +
                "[${blocks.size} nodes, ${simpleFields.size} simple fields, ${localFields.size} local fields]\n" +
                "${bold("this:")} $thisField\n" +
                (if (selfField != null) "${bold("self:")} $selfField\n" else "") +
                "${bold("unit:")} $unitField\n" +
                "${bold("params:")} $parameterFields\n" +
                "${bold("locals:")} $localFields\n" +
                blocks.joinToString("\n") { it.toString() }
    }

    fun removeSuperCalls() {
        for (block in blocks) {
            block.instructions.removeIf {
                it is SimpleConstructorCall && !it.forAllocation
            }
        }
    }

    fun removeConstantFields() {
        removeFieldIf { it.constantRef != null && it.dst.mergeInfo == null }
        for (block in blocks) {
            block.instructions.removeIf {
                it is SimpleAssignment &&
                        it.dst.constantRef != null &&
                        it.dst.mergeInfo == null
            }
        }
    }

    fun removeObjectFields() {
        removeFieldIf {
            val type = it.type
            type is ClassType && type.clazz.isObjectLike()
        }
    }

    fun removeWriteOnlyFields() {
        removeFieldIf { it.numReads == 0 }
    }

    fun replaceYieldsByInnerClass() {
        // todo if inner classes/methods reference a mutable field,
        //  create an inner class, too
        TODO()
    }

    fun inlineValueClasses() {
        TODO()
    }

    fun removeFieldIf(condition: (SimpleField) -> Boolean) {
        simpleFields.removeIf { field ->
            if (condition(field)) {
                field.id = -100_000 - field.id
                check(field.id < 0)
                true
            } else false
        }
    }

    fun renumberFields() {
        for (i in simpleFields.indices) {
            simpleFields[i].id = i
        }
    }

    fun giveLocalFieldsUniqueNames(keywords: Collection<String>) {
        val foundNames = HashMap<String, Any>()

        // protect these special names:
        foundNames["nextBlockId"] = Unit // for tail calls
        for (name in keywords) foundNames[name] = Unit
        for (field in localFields) {
            // contains this, self and valueParams if needed
            foundNames.getOrPut(field.name) { field }
        }

        for (field in localFields) {
            val prevField = foundNames[field.name]
            if (prevField == field) continue

            if (field.isInsideMethod) {
                findNewName(foundNames, field)
            } else if (field.field != null) {
                field.newName = field.field.newName
            } else {
                // todo should be synchronized with parameters at all times
                field.newName += "_"
            }
        }
    }

    private fun findNewName(allNames: HashMap<String, Any>, field: LocalField) {
        val oldName = field.name
        var id = 0
        while (true) {
            val newName = "${oldName}_${id++}"
            if (newName !in allNames) {
                allNames[newName] = field
                field.name = newName
                return
            }
        }
    }

    fun validateBlocks() {
        for (block in blocks) {
            val b0 = block.ifBranch
            val b1 = block.elseBranch
            if (b0 != null) {
                check(b0 in blocks)
                check(block in b0.inputBlocks)
            }
            if (b1 != null) {
                check(b1 in blocks)
                check(block in b1.inputBlocks)
            }
        }
        for (i in 1 until blocks.size) {
            val b0 = blocks[i - 1]
            val b1 = blocks[i]
            check(b0.id < b1.id)
        }
    }

    fun removeMergeInfoInstructions() {
        for (block in blocks) {
            block.instructions.removeIf { it is SimpleMerge }
        }
    }

    fun removeSimpleGetObject() {
        for (block in blocks) {
            block.instructions.removeIf { it is SimpleGetObject }
        }
    }

    fun hasTailCalls(): Boolean {
        return blocks.any { block ->
            block.instructions.any { instr ->
                instr is SimpleTailCall
            }
        }
    }

    /**
     * find simple fields, which are only used once,
     * and where the only read is immediately next to the assignment
     * */
    fun markSimpleReadImmediatelyAfterAssignment() {
        for (block in blocks) {
            val instructions = block.instructions
            var i = 0
            while (++i <= instructions.size) {
                val decl = instructions[i - 1]
                if (decl !is SimpleAssignment) continue
                if (decl.dst.mergeInfo != null || decl.dst.numReads != 1) continue

                if (i == instructions.size) {
                    val use = block.branchCondition
                    if (use == decl.dst) {
                        instructions.removeAt(i - 1)
                        decl.dst.immediateValue = decl
                        // done
                    }
                } else {
                    val use = instructions[i]
                    if (use.hasInput(decl.dst)) {
                        instructions.removeAt(i - 1)
                        decl.dst.immediateValue = decl
                        i--
                    }
                }
            }
        }
    }

    // todo also find LocalFields, which are assigned on one level only,
    //  s.t. we can declare them locally (nicer looking code)

    /**
     * detect type transitions (type1 -> type2),
     * and add explicit casts
     * */
    fun findBoxingAndUnboxing(findAllCasts: Boolean = false) {

        val context = ResolutionContext.minimal // todo we do need our specialization
        for (block in blocks) {
            val instructions = block.instructions
            var i = instructions.size - 1
            while (i >= 0) {
                when (val instr = instructions[i]) {
                    is SimpleSetClassField -> {
                        val contextI = context.withSpec(instr.specialization)
                        val dstType = instr.field.resolveValueType(contextI)
                            .specialize()
                        instr.value = addCastIfNeeded(
                            instr.value, dstType,
                            findAllCasts, instructions, i, instr
                        )
                    }
                    // getters are always fine <- todo check whether we can remove this block
                    is SimpleGetLocalField -> {
                        val srcType = instr.field.type
                        val dstType = instr.dst.type
                        if (needsCast(srcType, dstType, findAllCasts)) {
                            // reversed assignment
                            println("get-cast: $srcType -> $dstType")
                            val tmpField = field(srcType)
                            val cast = SimpleBoxCast(instr.dst, tmpField, instr.scope, instr.origin)
                            instructions.add(i + 1, cast)
                            instr.dst = tmpField.use()
                        }
                    }
                    is SimpleSetLocalField -> instr.value = addCastIfNeeded(
                        instr.value, instr.field.type,
                        findAllCasts, instructions, i, instr
                    )
                    is SimpleReturn -> instr.value = addCastIfNeeded(
                        instr.value, expectedReturnType,
                        findAllCasts, instructions, i, instr
                    )
                    is SimpleMerge -> {
                        if (instr.dst.dst.numReads > 0) {
                            val dstType = instr.dst.type
                            val ifType = instr.ifField.type
                            val elseType = instr.elseField.type
                            if (needsCast(ifType, dstType, findAllCasts)) {
                                TODO("Insert cast into merge $instr")
                            }
                            if (needsCast(elseType, dstType, findAllCasts)) {
                                TODO("Insert cast into merge $instr")
                            }
                        }
                    }
                    // todo calls and constructors can require casts, too
                    // todo for this, self, and parameters
                    is SimpleCallable -> {
                        assertEquals(instr.valueParameters.size, instr.sample.valueParameters.size) {
                            "Value-Params/Callable mismatch for ${instr.sample}\n" +
                                    "  at ${resolveOrigin(instr.origin)}"
                        }

                        /*val srcType0 = instr.thisInstance.type
                        val dstType0 = instr.sample.ownerScope.typeWithArgs2
                            .specialize(instr.specialization)
                        if (needsCast(srcType0, dstType0, findAllCasts)) {
                            val tmpField = field(dstType0)
                            val cast = SimpleBoxCast(tmpField, instr.thisInstance, instr.scope, instr.origin)
                            instructions.add(i, cast)
                            instr.thisInstance = tmpField.use()
                        }

                        if (instr is SimpleMethodCall && instr.sample.hasExplicitSelfType) {
                            val srcType1 = instr.selfInstance!!.type
                            val dstType1 = instr.sample.selfType!!
                                .specialize(instr.specialization)
                            if (needsCast(srcType1, dstType1, findAllCasts)) {
                                val tmpField = field(dstType1)
                                val cast = SimpleBoxCast(tmpField, instr.selfInstance!!, instr.scope, instr.origin)
                                instructions.add(i, cast)
                                instr.selfInstance = tmpField.use()
                            }
                        }*/

                        for (i in instr.valueParameters.indices) {
                            val src = instr.valueParameters[i]
                            val dst = instr.sample.valueParameters[i]
                            val dstType = dst.type.specialize(instr.specialization)
                            val tmpField = addCastIfNeeded(src, dstType, findAllCasts, instructions, i, instr)
                            if (tmpField !== src) instr.setValueParameter(i, tmpField)
                        }
                    }
                }
                i--
            }
        }
    }

    fun addCastIfNeeded(
        src: SimpleField, dstType: Type, findAllCasts: Boolean,
        instructions: ArrayList<SimpleInstruction>, i: Int,
        self: SimpleInstruction
    ): SimpleField {
        val srcType = src.type
        return if (needsCast(srcType, dstType, findAllCasts)) {
            // println("cast: $srcType -> $dstType")
            if (isConstNumberCast(src, dstType)) {
                field(dstType, src.constantRef)
            } else {
                val tmpField = field(dstType)
                val cast = SimpleBoxCast(tmpField, src, self.scope, self.origin)
                instructions.add(i, cast)
                tmpField
            }.use()
        } else src
    }

    fun needsCast(srcType: Type, dstType: Type, findAllCasts: Boolean): Boolean {
        return dstType != srcType &&
                (findAllCasts || dstType.isValue() || srcType.isValue())
    }

    fun clone(): SimpleGraph {

        check(blocks.withIndex().all { it.index == it.value.id })
        check(localFields.withIndex().all { it.index == it.value.id })
        check(simpleFields.withIndex().all { it.index == it.value.id })

        val cloned = SimpleGraph(method0)
        check(cloned.blocks.size == 1)
        check(cloned.localFields.isEmpty())
        check(cloned.simpleFields.isEmpty())

        // ensure blocks
        while (cloned.blocks.size < blocks.size) {
            cloned.addBlock()
        }

        // ensure simple fields
        while (cloned.simpleFields.size < simpleFields.size) {
            val field = simpleFields[cloned.simpleFields.size]
            cloned.field(field.type, field.constantRef)
        }

        // ensure local fields
        while (cloned.localFields.size < localFields.size) {
            val field = localFields[cloned.localFields.size]
            cloned.createLocalField(field.field, field.name, field.type, field.isInsideMethod)
        }

        cloned.capturedFields.putAll(capturedFields.mapValues { cloned(it.value, cloned) })
        cloned.breakLabels.putAll(breakLabels.mapValues { cloned(it.value, cloned) })
        cloned.continueLabels.putAll(continueLabels.mapValues { cloned(it.value, cloned) })

        for (block in blocks) {
            val clonedBlock = cloned.blocks[block.id]
            clonedBlock.instructions.ensureCapacity(block.instructions.size)
            for (instr in block.instructions) {
                clonedBlock.instructions.add(instr.clone(this, cloned))
            }

            clonedBlock.isEntryPoint = block.isEntryPoint
            clonedBlock.branchCondition = cloned1(block.branchCondition, cloned)
            clonedBlock.ifBranch = cloned1(block.ifBranch, cloned)
            clonedBlock.elseBranch = cloned1(block.elseBranch, cloned)
        }

        cloned.unitField = cloned1(unitField, cloned)
        cloned.thisField = cloned1(thisField, cloned)
        cloned.selfField = cloned1(selfField, cloned)
        cloned.parameterFields = parameterFields.map { cloned(it, cloned) }

        for (i in cloned.simpleFields.indices) {
            val src = simpleFields[i]
            val dst = cloned.simpleFields[i]
            dst.numReads = src.numReads
            dst.fromLocalField = cloned1(src.fromLocalField, cloned)
            assertEquals((dst.mergeInfo != null), (src.mergeInfo != null))
            assertEquals(dst.dst.id, src.dst.id)
        }

        return cloned
    }

    fun isConstNumberCast(src: SimpleField, dstType: Type): Boolean {
        val srcType = src.type
        if (srcType is ClassType &&
            src.constantRef is NumberExpression &&
            srcType in nativeNumbers
        ) {
            val castMethod = srcType.clazz.implicitCastMethods[dstType]
            return castMethod != null && isCast(castMethod.name)
        } else return false
    }

    fun cloned(field: SimpleField, cloned: SimpleGraph): SimpleField {
        return cloned.simpleFields[field.id]
    }

    fun cloned1(field: SimpleField?, cloned: SimpleGraph): SimpleField? {
        if (field == null) return null
        return cloned.simpleFields[field.id]
    }

    fun cloned(field: LocalField, cloned: SimpleGraph): LocalField {
        return cloned.localFields[field.id]
    }

    fun cloned1(field: LocalField?, cloned: SimpleGraph): LocalField? {
        if (field == null) return null
        return cloned.localFields[field.id]
    }

    fun cloned(block: SimpleBlock, cloned: SimpleGraph): SimpleBlock {
        return cloned.blocks[block.id]
    }

    fun cloned1(block: SimpleBlock?, cloned: SimpleGraph): SimpleBlock? {
        if (block == null) return null
        return cloned.blocks[block.id]
    }

}
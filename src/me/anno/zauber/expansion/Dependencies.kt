package me.anno.zauber.expansion

import me.anno.generation.java.JavaSourceGenerator.Companion.getCastTargetType
import me.anno.utils.ResetThreadLocal.Companion.threadLocal
import me.anno.zauber.ast.rich.Flags
import me.anno.zauber.ast.rich.Flags.hasAnyFlag
import me.anno.zauber.ast.rich.Flags.hasFlag
import me.anno.zauber.ast.rich.member.Constructor
import me.anno.zauber.ast.simple.ASTSimplifier
import me.anno.zauber.ast.simple.ASTSimplifier.nativeNumbers
import me.anno.zauber.ast.simple.SimpleBlock.Companion.needsCopy
import me.anno.zauber.ast.simple.constants.SimpleNumber
import me.anno.zauber.ast.simple.constants.SimpleString
import me.anno.zauber.ast.simple.expression.SimpleAllocateInstance
import me.anno.zauber.ast.simple.expression.SimpleAssignment
import me.anno.zauber.ast.simple.expression.SimpleCallable
import me.anno.zauber.ast.simple.expression.SimpleGetTypeInstance
import me.anno.zauber.ast.simple.fields.SimpleGetClassField
import me.anno.zauber.ast.simple.fields.SimpleGetObject
import me.anno.zauber.ast.simple.fields.SimpleSetClassField
import me.anno.zauber.logging.LogManager
import me.anno.zauber.scope.ScopeInitType
import me.anno.zauber.typeresolution.ParameterList.Companion.emptyParameterList
import me.anno.zauber.types.Specialization
import me.anno.zauber.types.Types
import me.anno.zauber.types.impl.ClassType

/**
 * Given a set of entry points,
 *   find all methods and classes that are constructable,
 *   so we can create a minimal executable
 *
 * todo we have some dependency conditions,
 *  but I still believe that we could define this as a graph coloring problem
 * */
object Dependencies {

    private val LOGGER = LogManager.getLogger(Dependencies::class)

    private val reached by threadLocal { DependencyData() }

    private val ByteArrayType by threadLocal { Types.Array.withTypeParameter(Types.Byte) }
    private val ByteReferenceType by threadLocal { Types.Ref.withTypeParameter(Types.Byte) }

    fun addClass(type: ClassType, markDefaultConstructor: Boolean = false) {
        addClass(Specialization(type), markDefaultConstructor)
    }

    fun addClass(type: Specialization, markDefaultConstructor: Boolean = false) {
        if (reached.createdClasses.add(type)) {
            markSuperTypesConstructable(type)
            markChildMethodsReachable(type)
        }

        if (markDefaultConstructor) {
            val ownerConstructor = type.clazz.getOrCreatePrimaryConstructorScope()
            addMethod(type.withScope(ownerConstructor))
        }
    }

    // todo calling Int.toString() doesn't need Any.toString(), because super is not called automatically
    // todo but calling Any.toString() and having Int constructable, now we need Int.toString(), because Any could be Int
    //  -> todo we could make casting one type to another be also data we collect, and then we can limit these interactions

    fun addMethod(method: Specialization) {
        check(method.isMethodLike()) { "$method is not a method" }
        // if method is a macro, skip it, we cannot execute it at runtime anyway
        if (method.method.flags.hasFlag(Flags.MACRO)) return

        if (reached.calledMethods.add(method)) {
            markMethodObjectReachable(method)
            markMethodsInSubClassesReachable(method)
            markCalledMethodsReachable(method)
        }
    }

    private fun markMethodObjectReachable(method: Specialization) {
        val ownerClass = method.method.ownerScope
        if (ownerClass.isObjectLike()) {
            addClass(ownerClass.typeWithArgs2, true)
        }
    }

    private fun markMethodsInSubClassesReachable(method0: Specialization) {

        val method = method0.method
        if (method is Constructor) {
            return // cannot be inherited -> nothing to do
        }

        if (!method.ownerScope.isClassLike() || method.ownerScope.isObjectLike()) {
            return // cannot be inherited -> nothing to do
        }

        // todo check that methods in interfaces automatically get marked OPEN
        if (!method.flags.hasAnyFlag(Flags.OPEN or Flags.OVERRIDE)) {
            check(!method.ownerScope.isInterface()) {
                "$method (inside an interface) misses open-flag"
            }
            return
        }

        val methodName = method.name

        val ownerScope = method.ownerScope
        val ownerSpec = method0.withScope(ownerScope)
        for (childClass in reached.childClasses[ownerSpec].orEmpty()) {
            val childCandidates = childClass.scope!!.methods0
                .filter { it.name == methodName && method in it.superMethods }
            if (childCandidates.size > 1) LOGGER.warn("More child-candidates than expected for $method in $childClass: $childCandidates")
            if (childCandidates.isEmpty()) LOGGER.warn("Missing child-candidate of $method in $childClass")
            for (candidate in childCandidates) {
                val joinedTypeParams = method0 + childClass
                addMethod(joinedTypeParams.withScope(candidate.scope))
            }
        }

        // todo we must check all sub classes whether they are constructable,
        //  and if so, we must add their overridden method to be reachable
    }

    private fun markCalledMethodsReachable(method0: Specialization) {
        // ASTSimplify method, and collect all called methods
        check(method0.isMethodLike())
        val method = method0.method
        if (method.isExternal() || method.hasNoBody()) {
            val owner = method.ownerScope
            val ownerType = owner.typeWithArgs2
            if (method.name.startsWith("to") &&
                ownerType in nativeNumbers
            ) {
                val targetType = getCastTargetType(method.name)
                if (targetType != null) addClass(targetType, true)
            }
            return
        }

        val simplified = ASTSimplifier.simplify(method0, readOnly = true)
        for (node in simplified.blocks) {
            for (instr in node.instructions) {

                // if the dstType is a value class, we need the copy-instruction
                // todo this is not needed, if our target language supports copy OOTB, like C or C++

                // todo is this the right place to copy???
                //  I think we need to copy, when we really assign...
                //  e.g. when passing fields into a function...

                if (instr is SimpleAssignment) {
                    val dstType = instr.dst.type
                    if (dstType.needsCopy()) {
                        dstType as ClassType
                        val method = dstType.clazz.methods0
                            .firstOrNull { it.name == "copy" && it.valueParameters.isEmpty() }
                            ?: error("Missing .copy() in value-type $dstType")
                        val spec = Specialization(
                            method.scope, dstType.typeParameters
                                ?: emptyParameterList()
                        )
                        addMethod(spec)
                    }
                }

                when (instr) {
                    is SimpleCallable -> addMethod(instr.specialization)
                    is SimpleAllocateInstance -> addClass(instr.allocatedType)
                    is SimpleString -> {
                        addClass(Types.String, true)
                        addClass(ByteArrayType, true)
                        addClass(ByteReferenceType, true) // todo only for C/C++/LLVM
                    }
                    is SimpleNumber -> {
                        // println("found number $instr, marking ${instr.dst.type} reachable")
                        addClass(instr.dst.type as ClassType, true)
                    }
                    is SimpleGetObject -> {
                        val scope = instr.objectScope[ScopeInitType.AFTER_DISCOVERY]
                        addClass(scope.typeWithArgs2)
                        val constr = scope.primaryConstructorScope
                        if (constr != null) {
                            addMethod(Specialization.fromSimple(constr))
                        }
                    }
                    is SimpleGetTypeInstance -> addClass(instr.dst.type as ClassType)
                    is SimpleSetClassField -> reached.setFields.add(instr.specialization)
                    is SimpleGetClassField -> reached.getFields.add(instr.specialization)

                    // how do we handle dynamic macros? can only be inside macros, so we're fine (?)
                }
            }
        }
    }

    private fun markSuperTypesConstructable(childType: Specialization) {
        check(childType.clazz.isClassLike())

        // mark all super-types as constructable (because they are)
        val scope = childType.clazz[ScopeInitType.AFTER_DISCOVERY]
        for (superCall in scope.superCalls) {
            val superTypeI = childType.getSuperType(superCall)
            reached.childClasses.getOrPut(superTypeI, ::ArrayList)
                .add(childType)

            // LOGGER.info("$childType extends $superTypeI")

            addClass(superTypeI)
        }
    }

    private fun markChildMethodsReachable(type: Specialization) {
        val scope = type.clazz[ScopeInitType.AFTER_DISCOVERY]
        val methods = scope.methods0
        for (method in methods) {
            // todo if the super-method is reachable, mark this method reachable
        }
    }

    fun collectDependencies(): DependencyData {
        addClass(Types.Unit, true)
        return reached
    }

}
package me.anno.zauber.types.impl

import me.anno.zauber.logging.LogManager
import me.anno.zauber.scope.Scope
import me.anno.zauber.scope.ScopeInitType
import me.anno.zauber.typeresolution.members.ResolvedMember.Companion.resolveGenerics
import me.anno.zauber.types.Type
import me.anno.zauber.types.Types
import me.anno.zauber.types.impl.arithmetic.NullType
import me.anno.zauber.types.impl.arithmetic.UnionType
import me.anno.zauber.types.impl.unresolved.UnresolvedType

object TypeUtils {

    private val LOGGER = LogManager.getLogger(TypeUtils::class)

    fun ClassType.isChildTypeOf(parent: ClassType): Boolean {

        // todo if either is an interface, we need other logic...
        if (clazz.isInterface()) return false
        if (parent.clazz.isInterface()) return false

        var t = this

        var td = t.clazz.getHierarchyDepth()
        val pd = parent.clazz.getHierarchyDepth()
        if (td <= pd) return false // our depth must be larger than that of the parent

        while (td > pd) {
            t = getSuperType(t, this)
            td--
        }

        return t == parent
    }

    fun isDistinctFrom(typeA: ClassType, typeB: ClassType): Boolean {
        if (typeA.clazz.isInterface()) return false
        if (typeB.clazz.isInterface()) return false

        var superTypeA = typeA
        var superTypeB = typeB

        var aDepth = superTypeA.clazz.getHierarchyDepth()
        var bDepth = superTypeB.clazz.getHierarchyDepth()

        while (aDepth > bDepth) {
            superTypeA = getSuperType(superTypeA, typeA)
            aDepth--
        }

        while (bDepth > aDepth) {
            superTypeB = getSuperType(superTypeB, typeB)
            bDepth--
        }

        if (LOGGER.isInfoEnabled) LOGGER.info("$typeA -$aDepth> $superTypeA =?= $superTypeB <$bDepth- $typeB")

        return superTypeA != superTypeB
    }

    fun canInstanceBeBoth(t1: Type, t2: Type): Boolean {
        if (t1 is UnresolvedType || t2 is UnresolvedType)
            return canInstanceBeBoth(t1.resolvedName, t2.resolvedName)

        if (t1 == Types.Nothing || t2 == Types.Nothing) return false
        if (t1 == t2) return true
        if (t1 is UnionType) return t1.types.any { t1i -> canInstanceBeBoth(t1i, t2) }
        if (t2 is UnionType) return t2.types.any { t2i -> canInstanceBeBoth(t1, t2i) }
        if (t1 == NullType && t2 is ClassType) return false
        if (t2 == NullType && t1 is ClassType) return false

        // if t1 or t2 are interfaces, an instance still could be both
        if (t1 is ClassType && t2 is ClassType && isDistinctFrom(t1, t2)) return false

        // todo complete this... is complicated...
        //  and ideally, all these should be resolved/specialized...
        return true // idk
    }

    fun getSuperType(a: ClassType, selfType: ClassType): ClassType {
        val superCall = a.clazz.superCalls.first { it.isClassCall }
        return resolveGenerics(
            selfType, superCall.type,
            a.clazz.typeParameters,
            superCall.type.typeParameters
        ) as ClassType
    }

    fun Scope.getHierarchyDepth(): Int {
        var depth = 0
        var scope = this
        while (true) {
            val superCall = scope[ScopeInitType.AFTER_DISCOVERY]
                .superCalls.firstOrNull { it.isClassCall }
            scope = superCall?.type?.clazz ?: return depth
            depth++
        }
    }
}
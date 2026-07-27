package me.anno.generation.structs

import me.anno.zauber.ast.rich.member.Field
import me.anno.zauber.scope.Scope
import me.anno.zauber.scope.ScopeInitType
import me.anno.zauber.typeresolution.ResolutionContext
import me.anno.zauber.types.Specialization
import me.anno.zauber.types.Type
import me.anno.zauber.types.Types

/**
 * todo use this in WASM and GLSL, too
 * */
abstract class Structures<InnerType, InnerProperty, InnerStruct : LateinitStruct<InnerProperty>> {

    companion object {
        fun align(pos: Int, size: Int): Int {
            val mod = pos % size
            return if (mod > 0) pos - mod + size else pos
        }
    }

    val structs = HashMap<Specialization, InnerStruct>()

    val classIndexProp = createProperty(null, getInnerType(Types.Int), 0, 0)

    var nextTypeIndex = 0

    abstract fun getInnerType(type: Type): InnerType

    abstract fun createProperty(
        field: Field?, type: InnerType,
        index: Int, offset: Int
    ): InnerProperty

    abstract fun createArrayContentProperty(
        elementLLVMType: InnerType, isValue: Boolean,
        index: Int, offset: Int
    ): InnerProperty

    abstract fun createStruct(
        superType: InnerStruct?,
        typeIndex: Int,
        typeName: String,
        isNullable: Boolean
    ): InnerStruct

    abstract fun getClassName(clazz: Scope, specialization: Specialization): String

    abstract fun isStoredField(field: Field): Boolean

    abstract fun getElementSizeAndAlignment(type: InnerType): IntPair

    open fun getClassIndexSize(): Int = 4
    open fun getArrayContentSize(prevSize: Int): Int = align(prevSize, 8) + 8

    fun getStruct(classSpecialization: Specialization): InnerStruct {
        check(classSpecialization.clazz.isClassLike()) {
            "Invalid struct: $classSpecialization"
        }
        var created = false
        val clazz = classSpecialization.clazz
        val s = structs.getOrPut(classSpecialization) {
            classSpecialization.use {

                val superType0 = classSpecialization.superType
                val superType = if (superType0 != null) getStruct(superType0) else null

                created = true
                val typeIndex = nextTypeIndex++
                val typeName = getClassName(clazz, classSpecialization)
                createStruct(superType, typeIndex, typeName, false)
            }
        }
        if (created) {

            // classIndexProp + props,
            s.properties.add(classIndexProp)
            s.sizeInBytes += getClassIndexSize()

            for (field in clazz.fields) {
                if (!isStoredField(field)) continue

                field.ownerScope[ScopeInitType.AFTER_RESOLVE_TYPES]

                val type = getInnerType(field.resolveValueType(ResolutionContext.minimal))
                s.properties.add(createProperty(field, type, s.properties.size, s.sizeInBytes))

                val (size, alignment) = getElementSizeAndAlignment(type)
                s.sizeInBytes = align(s.sizeInBytes, alignment)
                s.sizeInBytes += size
            }

            if (clazz == Types.Array.clazz) {
                val elementType = classSpecialization.typeParameters[0]
                val elementLLVMType = getInnerType(elementType)
                val property = createArrayContentProperty(
                    elementLLVMType, elementType.isValue(),
                    s.properties.size, s.sizeInBytes
                )
                s.properties.add(property)
                s.sizeInBytes = getArrayContentSize(s.sizeInBytes)
            }

        }
        return s
    }

}
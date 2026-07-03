package zauber

class Type {
    external fun isSubTypeOf(other: Type): Boolean
}

class ClassType<V> private constructor(): Type() {
    external val name: String
    external val fields: Array<Field>
    external val methods: Array<Method>
}

class Field(val name: String, val type: Type)
class Method(val name: String, val valueParameters: List<Type>, val returnType: Type)
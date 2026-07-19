package me.anno.zauber.interpreting

data class BlockReturn(val type: ReturnType, val value: Instance) {
    fun retToVal(): BlockReturn {
        return if (type == ReturnType.RETURN) BlockReturn(ReturnType.VALUE, value) else this
    }

    fun then(callOnSuccess: () -> BlockReturn): BlockReturn {
        return if (type.isValue()) callOnSuccess() else this
    }

    fun finish(): Instance {
        if (type.isValue()) return value
        error(this.toString())
    }
}
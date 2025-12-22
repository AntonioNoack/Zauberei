package me.anno.zauberei.typeresolution

enum class InsertMode {
    /**
     * list should be union-ed
     * */
    STRONG,
    /**
     * entries that are added are only weak
     * */
    WEAK,
    /**
     * list is read-only
     * */
    READ_ONLY,
}
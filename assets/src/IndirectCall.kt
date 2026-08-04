package zauber.inheritance

// class -> offset, offset + method -> method-index to call
private external fun readFromClassCallTable(addr: Int): Int

// class -> i0 + i1, i0 .. i1 |=| method -> method-index to call
private external fun readFromInterfaceCallTable(addr: Int): Int

// class -> super-class or -1
private external fun readFromSuperClassTable(addr: Int): Int

// class -> i0 + i1, i0 .. i1 |=| interface
private external fun readFromClassToInterfaceTable(addr: Int): Int

private external fun createString(offset: Int): String

@Suppress("unused")
fun resolveClassCall(classIndex: Int, methodIndex: Int): Int {
    val offset = readFromClassCallTable(classIndex)
    return readFromClassCallTable(offset + methodIndex)
}

@Suppress("unused")
fun resolveInterfaceCall(classIndex: Int, methodIndex: Int): Int {
    var i0 = readFromInterfaceCallTable(classIndex) * 2
    val i1 = readFromInterfaceCallTable(classIndex + 1) * 2
    while (i0 < i1) {
        val givenMethodIndex = readFromInterfaceCallTable(i0)
        if (givenMethodIndex == methodIndex) {
            return readFromInterfaceCallTable(i0 + 1)
        }
        i0 += 2
    }
    // todo fail somehow
    return -1
}

@Suppress("unused")
fun isInstanceOfClass(instanceClassIndex: Int, testClassIndex: Int): Boolean {
    var instanceClassIndex = instanceClassIndex
    while (instanceClassIndex >= 0) {
        if (instanceClassIndex == testClassIndex) return true
        instanceClassIndex = readFromSuperClassTable(testClassIndex)
    }
    return false
}

@Suppress("unused")
fun isInstanceOfInterface(instanceClassIndex: Int, testInterfaceIndex: Int): Boolean {
    var i0 = readFromClassToInterfaceTable(instanceClassIndex)
    val i1 = readFromClassToInterfaceTable(instanceClassIndex + 1)
    while (i0 < i1) {
        if (readFromClassToInterfaceTable(i0) == testInterfaceIndex) {
            return true
        }
        i0++
    }
    return false
}

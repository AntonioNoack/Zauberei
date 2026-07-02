// for calloc
#include <stdlib.h>
// for booleans
#include <stdbool.h>
// for integers with precise size
#include <stdint.h>
// for perror
#include <stdio.h>

#include "CStandardFileIO.h"

u32_file classCallTable;
u32_file interfaceCallTable;
u32_file superClassTable;
u32_file classToInterfaceTable;

int32_t zauber_inheritance_readFromClassCallTable_2clr50n(int32_t index) {
    if (index < 0 || index >= classCallTable.len) {
        perror("Index into classCallTable out of bounds");
        exit(1);
    }
    return classCallTable.data[index];
}

int32_t zauber_inheritance_readFromInterfaceCallTable_2clr50n(int32_t index) {
    if (index < 0 || index >= interfaceCallTable.len) {
        perror("Index into interfaceTable out of bounds");
        exit(1);
    }
    return interfaceCallTable.data[index];
}

int32_t zauber_inheritance_readFromSuperClassTable_2clr50n(int32_t index) {
    if (index < 0 || index >= superClassTable.len) {
        perror("Index into superClassTable out of bounds");
        exit(1);
    }
    return superClassTable.data[index];
}

int32_t zauber_inheritance_readFromClassToInterfaceTable_2clr50n(int32_t index) {
    if (index < 0 || index >= classToInterfaceTable.len) {
        perror("Index into classToInterfaceTable out of bounds");
        exit(1);
    }
    return classToInterfaceTable.data[index];
}

int32_t stdlibMain() {
    if (load_le_u32_file("data/classCallTable.bin", &classCallTable) != 0) {
        perror("Failed loading data/classCallTable.bin");
        return 1;
    }

    if (load_le_u32_file("data/interfaceCallTable.bin", &interfaceCallTable) != 0) {
        perror("Failed loading data/interfaceCallTable.bin");
        return 1;
    }

    if (load_le_u32_file("data/superClassTable.bin", &superClassTable) != 0) {
        perror("Failed loading data/superClassTable.bin");
        return 1;
    }

    if (load_le_u32_file("data/classToInterfaceTable.bin", &classToInterfaceTable) != 0) {
        perror("Failed loading data/classToInterfaceTable.bin");
        return 1;
    }

    return 0;
}

#include "zauber/Any.h"

void* __gcNew(size_t size, uint32_t classIndex) {
    zauber_Any* instance = (zauber_Any*) calloc(1, size);
    instance->__class = classIndex;
    return instance;
}

#ifdef HAS_STRINGS

#include "zauber/Array_zauberByte.h"
#include "zauber/String.h"
#include <string.h> // for strlen

void* __createString(const char* content, void* newStr0) {
    zauber_String* newStr = newStr0;
    if (!newStr->content) { // not yet initialized
        newStr->__class = 1;
        zauber_Array_zauberByte* newBuffer = __gcNew(sizeof(zauber_Array_zauberByte), 2);
        newStr->content = newBuffer;
        newBuffer->content = content;
        newBuffer->size = strlen(content);
        // todo we need to copy the data, or set a flag that we don't free it
    }
    return newStr;
}

#endif
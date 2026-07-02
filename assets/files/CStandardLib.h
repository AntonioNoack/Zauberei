#pragma once

// for calloc
#include <stdlib.h>
// for booleans
#include <stdbool.h>
// for integers with precise size
#include <stdint.h>

#include "CStandardFileIO.h"

void* __gcNew(size_t size, uint32_t classIndex);

#ifdef HAS_STRINGS
void* __createString(const char* content, void* string);
#endif

int32_t zauber_inheritance_readFromClassTable_2clr50n(int32_t index);
int32_t zauber_inheritance_readFromInterfaceTable_2clr50n(int32_t index);
int32_t zauber_inheritance_readFromSuperClassTable_2clr50n(int32_t index);
int32_t zauber_inheritance_readFromClassToInterfaceTable_2clr50n(int32_t index);

int32_t stdlibMain();

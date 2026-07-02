#pragma once

// for calloc
#include <stdlib.h>
// for integers with precise size
#include <stdint.h>

// for std::forward and custom-new
#include <utility>
#include <new>

// todo instead of using calloc,
//  allocate larger buffers,
//  fill in the values there,
//  and for sweeping GC, we find one with enough space,
//  and create the instance inside
template<typename T, typename... Args>
T* __gcNew(Args&&... args)
{
    void* mem = calloc(1, sizeof(T));
    return new (mem) T(std::forward<Args>(args)...);
}

#include "./zauber/String.hpp"

zauber::String* __createString(const char* content, zauber::String* string);

int32_t stdlibMain();
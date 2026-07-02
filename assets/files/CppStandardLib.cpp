
// for strlen
#include <cstring>

#include "CppStandardLib.hpp"
#include "zauber/Array_zauberByte.hpp"

int32_t stdlibMain() {
    return 0;
}

zauber::String* __createString(const char* content, zauber::String* string) {
    if (!string->content) { // initialize
        zauber::Array_zauberByte* newBytes = __gcNew<zauber::Array_zauberByte>(0);
        newBytes->content = (int8_t*) content;
        newBytes->size = strlen(content);
        string->content = newBytes;
    }
    return string;
}
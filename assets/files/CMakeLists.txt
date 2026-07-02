cmake_minimum_required(VERSION 3.16)

project(Zauber LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
add_compile_definitions(DEFINITIONS)

add_executable(Zauber
src/CppStandardLib.cpp
FILES_LIST
)
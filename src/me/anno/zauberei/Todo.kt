package me.anno.zauberei

// todo Kotlin-syntax like language
//  - compiles to C/C++ and to GLSL
//  - UTF-8 by default
//  - annotation to run things on the GPU @ComputeShader
//  - nice debugging on the GPU
//  - Rust-style enums
//  - use stack-allocated structs where possible
//  - support data-types as copy-by-value objects
//  - arrays of data-types are structs of arrays
//  - limited generic types???
//  - custom integer types, where converting from one to the other requires explicit casts
//  - any type conversion must be explicit
//  - VisualStudioCode language server
//  - first write this compiler in Kotlin, later fully compile it to our language
//  - mostly Kotlin compatible: we want to continue Intellij Idea, probably
//  - more allocator control like Zig: arena allocators
//  - no exceptions, instead Option/Error/Enum-return types like in Rust
//  - mark functions as pure
//  - by default GCed???
//  - async/blocking IO -> automatically paint methods as sync/async, and enable async & await and such without much overhead...
//  - Intellij Plugin for syntax highlighting??? Is VSCode easier???
//  - val/var -> I prefer the C/Java way
//  - modules like Rust, easily use code from others
//  - easy C interop!!! (we compile to C++, so should be easy)
//  - soft and hard generics: when generics are used, the user shall decide to create a specialized implementation,
//     and within generics, the type shall always be available (if so desired)

// todo custom fixed point types, vector types, matrix types, fp16, fp80, ...

// todo focus on extension functions like Rust, so we can have multiple inheritance

// todo allow binding any symbols to any functions
//  e.g. @Symbol("^=") fun xorEquals()

// todo steps:
//  - text -> tokens
//  - tokens -> AST
//  - AST -> intermediate language
//  - intermediate language -> C++
//  - intermediate language -> GLSL

// todo compile-time executions, calculations, and tests

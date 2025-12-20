package me.anno.zauberei

// todo out main problem is that Kotlin is beautiful,
//  but when we use it beautifully, it's hogged down by GC,
//  and that it inherited the bad generics/type-system from Java,
//  and that we cannot stack-allocate anything but numbers
//  and ideally, we'd like comp-time execution + comp-time(-only?) reflection

// todo use struct only, and anything else needs to be marked as such
// todo someone needs to be the owner...
// todo structs cannot be modified, only cloned?? -> eliminates non-obvious side-effects, but we need life-times
// todo Ref<Type> <- non-owning reference; struct must live shorter than owner; owner must be struct or struct-property,
// todo GC<Type> <- gc-managed reference; can live as long as you want; should not be stacked much
// todo RefCtr<Type> <- ref-counted reference
// todo no stdlib auto-imports, because they are annoying; imports are aliases
// todo Array<X> is array of structs?, MultiArray<X>=ShallowMultiArray<X> is struct of arrays?, DeepMultiArray<X> a deep version of that?
// todo define aliases anywhere... ->
//  and by using that, we can enforce declaring types anywhere
// todo a type is just a way to get access to member fields and methods -> inline types, and generic functions just define their own types,
//  casting them is done in-place
// todo -> like in Zig, types become comptime only? we could easily support runtime types where needed...

/**
 * <> is optional, deducible;
 * () is mandatory... -> default values??...
 * class MyClass<comptime T>(T x, T y, T z) {
 *
 *  init {
 *      require(...)
 *  }
 *
 *  constructor() : this(0,0,0)
 *
 *  MyClass<u16> produce() {
 *      T x = 0
 *      T y = 16
 *
 *      return MyClass(x,y,z = x+y)
 *      todo mandatory parameter names when it doesn't match the variable???
 *      todo and we could make function signatures actually include the parameter name :3
 *      todo interfaces get assigned automatically; when you want type-enhancements/compiler help, add it explicitly
 *  }
 * }
 *
 *
 * */

// todo getting started with compiling a compiler is just insane, instead, compile our sample code
// todo we can relatively easily enforce that every function and variable needs to specify its type
// todo we could also disable fun-interfaces for now -> makes type resolution much easier
// todo and I also dislike += being dependent on whether the variable is mutable

// todo recursive functions should get markers on which variables are needed for recursion...
//  this would allow them to switch to a heap-allocated stack, and therefore make it predictable and safe, and not store too much data


// todo two main variable types: stack | list | tree (graph?) ?
//  would solve memory management without GC just like Rust
//  the compiler needs to keep track of what type a variable is...

// todo for our sample of KMapGen2, we kind of need a multi-list...
//  solve different types of memory management by making every class an interface? :D (absurd, but possible)

// todo limited numbers, e.g. FiniteFloat, Int[0,100], Int[even], exiting the type throws...
//  no throws... always handle the case immediately there...?
//  typealias FiniteFloat = Float[it.isFinite()],
//  typealias EvenInt = Int[it.and(1) == 0]
//  typealias Percent = Int[it in 0 .. 100]
//  typealias PercentF32 = Float[it in 0f .. 100f]


// todo Kotlin-syntax like language
//  - compiles to C/C++ and to GLSL
//  - UTF-8 by default -> modern Java already stores byte[] in String where valid :)
//  - annotation to run things on the GPU @ComputeShader
//  - nice debugging on the GPU
//  - Rust-style enums -> TypeA|TypeB|TypeC + specifier
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

// todo Kotlin has a library/module problem... make them usable, understandable, ...

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

// todo somehow ensure left-to-right execution...

// todo compile-time executions, calculations, and tests

# Zauberei

This is a modern programming language with focus on usability, performance, and safety.

This project is still in its infancy, but I really do want a language without compromises and
constantly thinking about switching from Kotlin to Zig or Rust.

## Goals:
- Kotlin-style
- Native C++ performance
- Rust-style enums (e.g. for errors) AKA union types
- Rust-style macros (or at least their power)
- Struct types aka not storing the object header where not necessary
- Easy struct of arrays
- Optionally strong generics AKA making ArrayList<Float> contain a FloatArray instead of an Array<Float>
- GLSL cross compilation: debug statements on GPU & running the same algorithms on CPU
- Compile-Time execution using an interpreter
- Variable-Index-Based Virtual Machine code (could be converted to stack-based VM code for JVM)
- Fixed Point arithmetic without OOP/GC overhead
- Stack-located variables

I'd like to compile Rem's Engine (200k LOC + stdlib) to C++ in less than 30 seconds.
Ideally much faster.

## Milestones:

- [x] Tokenizing Kotlin
- [ ] Parsing Kotlin to an AST
- [ ] ...

### Progress Estimation

Total Progress: 0.5 %

```yaml
- Kotlin-Style:
  - Tokenizer: 70% of 1%
  - AST: 40% of 3%
  - Typealias: 0% of 0.2%
  - Type/Dependency-Resolution: 0% of 7%
- Rust-style Enums: 0% of 2%
- Rust-style Macros: 0% of 3%
- Compile to C++: 0% of 3%
- Choose Allocator for Instantiation: 0% of 3%
- Arena Allocator: 0% of 2%
- Store struct members on stack: 0% of 3%
- Automatic Struct Of Arrays: 0% of 2%
- Compile to JVM: 0% of 3%
- Compile to WASM: 0% of 3%
- Debug-Compile to x86 directly: 0% of 4%
- JVM bindings for FileIO, OpenGL/GLFW, println, ...: 0% of 3%
- WASM bindings for async IO, WebGL, println, ...: 0% of 3%
- C++ bindings for FileIO, OpenGL, native libraries, ...: 0% of 3%
- Automatically colored asynchronous Methods: 0% of 3%
- Fixed-Point numbers: 0% of 3%
- Lambdas: 0% of 3%
- Garbage Collector: 0% of 2%
- Multithreading and Parallel GC: 0% of 5%
- Completely Immutable Objects: 0% of 3%
- CompileTime Interpreter: 0% of 5%
- VisualStudioCode Extension for syntax checking: 0% of 3%
- VisualStudioCode Extension for semantic checking: 0% of 5%
- Intellij Idea Extension for syntax checking: 0% of 3%
- Intellij Idea Extension for semantic checking: 0% of 5%
...
# yes, these probably don't add up to 100%
```
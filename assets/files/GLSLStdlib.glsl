#version 430

layout (local_size_x = 1, local_size_y = 1, local_size_z = 1) in;
layout (std430, binding = 0) buffer MemoryBuffer { uint _memory[]; };

uint _gcNew(uint sizeInElements, uint classIndex) {
    uint ref = atomicAdd(_memory[60], max(sizeInElements, 1u));
    _memory[ref] = classIndex;
    return ref;
}

void zauber_flushConsole_0() {} // nothing to do
int zauber_inheritance_readFromClassCallTable_2clr50n(int index) { return 0; } // not yet implemented
int zauber_inheritance_readFromClassToInterfaceTable_2clr50n(int index) { return 0; } // not yet implemented
int zauber_inheritance_readFromSuperClassTable_2clr50n(int index) { return 0; } // not yet implemented
int zauber_inheritance_readFromInterfaceCallTable_2clr50n(int index) { return 0; } // not yet implemented
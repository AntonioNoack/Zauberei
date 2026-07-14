
// create OpenGL context with hidden window
// load provided memory into buffer (if GC is enabled)
// bind that buffer
// load and compile the shader (compute.glsl)
// execute the shader... on 1x1x1

#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <stddef.h>
// for memcpy:
#include <string.h>

#include <GL/glew.h>
#include <GLFW/glfw3.h>

#include "CStandardFileIO.h"

static char *read_text_file(const char *path, size_t *out_len) {
    FILE *fp = fopen(path, "rb");
    if (!fp) return NULL;

    if (fseek(fp, 0, SEEK_END) != 0) { fclose(fp); return NULL; }
    long sz = ftell(fp);
    if (sz < 0) { fclose(fp); return NULL; }
    rewind(fp);

    char *buf = (char *)malloc((size_t)sz + 1);
    if (!buf) { fclose(fp); return NULL; }

    size_t n = fread(buf, 1, (size_t)sz, fp);
    fclose(fp);

    buf[n] = '\0';
    if (out_len) *out_len = n;
    return buf;
}

static void die(const char *msg) {
    fprintf(stderr, "error: %s\n", msg);
    exit(1);
}

static GLuint compile_compute_shader_from_file(const char *shader_path) {
    size_t src_len = 0;
    char *src = read_text_file(shader_path, &src_len);
    if (!src) die("failed to read compute shader source (compute.glsl)");

    GLuint sh = glCreateShader(GL_COMPUTE_SHADER);
    if (!sh) die("glCreateShader failed");

    const GLchar *p = (const GLchar *)src;
    glShaderSource(sh, 1, &p, NULL);
    glCompileShader(sh);

    free(src);

    GLint ok = 0;
    glGetShaderiv(sh, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        GLint log_len = 0;
        glGetShaderiv(sh, GL_INFO_LOG_LENGTH, &log_len);
        char *log = (char *)malloc((size_t)log_len + 1);
        if (log) {
            glGetShaderInfoLog(sh, log_len, NULL, log);
            fprintf(stderr, "compute shader compile log:\n%s\n", log);
            free(log);
        }
        die("compute shader compilation failed");
    }

    return sh;
}

static GLuint link_compute_program(GLuint compute_sh) {
    GLuint prog = glCreateProgram();
    if (!prog) die("glCreateProgram failed");

    glAttachShader(prog, compute_sh);
    glLinkProgram(prog);
    glDetachShader(prog, compute_sh);
    glDeleteShader(compute_sh);

    GLint ok = 0;
    glGetProgramiv(prog, GL_LINK_STATUS, &ok);
    if (!ok) {
        GLint log_len = 0;
        glGetProgramiv(prog, GL_INFO_LOG_LENGTH, &log_len);
        char *log = (char *)malloc((size_t)log_len + 1);
        if (log) {
            glGetProgramInfoLog(prog, log_len, NULL, log);
            fprintf(stderr, "program link log:\n%s\n", log);
            free(log);
        }
        die("program linking failed");
    }

    return prog;
}

void printAllInStringBuilder(uint32_t* memory, size_t memorySizeBytes) {
    // todo validate all bounds
    uint32_t stringBuilder = memory[14];
    // printf("StringBuilder: %d\n", stringBuilder);
    if (!stringBuilder) {
        printf("Missing stringBuilder :(\n");
        return;
    }

    uint32_t buffer = memory[stringBuilder + 1]; // 0 is classIndex, 1 is buffer
    // printf("buffer: %d\n", buffer);
    if (!buffer) die("Buffer in stringBuilder cannot be null");

    uint32_t bufferSize = memory[buffer + 1]; // 0 is classIndex, 1 is size, 2+ is content
    // printf("buffer.class: %d\n", memory[buffer]);
    // printf("buffer.size: %d\n", memory[buffer + 1]);
    char* asCString = (char*) calloc(bufferSize + 1, 1);
    char* bufferContent = (char*) &memory[buffer + 2];
    memcpy(asCString, bufferContent, bufferSize);
    printf("%s", asCString);
}

int main() {

    // ---- Create hidden OpenGL context (GLFW) ----
    if (!glfwInit()) die("glfwInit failed");

    glfwWindowHint(GLFW_CLIENT_API, GLFW_OPENGL_API);
    glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
    glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
    glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);

    GLFWwindow *win = glfwCreateWindow(1, 1, "hidden", NULL, NULL);
    if (!win) die("glfwCreateWindow failed");

    glfwMakeContextCurrent(win);

    // ---- Load GL functions (GLEW) ----
    glewExperimental = GL_TRUE;
    GLenum glew_err = glewInit();
    if (glew_err != GLEW_OK) die("glewInit failed");

    if (!GLEW_VERSION_4_3) die("OpenGL 4.3 not available");

    // ---- Load provided u32 memory into buffer ----
    u32_file f = {0};
    if (load_le_u32_file("data/memory.bin", &f) != 0) die("load_le_u32_file for data/memory.bin failed");

    if (f.len == 0 || !f.data) {
        free_u32_file(&f);
        die("memory file contains no u32 data");
    }

    // ---- Create SSBO and bind it ----
    // Convention:
    //   - shader uses: layout(std430, binding = 0) buffer Buffer { uint memory[]; };
    //   - we bind the SSBO to binding point 0.
    GLsizeiptr memorySize0 = (GLsizeiptr)(f.len * sizeof(uint32_t));
    GLsizeiptr memorySize1 = (memorySize0 + 1024) * 16;

    // printAllInStringBuilder(f.data, memorySize0);

    f.data = realloc(f.data, memorySize1);
    memset(f.data + (memorySize0 / sizeof(uint32_t)), 0, memorySize1 - memorySize0);

    GLuint ssbo = 0;
    glGenBuffers(1, &ssbo);
    glBindBuffer(GL_SHADER_STORAGE_BUFFER, ssbo);
    glBufferData(GL_SHADER_STORAGE_BUFFER, memorySize1, f.data, GL_STATIC_DRAW);

    glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);

    // ---- Compile + link compute shader ----
    GLuint cs = compile_compute_shader_from_file("src/ComputeShader.glsl");
    GLuint prog = link_compute_program(cs);

    // ---- Bind SSBO to binding point 0 ----
    glUseProgram(prog);
    glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, ssbo);

    // ---- Execute compute shader on 1x1x1 ----
    glDispatchCompute(1, 1, 1);
    glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);

    // check for GL errors
    for (int i = 0; i < 16; i++) {
        GLenum err = glGetError();
        if (err == GL_NO_ERROR) break;
        fprintf(stderr, "gl error: 0x%X\n", err);
    }

    // Map for read
    glBindBuffer(GL_SHADER_STORAGE_BUFFER, ssbo);
    void *mapped = glMapBufferRange(GL_SHADER_STORAGE_BUFFER, 0, memorySize1, GL_MAP_READ_BIT);
    if (!mapped) die("glMapBufferRange failed");
    memcpy(f.data, mapped, memorySize1);
    glUnmapBuffer(GL_SHADER_STORAGE_BUFFER);
    glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);

    // print all there is to print
    printAllInStringBuilder(f.data, memorySize1);

    if (1) {
        // Save to memory.bin
        FILE *fp = fopen("memory.final.bin", "wb");
        if (!fp) die("failed to open memory.bin for writing");
        memorySize1 = f.data[15] * sizeof(uint32_t); // gc-pointer for next instance
        if (fwrite(f.data, 1, memorySize1, fp) != memorySize1) die("failed writing memory.bin");
        fclose(fp);
    }

    free_u32_file(&f);

    // ---- Cleanup ----
    glUseProgram(0);
    glDeleteProgram(prog);
    glDeleteBuffers(1, &ssbo);

    glfwDestroyWindow(win);
    glfwTerminate();
    return 0;
}
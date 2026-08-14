#include "anime4k.h"
#include <algorithm>
#include <sstream>

const char *VERTEX_SHADER_SOURCE = R"glsl(
    #version 300 es
    layout(location = 0) in vec2 aPos;
    layout(location = 1) in vec2 aTexCoord;
    out vec2 vTexCoord;
    void main() {
        gl_Position = vec4(aPos, 0.0, 1.0);
        vTexCoord = aTexCoord;
    }
)glsl";

Anime4K::Anime4K()
    : display(EGL_NO_DISPLAY), context(EGL_NO_CONTEXT), surface(EGL_NO_SURFACE),
      quad_vbo(0), quad_vao(0), initialized(false) {}

Anime4K::~Anime4K() { term_egl(); }

bool Anime4K::init_egl() {
  if (initialized)
    return true;

  display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
  eglInitialize(display, nullptr, nullptr);

  EGLint configAttribs[] = {EGL_RENDERABLE_TYPE,
                            EGL_OPENGL_ES3_BIT,
                            EGL_SURFACE_TYPE,
                            EGL_PBUFFER_BIT,
                            EGL_RED_SIZE,
                            8,
                            EGL_GREEN_SIZE,
                            8,
                            EGL_BLUE_SIZE,
                            8,
                            EGL_ALPHA_SIZE,
                            8,
                            EGL_NONE};
  EGLConfig config;
  EGLint numConfigs;
  eglChooseConfig(display, configAttribs, &config, 1, &numConfigs);

  EGLint pbufferAttribs[] = {EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE};
  surface = eglCreatePbufferSurface(display, config, pbufferAttribs);

  EGLint contextAttribs[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
  context = eglCreateContext(display, config, EGL_NO_CONTEXT, contextAttribs);

  if (!eglMakeCurrent(display, surface, surface, context)) {
    ANIME4K_LOGE("Failed to make EGL context current");
    return false;
  }

  setup_quad();
  initialized = true;

  // MihonSY fix: the context is now initialised but we do NOT want to keep it
  // bound to this (init) thread. Coil decodes on a thread pool and enhancement
  // may run on any thread; an EGL context can be current on only one thread at
  // a time, and a context still bound to the init thread fails with
  // EGL_BAD_ACCESS when another thread tries eglMakeCurrent. Detach it here so
  // process() can rebind it on whichever thread happens to run.
  eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
  return true;
}

void Anime4K::term_egl() {
  if (!initialized)
    return;
  for (auto &pass : passes)
    glDeleteProgram(pass.program);
  for (auto &it : textures)
    glDeleteTextures(1, &it.second);
  if (quad_vbo)
    glDeleteBuffers(1, &quad_vbo);
  if (quad_vao)
    glDeleteVertexArrays(1, &quad_vao);
  eglDestroyContext(display, context);
  eglDestroySurface(display, surface);
  eglTerminate(display);
  initialized = false;
}

void Anime4K::setup_quad() {
  float vertices[] = {
      -1.0f, 1.0f, 0.0f, 1.0f, -1.0f, -1.0f, 0.0f, 0.0f,
      1.0f,  1.0f, 1.0f, 1.0f, 1.0f,  -1.0f, 1.0f, 0.0f,
  };
  glGenVertexArrays(1, &quad_vao);
  glGenBuffers(1, &quad_vbo);
  glBindVertexArray(quad_vao);
  glBindBuffer(GL_ARRAY_BUFFER, quad_vbo);
  glBufferData(GL_ARRAY_BUFFER, sizeof(vertices), vertices, GL_STATIC_DRAW);
  glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float), (void *)0);
  glEnableVertexAttribArray(0);
  glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float),
                        (void *)(2 * sizeof(float)));
  glEnableVertexAttribArray(1);
}

GLuint Anime4K::compile_program(const std::string &name,
                                const std::string &source) {
  auto compile = [](GLenum type, const char *src) {
    GLuint shader = glCreateShader(type);
    glShaderSource(shader, 1, &src, nullptr);
    glCompileShader(shader);
    GLint status;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &status);
    if (!status) {
      char log[1024] = {0};
      GLint len = 0;
      glGetShaderInfoLog(shader, sizeof(log), &len, log);
      ANIME4K_LOGE("Shader compile error (type %d, len %d): %s", (int)type, (int)len, log);
    }
    return shader;
  };

  GLuint vs = compile(GL_VERTEX_SHADER, VERTEX_SHADER_SOURCE);
  GLuint fs = compile(GL_FRAGMENT_SHADER, source.c_str());
  if (vs == 0 || fs == 0) {
    if (vs) glDeleteShader(vs);
    if (fs) glDeleteShader(fs);
    return 0;
  }
  GLuint program = glCreateProgram();
  glAttachShader(program, vs);
  glAttachShader(program, fs);
  glLinkProgram(program);
  GLint linkStatus = 0;
  glGetProgramiv(program, GL_LINK_STATUS, &linkStatus);
  if (!linkStatus) {
    char log[1024] = {0};
    GLint len = 0;
    glGetProgramInfoLog(program, sizeof(log), &len, log);
    ANIME4K_LOGE("Program link error: %s", log);
    glDeleteShader(vs);
    glDeleteShader(fs);
    glDeleteProgram(program);
    return 0;
  }
  glDeleteShader(vs);
  glDeleteShader(fs);
  return program;
}

int Anime4K::load(const std::vector<std::string> &shaders,
                  const std::vector<std::string> &shader_names) {
  if (!init_egl())
    return -1;

  // MihonSY fix: init_egl() detaches the context after setup so other threads
  // can rebind it. But compiling shaders below needs a CURRENT context — with
  // none bound, glCreateShader/glCompileShader fail silently (len-0 info log)
  // and every pass gets skipped. Rebinding here lets all shaders compile.
  eglReleaseThread();
  if (!eglMakeCurrent(display, surface, surface, context)) {
    ANIME4K_LOGE("Failed to make EGL context current for shader compile");
    return -1;
  }

  // MihonSY: compile one accumulated pass (all its directives + fragment body)
  // into a program and push it. Returns false if compilation failed.
  auto flush_pass = [&](Pass &pass, const std::string &name,
                        std::vector<std::string> &bindings,
                        const std::string &fragment_body) -> bool {
    std::string full_fs = "#version 300 es\nprecision highp float;\nin vec2 "
                          "vTexCoord;\nout vec4 fragColor;\n";
    for (const auto &b : bindings) {
      full_fs += "uniform sampler2D " + b + "_tex;\n";
      full_fs += "uniform vec2 " + b + "_size;\n";
      full_fs += "#define " + b + "_tex(pos) texture(" + b + "_tex, pos)\n";
      full_fs += "#define " + b + "_texOff(off) texture(" + b +
                 "_tex, vTexCoord + off / " + b + "_size)\n";
      full_fs += "#define " + b + "_pos vTexCoord\n";
      // MihonSY fix: mpv defines NAME_pt as the pixel-texel step (1 / size);
      // Depth-to-Space and some conv layers use NAME_pt (e.g.
      // 'conv2d_last_tf_pt') to compute sampling offsets.
      full_fs += "#define " + b + "_pt (vec2(1.0) / " + b + "_size)\n";
    }
    // MihonSY fix: mpv-style shaders hardcode 'go_0(x,y) (MAIN_texOff(...))' and
    // 'MAIN' refers to the CURRENT pass input (= the first bind target), not
    // always the original image. Later conv layers bind e.g. conv2d_tf only, so
    // MAIN_texOff is undefined there and the shader fails to compile. Alias
    // MAIN to the first bind target so go_0/MAIN_* always resolve.
    if (!bindings.empty()) {
      const std::string &first = bindings.front();
      if (first != "MAIN") {
        full_fs += "#define MAIN_tex(pos) " + first + "_tex(pos)\n";
        full_fs += "#define MAIN_texOff(off) " + first + "_texOff(off)\n";
        full_fs += "#define MAIN_pos " + first + "_pos\n";
        full_fs += "#define MAIN_pt " + first + "_pt\n";
      }
    }
    full_fs += fragment_body;
    full_fs += "\nvoid main() { fragColor = hook(); }\n";

    pass.program = compile_program(name, full_fs);
    if (pass.program == 0) {
      ANIME4K_LOGE("Skipping pass (compile failed): %s", pass.desc.c_str());
      return false;
    }
    pass.bind_targets = bindings;
    passes.push_back(pass);
    ANIME4K_LOGD("Loaded pass: %s -> %s (Scale %.1fx)", pass.desc.c_str(),
                 pass.save_target.c_str(), pass.scale_x);
    return true;
  };

  for (size_t i = 0; i < shaders.size(); ++i) {
    const std::string &src = shaders[i];
    std::stringstream ss(src);
    std::string line;

    // MihonSY: each file contains MULTIPLE '//!DESC' sections (Clamp has 3,
    // Restore 8, Upscale 18). Each section is an independent pass with its own
    // hook()/get_luma()/uniforms — merging them into one shader caused
    // 'redefinition' compile errors. Start a fresh pass on every DESC.
    Pass pass;
    pass.scale_x = 1.0f;
    pass.scale_y = 1.0f;
    std::string fragment_body;
    std::vector<std::string> bindings;
    std::string hook_target;
    bool in_pass = false;

    while (std::getline(ss, line)) {
      if (line.find("//!DESC") == 0) {
        // New pass section: flush the previous one (if any), then start fresh.
        if (in_pass) {
          flush_pass(pass, shader_names[i], bindings, fragment_body);
          pass = Pass();
          pass.scale_x = 1.0f;
          pass.scale_y = 1.0f;
          fragment_body.clear();
          bindings.clear();
          hook_target.clear();
        }
        in_pass = true;
        pass.desc = line.substr(8);
        continue;
      }
      if (!in_pass) continue; // license header etc. before the first DESC

      if (line.find("//!HOOK") == 0) {
        hook_target = line.substr(8); // e.g. "MAIN"
        continue;
      }
      if (line.find("//!BIND") == 0) {
        const std::string b = line.substr(8);
        bindings.push_back(b);
        // MihonSY: mpv semantics — '//!BIND HOOKED' means "the current hook input
        // (from //!HOOK, e.g. MAIN)". Only this exact alias resolves to the hook
        // target. Any other BIND name (conv2d_tf, STATSMAX, ...) refers to a
        // texture created by an earlier pass's SAVE and must stay as-is.
        // PREKERNEL is the pre-processing input stage: for our pipeline it is the
        // original MAIN texture (De-Ring-Clamp hooks PREKERNEL).
        if (b == "HOOKED" && !hook_target.empty()) {
          pass.bind_alias[b] = (hook_target == "PREKERNEL") ? "MAIN" : hook_target;
        }
        continue;
      }
      if (line.find("//!SAVE") == 0) {
        pass.save_target = line.substr(8);
        continue;
      }
      // MihonSY fix: Anime4K v4 shaders express upscaling as a conditional
      // '//!WHEN OUTPUT.w MAIN.w / 1.2 > ... *'. Only the FIRST pass of an
      // upscaler chain scales 2x (its WIDTH references MAIN); later layers keep
      // the enlarged size ('//!WIDTH conv2d_tf.w'). Marking every WHEN-* pass 2x
      // would blow the output to 2^18. So: 2x only when the pass binds MAIN and
      // has a '*' scale marker anywhere (WIDTH/HEIGHT/WHEN).
      if (line.find("//!WIDTH") == 0 && line.find("*") != std::string::npos) {
        pass.scale_x = 2.0f;
        continue;
      }
      if (line.find("//!HEIGHT") == 0 && line.find("*") != std::string::npos) {
        pass.scale_y = 2.0f;
        continue;
      }
      if (line.find("//!WHEN") == 0 && line.find("*") != std::string::npos) {
        pass.conditional_upsample = true;
        continue;
      }
      if (line.find("//!") != 0)
        fragment_body += line + "\n";
    }
    // Flush the last section of this file.
    if (in_pass) {
      flush_pass(pass, shader_names[i], bindings, fragment_body);
    }
  }

  // Detach again so the context is free for whichever thread runs process().
  eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
  return 0;
}

int Anime4K::process(int width, int height, unsigned char *pixels, int &out_w,
                     int &out_h, unsigned char *out_pixels) {
  if (!init_egl())
    return -1;

  // MihonSY fix: an EGL context can be current on only one thread at a time.
  // Coil decodes on a thread pool, so consecutive calls may come from different
  // threads. init_egl() detaches the context after setup, so it is free to be
  // bound by whichever thread runs here. Release any stale binding on THIS
  // thread, then rebind. The Kotlin-side Semaphore(1) guarantees serial entry.
  eglReleaseThread();
  if (!eglMakeCurrent(display, surface, surface, context)) {
    ANIME4K_LOGE("Failed to make EGL context current in process()");
    return -1;
  }

  auto get_tex = [&](const std::string &name, int w, int h) {
    if (textures.count(name)) {
      if (tex_sizes[name].first == w && tex_sizes[name].second == h)
        return textures[name];
      glDeleteTextures(1, &textures[name]);
    }
    GLuint tex;
    glGenTextures(1, &tex);
    glBindTexture(GL_TEXTURE_2D, tex);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE,
                 nullptr);
    // MihonSY fix: conv layers sample at exact integer texel offsets via
    // _texOff(vec2(x,y)); GL_LINEAR interpolates between texels → color
    // smearing → garbled (花屏) output on the upscale chain. NEAREST is what
    // Anime4K's shaders expect.
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    textures[name] = tex;
    tex_sizes[name] = {w, h};
    return tex;
  };

  // Upload initial image
  GLuint main_tex = get_tex("MAIN", width, height);
  glBindTexture(GL_TEXTURE_2D, main_tex);
  glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, GL_RGBA,
                  GL_UNSIGNED_BYTE, pixels);

  GLuint fbo;
  glGenFramebuffers(1, &fbo);
  glBindFramebuffer(GL_FRAMEBUFFER, fbo);

  int curr_w = width, curr_h = height;

  for (const auto &pass : passes) {
    // MihonSY: ONLY the final Depth-to-Space pass of an upscaler actually outputs
    // 2x (it re-arranges pixels into a larger image). Every conv layer in between
    // runs at 1x ('//!WIDTH conv2d_tf.w' = keep size); their '//!WHEN ... *' only
    // marks them as part of the conditional upscale chain. Scaling any conv layer
    // 2x made all 19 layers run at 2x resolution → VRAM explosion → app crash.
    float sx = pass.scale_x;
    float sy = pass.scale_y;
    if (pass.conditional_upsample && pass.desc.find("Depth-to-Space") != std::string::npos) {
      sx = 2.0f;
      sy = 2.0f;
    }
    int next_w = curr_w * sx;
    int next_h = curr_h * sy;
    // MihonSY fix: a pass that both BINDs and SAVEs the same texture (e.g.
    // Depth-to-Space does '//!BIND MAIN' + '//!SAVE MAIN') would render INTO the
    // texture it samples — a GL feedback loop whose result is undefined (green
    // garbage on the device). Detect self-reference and render to __OUT__ so the
    // sampled texture stays intact; the final readback uses __OUT__ anyway.
    bool self_ref = false;
    if (!pass.save_target.empty()) {
      for (const auto &b : pass.bind_targets) {
        auto it = pass.bind_alias.find(b);
        const std::string real = (it != pass.bind_alias.end()) ? it->second : b;
        if (real == pass.save_target) { self_ref = true; break; }
      }
    }
    std::string out_name = pass.save_target;
    if (self_ref || out_name.empty()) out_name = "__OUT__";
    GLuint out_tex = get_tex(out_name, next_w, next_h);
    ANIME4K_LOGD("Render %s: in %dx%d -> out %dx%d (save=%s%s)", pass.desc.c_str(),
                 curr_w, curr_h, next_w, next_h, out_name.c_str(),
                 self_ref ? ", self-ref->__OUT__" : "");

    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
                           out_tex, 0);
    glViewport(0, 0, next_w, next_h);
    glUseProgram(pass.program);

    bool valid = true;
    for (size_t j = 0; j < pass.bind_targets.size(); ++j) {
      const std::string &bname = pass.bind_targets[j];
      auto it = pass.bind_alias.find(bname);
      const std::string real = (it != pass.bind_alias.end()) ? it->second : bname;
      auto texIt = textures.find(real);
      if (texIt == textures.end()) {
        // MihonSY: never bind a missing texture — that renders black. Skip the
        // pass instead (original image stays).
        ANIME4K_LOGE("Pass '%s' binds missing texture '%s', skipping", pass.desc.c_str(), real.c_str());
        valid = false;
        break;
      }
      glActiveTexture(GL_TEXTURE0 + j);
      glBindTexture(GL_TEXTURE_2D, texIt->second);
      glUniform1i(glGetUniformLocation(pass.program, (bname + "_tex").c_str()),
                  (int)j);
      glUniform2f(glGetUniformLocation(pass.program, (bname + "_size").c_str()),
                  (float)tex_sizes[real].first,
                  (float)tex_sizes[real].second);
    }
    if (!valid) break;

    glBindVertexArray(quad_vao);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

    // MihonSY: a self-referencing pass (BIND+SAVE same texture) renders to __OUT__
    // to avoid the GL feedback loop; copy the result back into the real
    // save_target so subsequent passes see the updated texture (mpv semantics:
    // read old value inside the pass, write new value for the next pass).
    if (self_ref && !pass.save_target.empty() && pass.save_target != "__OUT__") {
      GLuint real_tex = get_tex(pass.save_target, next_w, next_h);
      glBindTexture(GL_TEXTURE_2D, real_tex);
      glCopyTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 0, 0, next_w, next_h);
    }

    curr_w = next_w;
    curr_h = next_h;
  }

  out_w = curr_w;
  out_h = curr_h;
  // Read back final result
  glReadPixels(0, 0, curr_w, curr_h, GL_RGBA, GL_UNSIGNED_BYTE, out_pixels);

  // MihonSY fix: Restore/Depth-to-Space shaders do 'return result + MAIN_tex(...)',
  // so output alpha = result.a + MAIN.a — the mat4 result alpha is an arbitrary
  // (often <1) value, leaving the bitmap semi-transparent. Display blends it with
  // the background → colour cast (red on Fast/High, green on Ultra). Manga images
  // are fully opaque; force alpha=255 on the CPU side without touching the shader
  // feature channels.
  if (curr_w > 0 && curr_h > 0) {
    const size_t n = static_cast<size_t>(curr_w) * curr_h;
    unsigned char *a = out_pixels + 3;
    for (size_t i = 0; i < n; ++i, a += 4) *a = 255;
  }

  // MihonSY fix: Anime4K conv weights are trained on colour data, so its R/G/B
  // gains are slightly asymmetric — pure greyscale manga (R=G=B input) comes out
  // with a small colour cast (red on Fast/High, green on Ultra; measured up to
  // ±8 in mid/dark areas). Detect near-greyscale pixels (low chroma) and snap
  // them to their luminance — restores neutral greys for b/w manga while colour
  // regions (higher chroma) keep the full Anime4K enhancement.
  if (curr_w > 0 && curr_h > 0) {
    const size_t n = static_cast<size_t>(curr_w) * curr_h;
    unsigned char *p = out_pixels;
    const int CHROMA_THRESHOLD = 12;
    for (size_t i = 0; i < n; ++i, p += 4) {
      const int r = p[0], g = p[1], b = p[2];
      const int mx = r > g ? (r > b ? r : b) : (g > b ? g : b);
      const int mn = r < g ? (r < b ? r : b) : (g < b ? g : b);
      if (mx - mn < CHROMA_THRESHOLD) {
        const int luma = (r + g + b + 1) / 3;
        p[0] = static_cast<unsigned char>(luma);
        p[1] = static_cast<unsigned char>(luma);
        p[2] = static_cast<unsigned char>(luma);
      }
    }
  }

  // MihonSY debug: sample average RGBA of the output to detect channel
  // corruption (e.g. greenish cast = G channel biased) and alpha transparency.
  if (curr_w * curr_h > 0) {
    const size_t total = static_cast<size_t>(curr_w) * curr_h;
    const size_t step = (total > 20000) ? (total / 20000) : 1;
    unsigned long long sr = 0, sg = 0, sb = 0, sa = 0, cnt = 0;
    for (size_t i = 0; i < total * 4; i += step * 4) {
      sr += out_pixels[i];
      sg += out_pixels[i + 1];
      sb += out_pixels[i + 2];
      sa += out_pixels[i + 3];
      cnt++;
    }
    ANIME4K_LOGD("Output %dx%d avg RGBA: R=%llu G=%llu B=%llu A=%llu (n=%llu)",
                 curr_w, curr_h, cnt ? sr / cnt : 0, cnt ? sg / cnt : 0,
                 cnt ? sb / cnt : 0, cnt ? sa / cnt : 0, cnt);
  }

  glDeleteFramebuffers(1, &fbo);

  // MihonSY fix: detach the context again so the next call (possibly on a
  // different Coil thread) can rebind it without EGL_BAD_ACCESS.
  eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
  return 0;
}

void Anime4K::get_output_size(int width, int height, int &out_w, int &out_h) {
  float curr_w = width, curr_h = height;
  for (const auto &pass : passes) {
    float sx = pass.scale_x;
    float sy = pass.scale_y;
    // Only the final Depth-to-Space pass outputs 2x (mirror of process()).
    if (pass.conditional_upsample && pass.desc.find("Depth-to-Space") != std::string::npos) {
      sx = 2.0f;
      sy = 2.0f;
    }
    curr_w *= sx;
    curr_h *= sy;
  }
  out_w = (int)curr_w;
  out_h = (int)curr_h;
}

int Anime4K::get_max_texture_size() {
  if (!init_egl()) return 0;
  GLint size = 0;
  glGetIntegerv(GL_MAX_TEXTURE_SIZE, &size);
  return (int)size;
}

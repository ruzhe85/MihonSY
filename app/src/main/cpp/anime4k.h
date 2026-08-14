#pragma once
#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <android/log.h>
#include <map>
#include <string>
#include <vector>

#define ANIME4K_LOGD(...)                                                      \
  __android_log_print(ANDROID_LOG_DEBUG, "Anime4K", __VA_ARGS__)
#define ANIME4K_LOGE(...)                                                      \
  __android_log_print(ANDROID_LOG_ERROR, "Anime4K", __VA_ARGS__)

class Anime4K {
public:
  Anime4K();
  ~Anime4K();

  int load(const std::vector<std::string> &shaders,
           const std::vector<std::string> &shader_names);
  int process(int width, int height, unsigned char *pixels, int &out_w,
              int &out_h, unsigned char *out_pixels);
  void get_output_size(int width, int height, int &out_w, int &out_h);
  int get_max_texture_size();

private:
  struct Pass {
    GLuint program;
    std::string save_target;
    std::vector<std::string> bind_targets;
    float scale_x;
    float scale_y;
    std::string desc;
    // MihonSY: true when the shader declares a conditional upsample
    // ('//!WHEN OUTPUT.w MAIN.w / 1.2 > ... *'). The first pass of an upscaler
    // chain scales 2x; later layers keep the enlarged size.
    bool conditional_upsample = false;
    // MihonSY: map a shader-side texture name (e.g. "HOOKED") to the real
    // texture the renderer created (e.g. "MAIN"). Key = bind name used in the
    // shader; value = texture key in textures[]. Populated from //!HOOK.
    std::map<std::string, std::string> bind_alias;
  };

  bool init_egl();
  void term_egl();
  GLuint compile_program(const std::string &name, const std::string &source);
  void setup_quad();

  EGLDisplay display;
  EGLContext context;
  EGLSurface surface;

  std::vector<Pass> passes;
  std::map<std::string, GLuint> textures;
  std::map<std::string, std::pair<int, int>> tex_sizes;

  GLuint quad_vbo;
  GLuint quad_vao;

  bool initialized;
};

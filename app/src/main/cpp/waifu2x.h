// waifu2x implemented with ncnn library

#ifndef WAIFU2X_H
#define WAIFU2X_H

#include <atomic>
#include <mutex>
#include <string>

// ncnn
#include "gpu.h"
#include "layer.h"
#include "net.h"

class Waifu2x {
public:
  Waifu2x(int gpuid, bool tta_mode = false, int num_threads = 0,
          int precision_mode = 0, bool fp16_arithmetic = false);
  ~Waifu2x();

  int load(const std::string &parampath, const std::string &modelpath);

  // Unified process method: runs inference and writes directly to output
  // in: inimage (RGBA planar)
  // out: out_pixels (RGBA packed), out_stride
  // lock: The JNI lock, passed in to allow early release of the GPU.
  int process(const ncnn::Mat &inimage, void *out_pixels, int out_stride,
              bool input_has_alpha, std::unique_lock<std::mutex> &lock,
              std::atomic<int> *progress_ptr = nullptr) const;
  int process_gpu(const ncnn::Mat &packed_input, void *out_pixels,
                  int out_stride, bool input_has_alpha,
                  std::atomic<int> *progress_ptr = nullptr) const;
  bool has_gpu_pipeline() const { return gpu_pipeline_available; }

public:
  // waifu2x parameters
  int noise;
  int scale;
  int tilesize;
  int prepadding;
  std::atomic<int> *progress_ptr = nullptr;
  std::atomic<int> *ui_busy_ptr = nullptr;
  std::atomic<bool> *should_abort_ptr = nullptr;
  int tile_sleep_ms = 0; // Sleep between tiles for cooling (0 = full speed)
  bool is_snapdragon = false;
  bool disable_grayscale_check = false;
  int num_threads = 1;
  int precision_mode = 0; // 0 = fp16, 1 = fp32, 2 = int8, 3 = bf16
  bool fp16_arithmetic = false;

  // Komiho (2026-10-01): 上次收敛出的批次大小（片数），0 = 未知。
  //
  // 跨页保留的理由：批次只跟 (模型, tile 尺寸) 有关，与页无关；不保留的话每一页都要
  // 从 1 重新 ramp 一遍，而 ramp 期的提交次数正是要摊掉的那个开销。
  // 只持有 g_lock 时读写（process_gpu —— 故需 mutable），tile 尺寸/模型变更时清零
  // （见 nativeUpdatePerformanceConfig / load）。
  mutable int batch_target_hint = 0;

  // Komiho (2026-10-01): 实测判为「太贵」的片数（0 = 未知）；批次增长不再越过它。
  //
  // 为什么必须记住：单次提交的代价在这台设备上**超线性** —— 1099x1600/192 tile 实测
  // 1 片 36ms/片、2 片 29ms/片、**3 片 130ms/片**；1445x2048/256 tile 是 1 片 ~40ms/片、
  // **2 片 170ms/片**。（机制未查清，非功耗就是显存带宽，但跨两个 tile 尺寸都稳定复现。）
  // 只收缩不记忆的后果：每一页都会重新探到那个坏尺寸、再付一次 3~4 倍代价。
  mutable int batch_target_ceiling = 0;

private:
  ncnn::VulkanDevice *vkdev;
  ncnn::Net net;
  ncnn::Pipeline *waifu2x_preproc;
  ncnn::Pipeline *waifu2x_postproc;
  ncnn::Pipeline *waifu2x_preproc_tta;
  ncnn::Pipeline *waifu2x_postproc_tta;
  ncnn::Layer *bicubic_2x;
  bool tta_mode;
  bool gpu_pipeline_available = false;

  // Komiho (2026-10-01): 分配器随引擎生命周期持有 —— process_gpu 首次使用时 acquire
  // （此刻必持有 g_lock）、~Waifu2x 里 reclaim。原先每页 acquire/reclaim 一次设备池，
  // 只换来池内锁竞争与队列抖动；这两块显存本来就是「整台设备上唯一在跑推理的引擎」在用。
  // 归还时机安全：此时已无活跃 VkMat —— process_gpu 里的 VkMat 全是函数局部量，早已析构。
  mutable ncnn::VkAllocator *blob_allocator = nullptr;
  mutable ncnn::VkAllocator *staging_allocator = nullptr;
};

#endif // WAIFU2X_H

#pragma once

#include <atomic>
#include <memory>
#include "api/audio/audio_device.h"
#include "livekit/webrtc.h"
#include "rust/cxx.h"

namespace livekit_ffi {

// Dedicated local playout only: no PeerConnection, encoder or capture path.
class OutputTest final : public webrtc::AudioTransport {
 public:
  OutputTest();
  ~OutputTest() override;
  bool start(rust::String device_id, double gain);
  uint64_t samples() const { return samples_.load(); }

  int32_t RecordedDataIsAvailable(const void*, size_t, size_t, size_t,
      uint32_t, uint32_t, int32_t, uint32_t, bool, uint32_t& level) override {
    level = 0;
    return 0;
  }
  int32_t NeedMorePlayData(size_t count, size_t bytes, size_t channels,
      uint32_t rate, void* data, size_t& out, int64_t* elapsed,
      int64_t* ntp) override;
  void PullRenderData(int bits, int rate, size_t channels, size_t count,
      void* data, int64_t* elapsed, int64_t* ntp) override;

 private:
  void render(size_t count, size_t channels, uint32_t rate, int16_t* data);
  std::shared_ptr<RtcRuntime> runtime_;
  webrtc::scoped_refptr<webrtc::AudioDeviceModule> adm_;
  std::atomic<uint64_t> samples_{0};
  double gain_ = 0;
  double seconds_ = 0;
};

std::unique_ptr<OutputTest> create_output_test();
}

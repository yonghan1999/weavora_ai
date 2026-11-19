#include "livekit/output_test.h"
#include <algorithm>
#include <cmath>
#include <cstring>
#include "api/audio/create_audio_device_module.h"
#include "api/environment/environment_factory.h"

namespace livekit_ffi {
OutputTest::OutputTest() : runtime_(RtcRuntime::create()) {}

OutputTest::~OutputTest() {
  runtime_->worker_thread()->BlockingCall([this] {
    if (adm_) {
      adm_->StopPlayout();
      adm_->RegisterAudioCallback(nullptr);
      adm_->Terminate();
      adm_ = nullptr;
    }
  });
}

bool OutputTest::start(rust::String device_id, double gain) {
  if (!std::isfinite(gain) || gain < 0 || gain > 0.15) return false;
  return runtime_->worker_thread()->BlockingCall([&] {
    if (adm_) return false;
    gain_ = gain;
    adm_ = webrtc::CreateAudioDeviceModule(webrtc::CreateEnvironment(),
        webrtc::AudioDeviceModule::kPlatformDefaultAudio);
    if (!adm_ || adm_->Init() != 0) return false;
    bool found = false;
    for (int16_t i = 0; i < adm_->PlayoutDevices(); ++i) {
      char name[webrtc::kAdmMaxDeviceNameSize] = {};
      char guid[webrtc::kAdmMaxGuidSize] = {};
      if (adm_->PlayoutDeviceName(i, name, guid) != 0) continue;
      const std::string identity = guid[0] ? guid : "index:" + std::to_string(i);
      if (identity == std::string(device_id)) {
        found = adm_->SetPlayoutDevice(i) == 0;
        break;
      }
    }
    if (!found || adm_->RegisterAudioCallback(this) != 0 ||
        adm_->InitPlayout() != 0) return false;
    return adm_->StartPlayout() == 0;
  });
}

void OutputTest::render(size_t count, size_t channels, uint32_t rate, int16_t* data) {
  if (!rate) return;
  for (size_t frame = 0; frame < count; ++frame) {
    // 1.5 seconds maximum, with a short fade at either end to avoid clicks.
    double envelope = std::clamp(std::min(seconds_ / 0.03, (1.5 - seconds_) / 0.03), 0.0, 1.0);
    int16_t sample = static_cast<int16_t>(32767 * gain_ * envelope *
        std::sin(6.283185307179586 * 440 * seconds_));
    for (size_t channel = 0; channel < channels; ++channel) data[frame * channels + channel] = sample;
    if (seconds_ < 1.5) samples_.fetch_add(1, std::memory_order_relaxed);
    seconds_ += 1.0 / rate;
  }
}

int32_t OutputTest::NeedMorePlayData(size_t count, size_t bytes, size_t channels,
    uint32_t rate, void* data, size_t& out, int64_t* elapsed, int64_t* ntp) {
  out = 0;
  if (elapsed) *elapsed = -1;
  if (ntp) *ntp = -1;
  // ADM reports bytes per interleaved frame (2 * channels), not per channel.
  if (!data || !rate || channels == 0 || channels > 8 || bytes != sizeof(int16_t) * channels || count > rate) return -1;
  render(count, channels, rate, static_cast<int16_t*>(data));
  out = count;
  return 0;
}

void OutputTest::PullRenderData(int bits, int rate, size_t channels, size_t count,
    void* data, int64_t* elapsed, int64_t* ntp) {
  if (elapsed) *elapsed = -1;
  if (ntp) *ntp = -1;
  if (!data || rate <= 0 || channels == 0 || channels > 8 ||
      count > static_cast<size_t>(rate)) return;
  if (bits != 16) {
    // Supported alternative PCM widths remain silent, never uninitialized.
    if (bits == 8 || bits == 32) std::memset(data, 0, count * channels * (bits / 8));
    return;
  }
  size_t out = 0;
  NeedMorePlayData(count, 2 * channels, channels, rate, data, out, elapsed, ntp);
}

std::unique_ptr<OutputTest> create_output_test() { return std::make_unique<OutputTest>(); }
}

#include <roapi.h>
#include <winrt/Windows.Devices.Enumeration.h>

// 未知状态由实际采集操作判定；不能把查询失败当成用户拒绝。
extern "C" int zhijie_microphone_denied() noexcept {
    using namespace winrt::Windows::Devices::Enumeration;
    const HRESULT init = RoInitialize(RO_INIT_MULTITHREADED);
    int denied = 0;
    try {
        const auto status = DeviceAccessInformation::CreateFromDeviceClass(DeviceClass::AudioCapture).CurrentStatus();
        denied = status == DeviceAccessStatus::DeniedByUser || status == DeviceAccessStatus::DeniedBySystem;
    } catch (const winrt::hresult_error& error) {
        denied = error.code() == E_ACCESSDENIED;
    } catch (...) {}
    if (SUCCEEDED(init)) RoUninitialize();
    return denied;
}

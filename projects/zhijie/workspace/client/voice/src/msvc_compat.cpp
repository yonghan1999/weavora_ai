#include <cstddef>
#include <cstdint>

extern "C" __declspec(noalias) size_t __stdcall __std_find_first_of_trivial_pos_1(
    const void* const haystack, const size_t haystack_length,
    const void* const needle, const size_t needle_length) noexcept {
    const auto* const haystack_bytes = static_cast<const uint8_t*>(haystack);
    const auto* const needle_bytes = static_cast<const uint8_t*>(needle);
    for (size_t index = 0; index < haystack_length; ++index) {
        for (size_t target = 0; target < needle_length; ++target) {
            if (haystack_bytes[index] == needle_bytes[target]) {
                return index;
            }
        }
    }
    return haystack_length;
}

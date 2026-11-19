#[cxx::bridge(namespace = "livekit_ffi")]
pub mod ffi {
    unsafe extern "C++" {
        include!("livekit/output_test.h");
        type OutputTest;
        fn create_output_test() -> UniquePtr<OutputTest>;
        fn start(self: Pin<&mut OutputTest>, device_id: String, gain: f64) -> bool;
        fn samples(self: &OutputTest) -> u64;
    }
}

crate::impl_thread_safety!(ffi::OutputTest, Send);

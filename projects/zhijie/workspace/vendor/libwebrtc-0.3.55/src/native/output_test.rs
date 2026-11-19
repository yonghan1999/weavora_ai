/// Local output probe. Does not construct or publish a media track.
pub struct OutputTest {
    handle: cxx::UniquePtr<webrtc_sys::output_test::ffi::OutputTest>,
}

impl OutputTest {
    pub fn start(device_id: &str, gain: f64) -> Option<Self> {
        let mut handle = webrtc_sys::output_test::ffi::create_output_test();
        handle
            .pin_mut()
            .start(device_id.into(), gain)
            .then_some(Self { handle })
    }

    pub fn samples(&self) -> u64 {
        self.handle.samples()
    }
}

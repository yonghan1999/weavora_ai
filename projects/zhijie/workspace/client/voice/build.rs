fn main() {
    if std::env::var_os("CARGO_CFG_WINDOWS").is_none() {
        return;
    }
    cc::Build::new()
        .file("src/msvc_compat.cpp")
        .flag("/MT")
        .compile("zhijie_voice_msvc_compat");
}

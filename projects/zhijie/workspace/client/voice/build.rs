fn main() {
    if std::env::var_os("CARGO_CFG_WINDOWS").is_none() {
        return;
    }
    println!("cargo:rustc-link-lib=windowsapp");
    cc::Build::new()
        .file("src/msvc_compat.cpp")
        .file("src/microphone_access.cpp")
        .flag("/std:c++17")
        .flag("/EHsc")
        .flag("/MT")
        .compile("zhijie_voice_msvc_compat");
}

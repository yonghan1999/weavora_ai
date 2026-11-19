use std::fs::{File, OpenOptions};
use std::io::Read;
use std::os::windows::fs::OpenOptionsExt;
use std::path::Path;

use sha2::{Digest, Sha256};

// 与预览打包锁定的原始 x64 Wintun DLL 一致；升级需同时核对来源和许可。
const WINTUN_SHA256: [u8; 32] = [
    0xe5, 0xda, 0x84, 0x47, 0xdc, 0x2c, 0x32, 0x0e, 0xdc, 0x0f, 0xc5, 0x2f, 0xa0, 0x18, 0x85, 0xc1,
    0x03, 0xde, 0x8c, 0x11, 0x84, 0x81, 0xf6, 0x83, 0x64, 0x3c, 0xac, 0xc3, 0x22, 0x0d, 0xaf, 0xce,
];
const WINTUN_LENGTH: u64 = 427_552;

pub(super) fn verified_driver_guard(path: &Path) -> Result<File, String> {
    // FILE_SHARE_READ：校验到加载期间不允许其他句柄写入、删除或重命名该文件。
    // 不调用 LoadLibrary，也不把上游先加载后验签的选项用作加载前验证。
    let mut file = OpenOptions::new()
        .read(true)
        .share_mode(1)
        .open(path)
        .map_err(|_| "TUN_DRIVER_UNAVAILABLE")?;
    let metadata = file.metadata().map_err(|_| "TUN_DRIVER_UNAVAILABLE")?;
    if !metadata.is_file() || metadata.len() != WINTUN_LENGTH {
        return Err("TUN_DRIVER_UNAVAILABLE".into());
    }
    let mut digest = Sha256::new();
    let mut buffer = [0u8; 8192];
    loop {
        let length = file
            .read(&mut buffer)
            .map_err(|_| "TUN_DRIVER_UNAVAILABLE")?;
        if length == 0 {
            break;
        }
        digest.update(&buffer[..length]);
    }
    if digest.finalize().as_slice() != WINTUN_SHA256 {
        return Err("TUN_DRIVER_UNAVAILABLE".into());
    }
    Ok(file)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn missing_or_untrusted_driver_is_rejected_without_loading() {
        let directory = Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../../target")
            .join(format!("driver-rejection-{}", std::process::id()));
        std::fs::create_dir(&directory).unwrap();
        let path = directory.join("wintun.dll");
        assert!(verified_driver_guard(&path).is_err());
        std::fs::write(&path, vec![0u8; WINTUN_LENGTH as usize]).unwrap();
        assert!(verified_driver_guard(&path).is_err());
        std::fs::write(&path, b"not a driver").unwrap();
        assert!(verified_driver_guard(&path).is_err());
        std::fs::remove_file(&path).unwrap();
        std::fs::remove_dir(&directory).unwrap();
    }

    #[test]
    #[ignore = "需要先运行 prepare-wintun.py；只读原始驱动，不加载或创建网卡"]
    fn pinned_driver_is_read_without_loading_and_guard_blocks_changes() {
        let runtime = Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../../target/debug/wintun-runtime/wintun.dll");
        // 真实预编译 DLL 由 prepare-wintun.py 校验准备；不加载、不创建网卡。
        let directory = Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../../target")
            .join(format!("driver-integrity-{}", std::process::id()));
        std::fs::create_dir(&directory).unwrap();
        let path = directory.join("wintun.dll");
        let result = std::panic::catch_unwind(|| {
            assert!(verified_driver_guard(&path).is_err());
            std::fs::copy(&runtime, &path).expect("先运行 packaging/windows/prepare-wintun.py");
            let original = std::fs::read(&path).unwrap();
            let writer = OpenOptions::new().write(true).open(&path).unwrap();
            assert!(verified_driver_guard(&path).is_err());
            drop(writer);
            let guard = verified_driver_guard(&path).unwrap();
            assert!(OpenOptions::new().write(true).open(&path).is_err());
            assert!(std::fs::remove_file(&path).is_err());
            assert!(std::fs::rename(&path, directory.join("moved.dll")).is_err());
            drop(guard);
            let mut changed = original.clone();
            changed[2048] ^= 1;
            std::fs::write(&path, changed).unwrap();
            assert!(verified_driver_guard(&path).is_err());
            std::fs::write(&path, &original[..original.len() - 1]).unwrap();
            assert!(verified_driver_guard(&path).is_err());
            std::fs::write(&path, original).unwrap();
            drop(verified_driver_guard(&path).unwrap());
        });
        if path.exists() {
            std::fs::remove_file(&path).unwrap();
        }
        std::fs::remove_dir(&directory).unwrap();
        if let Err(error) = result {
            std::panic::resume_unwind(error);
        }
    }
}

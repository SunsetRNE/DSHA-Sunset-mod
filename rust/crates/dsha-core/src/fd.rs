//! 唯一的只读 fd 读取边界（ADR-0004 决定四 / 方案 §4.1、§4.3）。
//!
//! Kotlin 打开并核验 fd，Rust 只对 `[offset, offset + length)` 做 `pread`
//! （`FileExt::read_at`）：不 `open`/`stat`/`close`、不接收路径字符串、不创建
//! 进程/线程、不联网、不改变文件偏移。`unsafe` 只用于把 `fd` 临时包成
//! `ManuallyDrop<File>` 视图：不接管所有权，因此不会关闭调用方的 fd；无效
//! fd 由 `read_at` 返回 EBADF（映射 `ERR_INVALID_ARGUMENT`），不构成 UB。
//!
//! 错误语义：EINTR 无限重试（不计入短读）；EBADF/EINVAL/ENXIO/EOVERFLOW/EISDIR
//! 视为调用方错误（`ERR_INVALID_ARGUMENT`）；其它 I/O 错误为 `ERR_INTERNAL`。
//! 读到少于请求字节（含提前 EOF）不算错误，由 `digest` 循环判为
//! `ERR_SHORT_READ`。

use std::fs::File;
use std::io;
use std::mem::ManuallyDrop;
use std::os::fd::FromRawFd;
use std::os::unix::fs::FileExt;

use crate::abi::{ERR_INTERNAL, ERR_INVALID_ARGUMENT};

/// Linux/Android 的 errno 值（不引入 libc crate；`read_at` 通过
/// `raw_os_error()` 返回这些数字，仅用于把调用方错误映射成 ABI 错误码）。
const E_NXIO: i32 = 6;
const E_BADF: i32 = 9;
const E_ISDIR: i32 = 21;
const E_INVAL: i32 = 22;
const E_OVERFLOW: i32 = 75;

/// 从 `offset` 起把最多 `buffer.len()` 字节读入 `buffer`，返回实际字节数。
///
/// `fd` 必须由调用方在本次调用期间保持打开（方案 §4.3 输入契约）；本函数
/// 不接管、不关闭 fd。调用方负责把 `(Ok(n), n < buffer.len())` 解释为短读。
pub(crate) fn read_into(fd: i32, offset: u64, buffer: &mut [u8]) -> Result<usize, i32> {
    if fd < 0 {
        return Err(ERR_INVALID_ARGUMENT);
    }
    // SAFETY: fd 由调用方持有并在调用期间保持打开（方案 §4.3）；ManuallyDrop
    // 保证不 close / 不接管所有权；read_at 只读、使用显式偏移，不改变文件位置。
    let file = ManuallyDrop::new(unsafe { File::from_raw_fd(fd) });
    let mut total = 0usize;
    while total < buffer.len() {
        let current = offset
            .checked_add(total as u64)
            .ok_or(ERR_INVALID_ARGUMENT)?;
        // `read_at` = pread：不移动 fd 的共享文件偏移，可被 Kotlin 侧并发读取。
        match file.read_at(&mut buffer[total..], current) {
            Ok(0) => break,
            Ok(read) => total += read,
            Err(error) if error.kind() == io::ErrorKind::Interrupted => continue,
            Err(error) => return Err(map_io_error(&error)),
        }
    }
    Ok(total)
}

fn map_io_error(error: &io::Error) -> i32 {
    match error.raw_os_error() {
        Some(E_BADF | E_INVAL | E_NXIO | E_OVERFLOW | E_ISDIR) => ERR_INVALID_ARGUMENT,
        _ => ERR_INTERNAL,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use sha2::Digest as _;
    use std::io::Write;
    use std::os::fd::AsRawFd;
    use std::time::{SystemTime, UNIX_EPOCH};

    /// 测试专用只读临时文件（只在 `#[cfg(test)]` 编译单元，不进入 cdylib）。
    ///
    /// 先只写创建并写入，再以只读方式重新打开：`File::create` 是只写打开，
    /// 直接对它 `read_at` 会得到 EBADF（测试会误判实现）。创建后立刻解除
    /// 目录项，fd 仍有效，测试结束时随 `File` 关闭，避免遗留临时文件。
    fn scratch_file(payload: &[u8]) -> File {
        let nanos = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|value| value.as_nanos())
            .unwrap_or(0);
        let location = std::env::temp_dir().join(format!("dsha-rs-fd-{}-{}", nanos, payload.len()));
        let mut file = File::create(&location).unwrap_or_else(|error| panic!("创建失败: {error}"));
        file.write_all(payload)
            .unwrap_or_else(|error| panic!("写入失败: {error}"));
        let reader = File::open(&location).unwrap_or_else(|error| panic!("打开失败: {error}"));
        let _ = std::fs::remove_file(&location);
        reader
    }

    #[test]
    fn reads_the_requested_range_without_moving_the_file_offset() {
        let file = scratch_file(b"0123456789");
        let fd = file.as_raw_fd();
        let mut buffer = [0u8; 4];
        assert_eq!(read_into(fd, 2, &mut buffer), Ok(4));
        assert_eq!(&buffer, b"2345");
        // 显式偏移读取不依赖也不会推进共享文件位置。
        assert_eq!(read_into(fd, 6, &mut buffer), Ok(4));
        assert_eq!(&buffer, b"6789");
        assert_eq!(read_into(fd, 0, &mut buffer), Ok(4));
        assert_eq!(&buffer, b"0123");
    }

    #[test]
    fn eof_returns_a_short_count_for_the_caller_to_reject() {
        let file = scratch_file(b"0123456789");
        let fd = file.as_raw_fd();
        let mut buffer = [0u8; 4];
        assert_eq!(read_into(fd, 8, &mut buffer), Ok(2));
        assert_eq!(&buffer[..2], b"89");
        assert_eq!(read_into(fd, 10, &mut buffer), Ok(0));
        assert_eq!(read_into(fd, 64, &mut buffer), Ok(0));
    }

    #[test]
    fn negative_and_unopened_fds_are_caller_errors_not_ub() {
        assert_eq!(
            read_into(-1, 0, &mut [0u8; 4]).err(),
            Some(ERR_INVALID_ARGUMENT)
        );
        // 一个不可能被进程打开的巨大 fd：内核返回 EBADF，映射为 INVALID_ARGUMENT。
        assert_eq!(
            read_into(i32::MAX, 0, &mut [0u8; 4]).err(),
            Some(ERR_INVALID_ARGUMENT)
        );
    }

    #[test]
    fn digest_fd_honours_the_requested_offset() {
        // 回归：digest_range 传给 provider 的是区间内相对偏移，digest_fd 必须加上
        // 调用方 offset 才是 pread 的绝对偏移（R1 首次运行时曾漏加）。
        let file = scratch_file(b"0123456789");
        let fd = file.as_raw_fd();
        let result = crate::digest::digest_fd(fd, 2, 8, crate::digest::ALGORITHM_SHA256)
            .unwrap_or_else(|code| panic!("摘要失败: {}", code));
        let expected = sha2::Sha256::digest(b"23456789");
        assert_eq!(result.digest(), expected.as_slice());
        assert_eq!(result.bytes_read(), 8);
        // SHA-1 同一边界，避免只修一种算法。
        let sha1 = crate::digest::digest_fd(fd, 5, 3, crate::digest::ALGORITHM_SHA1)
            .unwrap_or_else(|code| panic!("摘要失败: {}", code));
        let expected_sha1 = sha1_digest(b"567");
        assert_eq!(sha1.digest(), expected_sha1.as_slice());
    }

    fn sha1_digest(data: &[u8]) -> [u8; 20] {
        use sha1::Digest as _;
        let mut hasher = sha1::Sha1::new();
        hasher.update(data);
        hasher.finalize().into()
    }

    #[test]
    fn huge_offsets_are_rejected_not_ub() {
        let file = scratch_file(b"0123456789");
        let fd = file.as_raw_fd();
        // 超出 off_t 正区间的偏移由内核拒绝（EINVAL），映射为调用方错误码。
        assert_eq!(
            read_into(fd, u64::MAX, &mut [0u8; 2]).err(),
            Some(ERR_INVALID_ARGUMENT)
        );
    }
}

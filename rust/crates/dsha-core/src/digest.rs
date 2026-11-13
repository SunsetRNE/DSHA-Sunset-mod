//! R1 实现：`digest(fd_range, algorithm)`（方案 §4.3）。
//!
//! 只对 Kotlin 已核验并移交的 `fd + offset + length` 做只读摘要；Rust 不
//! `open`/`stat`/`close`、不接收路径字符串、不决定 fd 是否可信。`fd` 的
//! `read_at` 只出现在 `crate::fd`（唯一允许 `unsafe` 的只读 fd 边界），本模块
//! 只做参数校验、分块循环与 SHA-256 / SHA-1 计算，因此保持 `forbid(unsafe_code)`。
//!
//! 错误码与上限按 R0 冻结的 ABI（`crate::abi` / 方案 §4.2、§4.3）：
//!
//! - `fd < 0`、`offset < 0`、`length < 1`、`offset + length` 溢出、
//!   `length > MAX_LENGTH` → `ERR_INVALID_ARGUMENT`；
//! - `algorithm` 不在 {SHA-256, SHA-1} → `ERR_UNSUPPORTED_ALGORITHM`；
//! - `read_at` 读到的字节少于请求长度（含提前 EOF） → `ERR_SHORT_READ`；
//! - `pread` 的 EBADF/EINVAL 等调用方错误 → `ERR_INVALID_ARGUMENT`，
//!   其它 I/O 错误 → `ERR_INTERNAL`。EINTR 重试，不计入短读。
//!
//! 成功时返回 `bytesRead = length` 与定长摘要；任何错误都不写输出缓冲区、
//! 不返回半成品。Rust 内部按 `CHUNK`（256 KiB）循环读取，不按输入长度分配
//! 无界 `Vec`。
#![forbid(unsafe_code)]

use sha1::Sha1;
use sha2::{Digest, Sha256};

use crate::abi::{ERR_INVALID_ARGUMENT, ERR_SHORT_READ, ERR_UNSUPPORTED_ALGORITHM};

/// `algorithm` 取值：SHA-256（与 Java `MessageDigest` 名称对应）。
pub const ALGORITHM_SHA256: i32 = 1;
/// `algorithm` 取值：SHA-1。
pub const ALGORITHM_SHA1: i32 = 2;

/// 单次调用允许的最大长度（32 MiB）；大文件由 Kotlin 分段循环。
pub const MAX_LENGTH: u64 = 33_554_432;

/// 内部 `read_at` 流式缓冲（256 KiB）；不按输入长度做无界分配。
pub const CHUNK: usize = 262_144;

/// 摘要输出上限：SHA-256 为 32 B，SHA-1 为 20 B；调用方必须提供至少 32 B。
pub const MAX_DIGEST_BYTES: usize = 32;

/// 首期只允许的两种摘要算法（与代码中仅有的两种 `MessageDigest` 一致）。
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Algorithm {
    Sha256,
    Sha1,
}

impl Algorithm {
    /// `algorithm` → 算法；未知值返回 `None`（入口映射为 `ERR_UNSUPPORTED_ALGORITHM`）。
    pub const fn from_code(code: i32) -> Option<Self> {
        match code {
            ALGORITHM_SHA256 => Some(Self::Sha256),
            ALGORITHM_SHA1 => Some(Self::Sha1),
            _ => None,
        }
    }

    /// 摘要字节数（SHA-256 = 32，SHA-1 = 20）。
    pub const fn digest_bytes(self) -> usize {
        match self {
            Self::Sha256 => 32,
            Self::Sha1 => 20,
        }
    }
}

/// 一次成功摘要的结果：定长摘要、有效字节数与已读长度。
#[derive(Clone, Copy, Debug)]
pub struct DigestResult {
    digest: [u8; MAX_DIGEST_BYTES],
    digest_len: usize,
    bytes_read: u64,
}

impl DigestResult {
    /// 摘要字节视图（长度为 `digest_len`）。
    pub fn digest(&self) -> &[u8] {
        &self.digest[..self.digest_len]
    }

    /// 实际读取长度；成功调用恒等于请求的 `length`。
    pub const fn bytes_read(&self) -> u64 {
        self.bytes_read
    }
}

/// 校验 `digest` 冻结 ABI 的标量参数。返回 `(offset, length, algorithm)`。
///
/// 校验顺序固定为 fd → offset → length → 溢出 → algorithm，便于 JVM 差分测试
/// 只构造一个非法参数就得到确定错误码。
pub fn validate(
    fd: i32,
    offset: i64,
    length: i64,
    algorithm: i32,
) -> Result<(u64, u64, Algorithm), i32> {
    if fd < 0 {
        return Err(ERR_INVALID_ARGUMENT);
    }
    if offset < 0 {
        return Err(ERR_INVALID_ARGUMENT);
    }
    if length < 1 {
        return Err(ERR_INVALID_ARGUMENT);
    }
    let offset = offset as u64;
    let length = length as u64;
    // 超 ABI 上限按 §4.2 的 INVALID_ARGUMENT 行（“非法长度/超 ABI 上限”）处理；
    // 不用 INPUT_BOUND（那是归档会话的累计输入上限）。
    if length > MAX_LENGTH {
        return Err(ERR_INVALID_ARGUMENT);
    }
    let end = offset.checked_add(length).ok_or(ERR_INVALID_ARGUMENT)?;
    // pread 的偏移是 off_t（i64）：区间上界也必须落在非负 off_t 内，否则
    // 内核会以 EINVAL 拒绝，提前用同一错误码拒绝更明确。
    if end > i64::MAX as u64 {
        return Err(ERR_INVALID_ARGUMENT);
    }
    let algorithm = Algorithm::from_code(algorithm).ok_or(ERR_UNSUPPORTED_ALGORITHM)?;
    Ok((offset, length, algorithm))
}

/// 对 Kotlin 已核验的只读 fd 区间计算摘要（Rust 不 close fd）。
///
/// `read_at` 由 `crate::fd` 提供：只做 `pread`，不 `open`、不 `stat`、不改变
/// 文件偏移。成功返回 `bytes_read == length`；短读与任何错误都返回负错误码。
pub fn digest_fd(fd: i32, offset: i64, length: i64, algorithm: i32) -> Result<DigestResult, i32> {
    let (offset, length, algorithm) = validate(fd, offset, length, algorithm)?;
    digest_range(length, algorithm, |position, out| {
        // `position` 是区间内的相对偏移；pread 的绝对偏移必须加上调用方 offset。
        let current = offset.checked_add(position).ok_or(ERR_INVALID_ARGUMENT)?;
        crate::fd::read_into(fd, current, out)
    })
}

/// 有界分块摘要循环：`read_at(position, buffer)` 必须填满 `buffer` 或返回错误。
///
/// 与 fd 解耦，便于主机单测覆盖分块/边界/短读语义而无需文件系统。
pub fn digest_range<F>(
    length: u64,
    algorithm: Algorithm,
    mut read_at: F,
) -> Result<DigestResult, i32>
where
    F: FnMut(u64, &mut [u8]) -> Result<usize, i32>,
{
    let total = usize::try_from(length).map_err(|_| ERR_INVALID_ARGUMENT)?;
    let mut hasher: Box<dyn DynHasher> = match algorithm {
        Algorithm::Sha256 => Box::new(Sha256::new()),
        Algorithm::Sha1 => Box::new(Sha1::new()),
    };
    let mut buffer = [0u8; CHUNK];
    let mut consumed = 0usize;
    while consumed < total {
        let want = core::cmp::min(CHUNK, total - consumed);
        let slice = &mut buffer[..want];
        let read = read_at(consumed as u64, slice)?;
        if read != want {
            // 提前 EOF / 截断：不返回部分摘要，输出保持未写入。
            return Err(ERR_SHORT_READ);
        }
        hasher.update(&buffer[..want]);
        consumed += want;
    }
    let mut result = DigestResult {
        digest: [0u8; MAX_DIGEST_BYTES],
        digest_len: algorithm.digest_bytes(),
        bytes_read: length,
    };
    hasher.finish_into(&mut result.digest[..result.digest_len]);
    Ok(result)
}

/// 把两种 `digest` crate 实现收敛到同一对象安全接口（避免泛型双份循环代码）。
trait DynHasher {
    fn update(&mut self, data: &[u8]);
    fn finish_into(self: Box<Self>, out: &mut [u8]);
}

impl DynHasher for Sha256 {
    fn update(&mut self, data: &[u8]) {
        Digest::update(self, data);
    }

    fn finish_into(self: Box<Self>, out: &mut [u8]) {
        let value = self.finalize();
        out.copy_from_slice(&value);
    }
}

impl DynHasher for Sha1 {
    fn update(&mut self, data: &[u8]) {
        Digest::update(self, data);
    }

    fn finish_into(self: Box<Self>, out: &mut [u8]) {
        let value = self.finalize();
        out.copy_from_slice(&value);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::abi::ERR_INTERNAL;

    fn hex(bytes: &[u8]) -> String {
        let mut out = String::with_capacity(bytes.len() * 2);
        for byte in bytes {
            out.push_str(&format!("{:02x}", byte));
        }
        out
    }

    /// 已知向量提供者：按偏移从固定字节串切片，模拟“读满请求长度”。
    fn memory_reader<'a>(data: &'a [u8]) -> impl FnMut(u64, &mut [u8]) -> Result<usize, i32> + 'a {
        move |position: u64, out: &mut [u8]| {
            let start = usize::try_from(position).map_err(|_| ERR_INVALID_ARGUMENT)?;
            let end = start.checked_add(out.len()).ok_or(ERR_INVALID_ARGUMENT)?;
            if end > data.len() {
                let available = data.len().saturating_sub(start);
                out[..available].copy_from_slice(&data[start..]);
                return Ok(available);
            }
            out.copy_from_slice(&data[start..end]);
            Ok(out.len())
        }
    }

    fn digest_of(data: &[u8], algorithm: Algorithm) -> String {
        let result = digest_range(data.len() as u64, algorithm, memory_reader(data))
            .unwrap_or_else(|code| panic!("摘要失败: {}", code));
        hex(result.digest())
    }

    #[test]
    fn sha256_known_vectors() {
        assert_eq!(
            digest_of(b"abc", Algorithm::Sha256),
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        );
        assert_eq!(
            digest_of(b"", Algorithm::Sha256),
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        );
        assert_eq!(
            digest_of(
                b"abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq",
                Algorithm::Sha256
            ),
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1"
        );
    }

    #[test]
    fn sha1_known_vectors() {
        assert_eq!(
            digest_of(b"abc", Algorithm::Sha1),
            "a9993e364706816aba3e25717850c26c9cd0d89d"
        );
        assert_eq!(
            digest_of(b"", Algorithm::Sha1),
            "da39a3ee5e6b4b0d3255bfef95601890afd80709"
        );
        assert_eq!(
            digest_of(
                b"abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq",
                Algorithm::Sha1
            ),
            "84983e441c3bd26ebaae4aa1f95129e5e54670f1"
        );
    }

    #[test]
    fn chunk_boundaries_match_one_shot_digest() {
        // 256 KiB 边界前后各一字节 + 跨多块：用确定性图案与 sha2 一次性摘要对照，
        // 覆盖分块循环本身（加密正确性由上面的已知向量覆盖）。
        let sizes = [CHUNK - 1, CHUNK, CHUNK + 1, CHUNK * 3 + 7];
        for size in sizes {
            let data: Vec<u8> = (0..size).map(|index| (index % 251) as u8).collect();
            let one_shot = Sha256::digest(&data);
            let result = digest_range(size as u64, Algorithm::Sha256, memory_reader(&data))
                .unwrap_or_else(|code| panic!("摘要失败: {}", code));
            assert_eq!(result.digest(), one_shot.as_slice(), "size={}", size);
            assert_eq!(result.bytes_read(), size as u64);
        }
    }

    #[test]
    fn empty_input_is_allowed_by_the_chunk_loop() {
        // ABI 冻结的 length 下限为 1；空输入只在分块循环层校验向量。
        let result = digest_range(0, Algorithm::Sha256, |_position, _out| Ok(0))
            .unwrap_or_else(|code| panic!("空输入失败: {}", code));
        assert_eq!(
            hex(result.digest()),
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        );
        assert_eq!(result.bytes_read(), 0);
    }

    #[test]
    fn short_read_is_an_error_and_not_a_partial_digest() {
        let data = b"0123456789";
        let error = digest_range(data.len() as u64, Algorithm::Sha256, |position, out| {
            let start = usize::try_from(position).map_err(|_| ERR_INVALID_ARGUMENT)?;
            if start == 0 {
                out[..5].copy_from_slice(&data[..5]);
                Ok(5)
            } else {
                Err(ERR_INTERNAL)
            }
        });
        assert_eq!(error.err(), Some(ERR_SHORT_READ));
        // 提前 EOF（返回 0）同样是短读。
        let eof = digest_range(4, Algorithm::Sha1, |_position, _out| Ok(0));
        assert_eq!(eof.err(), Some(ERR_SHORT_READ));
        // 读取错误原样传递，不折成短读。
        let failed = digest_range(4, Algorithm::Sha1, |_position, _out| Err(ERR_INTERNAL));
        assert_eq!(failed.err(), Some(ERR_INTERNAL));
    }

    #[test]
    fn validate_enforces_the_frozen_bounds() {
        assert_eq!(
            validate(-1, 0, 1, ALGORITHM_SHA256).err(),
            Some(ERR_INVALID_ARGUMENT)
        );
        assert_eq!(
            validate(0, -1, 1, ALGORITHM_SHA256).err(),
            Some(ERR_INVALID_ARGUMENT)
        );
        assert_eq!(
            validate(0, 0, 0, ALGORITHM_SHA256).err(),
            Some(ERR_INVALID_ARGUMENT)
        );
        assert_eq!(
            validate(0, 0, MAX_LENGTH as i64 + 1, ALGORITHM_SHA256).err(),
            Some(ERR_INVALID_ARGUMENT)
        );
        assert_eq!(
            validate(0, i64::MAX, 1, ALGORITHM_SHA256).err(),
            Some(ERR_INVALID_ARGUMENT)
        );
        assert_eq!(validate(0, 0, 1, 99).err(), Some(ERR_UNSUPPORTED_ALGORITHM));
        assert!(validate(0, 0, 1, ALGORITHM_SHA256).is_ok());
        assert!(validate(0, 0, MAX_LENGTH as i64, ALGORITHM_SHA1).is_ok());
        assert_eq!(
            Algorithm::from_code(ALGORITHM_SHA256),
            Some(Algorithm::Sha256)
        );
        assert_eq!(Algorithm::from_code(ALGORITHM_SHA1), Some(Algorithm::Sha1));
        assert_eq!(Algorithm::from_code(0), None);
        assert_eq!(Algorithm::Sha256.digest_bytes(), 32);
        assert_eq!(Algorithm::Sha1.digest_bytes(), 20);
    }
}

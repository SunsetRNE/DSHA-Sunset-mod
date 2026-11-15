//! R3 实现：单成员 RFC 1952 gzip 流式编解码（方案 §4.4、§4.5）。
//!
//! 只做有界字节流：不写 tar/容器格式、不接收路径、不做文件 I/O、不做多成员、
//! 不创建线程/进程、不写 stdout/stderr/logcat。`pack` 与 `unpack` 都是**会话式**
//! 有界纯函数：调用方喂入/取出 direct `ByteBuffer` 的字节，Rust 只在会话内保存
//! 定长状态（输入缓冲上限 256 KiB）；ABI 见 `crate::abi`，错误码在 R0 已冻结。
//!
//! `unpack` 的语义镜像 `app/src/main/java/com/deepseekharness/app/backup/StrictGzipInputStream.java`：
//! 单成员、严格头/尾校验、拒绝尾随字节与多成员；`FNAME`/`FCOMMENT` 各 ≤ 8192 字节
//! （含终止 0），`FEXTRA` 与 JVM 实现一致按 u16 长度（≤ 65535）逐字节流式接收。
//! 方案 §4.5 的“FEXTRA ≤ 8192”已由 2026-11-14 Lead waiver（R3 评审 P2-4）改为
//! 上述实际口径；本实现不改 JVM 参考读取器。
//!
//! 截断（头/体/尾提前 EOF）在 native 层表现为 `NEED_INPUT`：Rust 不知道调用方
//! 的字节流是否已经结束，由 JVM 桥在底层 `InputStream` 返回 -1 时映射为
//! `GZIP_TRUNCATED`（与 `StrictGzipInputStream` 的 EOFException 一致）。

#![forbid(unsafe_code)]

use std::collections::BTreeMap;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Mutex, MutexGuard, OnceLock};

use flate2::{Compress, Compression, Crc, Decompress, FlushCompress, FlushDecompress, Status};

use crate::abi::{
    ERR_GZIP_DEFLATE, ERR_GZIP_DICTIONARY, ERR_GZIP_HEADER, ERR_GZIP_HEADER_CRC,
    ERR_GZIP_METADATA_LIMIT, ERR_GZIP_STALLED, ERR_GZIP_TRAILER, ERR_GZIP_TRAILING_DATA,
    ERR_INPUT_BOUND, ERR_INTERNAL, ERR_INVALID_ARGUMENT, ERR_OUTPUT_BOUND, ERR_SESSION_LIMIT,
    ERR_STALE_HANDLE, MAX_SESSIONS,
};

/// 单次 `update`/`write` 输入上限（256 KiB，沿用现有 SAF 读块）。
pub const MAX_INPUT_CHUNK: usize = 262_144;

/// 单次 `read` 输出上限（256 KiB）。
pub const MAX_OUTPUT_CHUNK: usize = 262_144;

/// `FNAME`/`FCOMMENT` 最大长度（含终止 0）；与 `StrictGzipInputStream` 一致。
pub const MAX_METADATA: usize = 8_192;

/// `pack` 会话压缩级别范围（0 = store，默认 6）。
pub const MIN_LEVEL: i32 = 0;
pub const MAX_LEVEL: i32 = 9;
pub const DEFAULT_LEVEL: i32 = 6;

/// 固定 gzip 头长度（magic 2 + CM/FG 2 + MTIME 4 + XFL/OS 2）。
pub const HEADER_BYTES: usize = 10;

/// gzip 尾长度（CRC32 + ISIZE）。
pub const TRAILER_BYTES: usize = 8;

/// `finish` 输出缓冲下限；覆盖“上次 `update` 未刷出的 deflate 数据 + 最终块 + 尾部”。
/// 会话输入上限 256 KiB、窗口 32 KiB，实际待刷数据远小于该值；单测按 level 0–9
/// 与不可压缩载荷验证。
pub const FINISH_BOUND: usize = 128 * 1024;

/// `read` 返回的位标志（方案 §4.5）：0x1 EOF、0x2 NEED_INPUT、0x4 NEED_OUTPUT。
pub const FLAG_EOF: u32 = 0x1;
pub const FLAG_NEED_INPUT: u32 = 0x2;
pub const FLAG_NEED_OUTPUT: u32 = 0x4;

/// 固定单成员 gzip 头：`1f 8b 08 00 00000000 00 00`（FLG=0、MTIME=0、XFL=0、OS=0）。
pub const GZIP_HEADER: [u8; HEADER_BYTES] =
    [0x1f, 0x8b, 0x08, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00];

/// 累计压缩输入 / 解压输出上限：`BackupLimits.BYTES + 256 MiB`（16 GiB + 256 MiB）。
pub const MAX_TOTAL_BYTES: u64 = 16 * 1024 * 1024 * 1024 + 256 * 1024 * 1024;

const GZIP_MAGIC: [u8; 3] = [0x1f, 0x8b, 0x08];
const FLAG_FEXTRA: u8 = 0x04;
const FLAG_FNAME: u8 = 0x08;
const FLAG_FCOMMENT: u8 = 0x10;
const FLAG_FHCRC: u8 = 0x02;

/// `pack` 单次输出缓冲下限（方案 §4.4）：`n + n/8 + 128`。
///
/// 128 字节余量覆盖固定头（10 B）与 deflate 最坏膨胀；不可压缩输入的实测输出
/// 远低于该上界，单测按 level 0–9 逐块核对。
pub const fn pack_bound(input_length: usize) -> usize {
    input_length
        .saturating_add(input_length / 8)
        .saturating_add(128)
}

/// `pack` 单次输出上限：`pack_bound(MAX_INPUT_CHUNK)`（含上一次 update 待刷数据的余量）。
pub const MAX_PACK_OUTPUT: usize = pack_bound(MAX_INPUT_CHUNK);

/// 会话句柄：单调递增 `u64`，0 保留为无效值。
pub type Handle = u64;

static NEXT_HANDLE: AtomicU64 = AtomicU64::new(1);

enum Session {
    Pack(PackStream),
    Unpack(UnpackStream),
}

static SESSIONS: OnceLock<Mutex<BTreeMap<Handle, Session>>> = OnceLock::new();

fn sessions() -> &'static Mutex<BTreeMap<Handle, Session>> {
    SESSIONS.get_or_init(|| Mutex::new(BTreeMap::new()))
}

/// 取会话表；panic 导致的锁中毒只恢复纯数据，不把错误扩散给调用方。
fn lock_sessions() -> MutexGuard<'static, BTreeMap<Handle, Session>> {
    match sessions().lock() {
        Ok(guard) => guard,
        Err(poisoned) => poisoned.into_inner(),
    }
}

fn next_handle() -> Handle {
    let handle = NEXT_HANDLE.fetch_add(1, Ordering::Relaxed);
    if handle == 0 {
        // 只有 u64 回绕才会发生；回绕后的句柄不能复用，直接失败（不 panic）。
        NEXT_HANDLE.store(1, Ordering::Relaxed);
        return 0;
    }
    handle
}

/// 创建一个 gzip 编码会话；`level` 不在 0..=9 → `INVALID_ARGUMENT`。
pub fn pack_create(level: i32) -> Result<Handle, i32> {
    let stream = PackStream::new(level)?;
    let mut table = lock_sessions();
    if table.len() >= MAX_SESSIONS {
        return Err(ERR_SESSION_LIMIT);
    }
    let handle = next_handle();
    if handle == 0 {
        return Err(ERR_INTERNAL);
    }
    table.insert(handle, Session::Pack(stream));
    Ok(handle)
}

/// 喂入一块明文并取出压缩字节；返回 `(consumed << 32) | produced`。
///
/// 输出缓冲不足在任何状态变更前返回 `OUTPUT_BOUND`，调用方换更大缓冲重试同一块
/// 输入即可（方案 §4.4“不产生半块状状态”）。
pub fn pack_update(handle: Handle, input: &[u8], out: &mut [u8]) -> Result<i64, i32> {
    let mut table = lock_sessions();
    let stream = match table.get_mut(&handle) {
        Some(Session::Pack(stream)) => stream,
        _ => return Err(ERR_STALE_HANDLE),
    };
    let result = stream.update(input, out);
    if matches!(result, Err(ERR_INTERNAL)) {
        table.remove(&handle);
    }
    let (consumed, produced) = result?;
    Ok(((consumed as i64) << 32) | produced as i64)
}

/// 结束编码会话并取出最终块 + 尾部；成功后会从表中移除（句柄失效）。
pub fn pack_finish(handle: Handle, out: &mut [u8]) -> Result<i64, i32> {
    let mut table = lock_sessions();
    let result = match table.get_mut(&handle) {
        Some(Session::Pack(stream)) => stream.finish(out),
        _ => return Err(ERR_STALE_HANDLE),
    };
    match result {
        Ok(produced) => {
            table.remove(&handle);
            Ok(produced as i64)
        }
        Err(code) => {
            if code == ERR_INTERNAL {
                table.remove(&handle);
            }
            Err(code)
        }
    }
}

/// 释放编码会话（成功与否都移除）；句柄不存在/类型不符 → `STALE_HANDLE`。
pub fn pack_abort(handle: Handle) -> Result<i32, i32> {
    abort_session(handle, true)
}

/// 创建一个 gzip 解码会话。
pub fn unpack_create() -> Result<Handle, i32> {
    let mut table = lock_sessions();
    if table.len() >= MAX_SESSIONS {
        return Err(ERR_SESSION_LIMIT);
    }
    let handle = next_handle();
    if handle == 0 {
        return Err(ERR_INTERNAL);
    }
    table.insert(handle, Session::Unpack(UnpackStream::new()));
    Ok(handle)
}

/// 喂入一块压缩字节；返回实际消费的字节数（内部缓冲满时可 < 输入长度，甚至为 0）。
pub fn unpack_write(handle: Handle, input: &[u8]) -> Result<i64, i32> {
    let mut table = lock_sessions();
    let stream = match table.get_mut(&handle) {
        Some(Session::Unpack(stream)) => stream,
        _ => return Err(ERR_STALE_HANDLE),
    };
    match stream.write(input) {
        Ok(consumed) => Ok(consumed as i64),
        Err(code) => {
            // 任何错误后会话进入失败态；由 abort 或新会话恢复（不重放已消费输入）。
            stream.fail(code);
            Err(code)
        }
    }
}

/// 取出解压字节；返回 `(produced << 32) | flags`，flags 见 `FLAG_*`。
pub fn unpack_read(handle: Handle, out: &mut [u8]) -> Result<i64, i32> {
    let mut table = lock_sessions();
    let stream = match table.get_mut(&handle) {
        Some(Session::Unpack(stream)) => stream,
        _ => return Err(ERR_STALE_HANDLE),
    };
    match stream.read(out) {
        Ok((produced, flags)) => Ok(((produced as i64) << 32) | i64::from(flags)),
        Err(code) => Err(code),
    }
}

/// 释放解码会话；句柄不存在/类型不符 → `STALE_HANDLE`。
pub fn unpack_abort(handle: Handle) -> Result<i32, i32> {
    abort_session(handle, false)
}

fn abort_session(handle: Handle, expect_pack: bool) -> Result<i32, i32> {
    let mut table = lock_sessions();
    let matches = match table.get(&handle) {
        Some(Session::Pack(_)) => expect_pack,
        Some(Session::Unpack(_)) => !expect_pack,
        None => false,
    };
    if !matches {
        return Err(ERR_STALE_HANDLE);
    }
    table.remove(&handle);
    Ok(0)
}

/// 有界 gzip 编码器（不依赖会话表，单测可直接驱动）。
pub struct PackStream {
    compress: Compress,
    crc: Crc,
    total_in: u64,
    header_pending: bool,
    finished: bool,
}

impl PackStream {
    /// 新建编码器；`level` 不在 0..=9 → `INVALID_ARGUMENT`。
    pub fn new(level: i32) -> Result<Self, i32> {
        if !(MIN_LEVEL..=MAX_LEVEL).contains(&level) {
            return Err(ERR_INVALID_ARGUMENT);
        }
        Ok(Self {
            compress: Compress::new(Compression::new(level as u32), false),
            crc: Crc::new(),
            total_in: 0,
            header_pending: true,
            finished: false,
        })
    }

    /// 是否已成功 `finish`。
    pub fn is_finished(&self) -> bool {
        self.finished
    }

    /// 喂入一块明文；返回 `(consumed, produced)`。
    pub fn update(&mut self, input: &[u8], out: &mut [u8]) -> Result<(usize, usize), i32> {
        if self.finished {
            return Err(ERR_STALE_HANDLE);
        }
        if input.is_empty() {
            return Err(ERR_INVALID_ARGUMENT);
        }
        if input.len() > MAX_INPUT_CHUNK {
            return Err(ERR_INPUT_BOUND);
        }
        if out.len() < pack_bound(input.len()) {
            return Err(ERR_OUTPUT_BOUND);
        }
        let mut produced = 0usize;
        if self.header_pending {
            out[..HEADER_BYTES].copy_from_slice(&GZIP_HEADER);
            produced = HEADER_BYTES;
            self.header_pending = false;
        }
        let mut consumed = 0usize;
        loop {
            let before_in = self.compress.total_in();
            let before_out = self.compress.total_out();
            if self
                .compress
                .compress(
                    &input[consumed..],
                    &mut out[produced..],
                    FlushCompress::None,
                )
                .is_err()
            {
                return Err(ERR_INTERNAL);
            }
            let step_in =
                ((self.compress.total_in() - before_in) as usize).min(input.len() - consumed);
            let step_out = (self.compress.total_out() - before_out) as usize;
            if step_out > out.len() - produced {
                return Err(ERR_INTERNAL);
            }
            self.crc.update(&input[consumed..consumed + step_in]);
            consumed += step_in;
            self.total_in += step_in as u64;
            produced += step_out;
            if consumed == input.len() {
                return Ok((consumed, produced));
            }
            if produced == out.len() {
                // 输出空间用尽：返回已消费/已产出的进度，调用方用剩余输入继续。
                // 不丢状态、不报错（方案 §4.4 的“会话内保留未消费输入，可重试”）。
                return Ok((consumed, produced));
            }
            if step_in == 0 && step_out == 0 {
                // 有输出空间却无进展：属实现/契约异常；此时未消费任何输入。
                return Err(ERR_OUTPUT_BOUND);
            }
        }
    }

    /// 刷出最终块与 gzip 尾部；成功后会话不可再用。
    pub fn finish(&mut self, out: &mut [u8]) -> Result<usize, i32> {
        if self.finished {
            return Err(ERR_STALE_HANDLE);
        }
        if out.len() < FINISH_BOUND {
            return Err(ERR_OUTPUT_BOUND);
        }
        let mut produced = 0usize;
        if self.header_pending {
            out[..HEADER_BYTES].copy_from_slice(&GZIP_HEADER);
            produced = HEADER_BYTES;
            self.header_pending = false;
        }
        loop {
            let before_out = self.compress.total_out();
            let status =
                match self
                    .compress
                    .compress(&[], &mut out[produced..], FlushCompress::Finish)
                {
                    Ok(status) => status,
                    Err(_) => return Err(ERR_INTERNAL),
                };
            let step_out = (self.compress.total_out() - before_out) as usize;
            if step_out > out.len() - produced {
                return Err(ERR_INTERNAL);
            }
            produced += step_out;
            match status {
                Status::StreamEnd => break,
                Status::Ok | Status::BufError => {
                    // Finish 阶段没有更多输入，无输出进展说明内部实现不按约定结束。
                    if step_out == 0 || produced == out.len() {
                        return Err(ERR_INTERNAL);
                    }
                }
            }
        }
        if out.len() - produced < TRAILER_BYTES {
            return Err(ERR_INTERNAL);
        }
        out[produced..produced + 4].copy_from_slice(&self.crc.sum().to_le_bytes());
        out[produced + 4..produced + 8].copy_from_slice(&(self.total_in as u32).to_le_bytes());
        produced += TRAILER_BYTES;
        self.finished = true;
        Ok(produced)
    }
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum Phase {
    Header,
    Body,
    Trailer,
    Eof,
    Failed(i32),
}

/// gzip 头增量解析状态（逐字节，与 `StrictGzipInputStream` 的读取顺序一致）。
struct HeaderParser {
    stage: Stage,
    flags: u8,
    crc: Crc,
    extra_lo: u8,
    fhcrc_lo: u8,
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum Stage {
    Magic(u8),
    Flags,
    Fixed(u8),
    ExtraLo,
    ExtraHi,
    Extra(u32),
    Name(u32),
    Comment(u32),
    FhcrcLo,
    FhcrcHi,
    Done,
}

impl HeaderParser {
    fn new() -> Self {
        Self {
            stage: Stage::Magic(0),
            flags: 0,
            crc: Crc::new(),
            extra_lo: 0,
            fhcrc_lo: 0,
        }
    }

    fn done(&self) -> bool {
        self.stage == Stage::Done
    }

    fn step(&mut self, byte: u8) -> Result<(), i32> {
        match self.stage {
            Stage::Magic(index) => {
                if byte != GZIP_MAGIC[index as usize] {
                    return Err(ERR_GZIP_HEADER);
                }
                self.crc.update(&[byte]);
                self.stage = if index == 2 {
                    Stage::Flags
                } else {
                    Stage::Magic(index + 1)
                };
            }
            Stage::Flags => {
                if byte & 0xe0 != 0 {
                    // 保留位（bit5..bit7）非法；FTEXT(bit0) 允许并忽略。
                    return Err(ERR_GZIP_HEADER);
                }
                self.crc.update(&[byte]);
                self.flags = byte;
                self.stage = Stage::Fixed(6);
            }
            Stage::Fixed(remaining) => {
                self.crc.update(&[byte]);
                self.stage = if remaining > 1 {
                    Stage::Fixed(remaining - 1)
                } else {
                    self.next_optional()
                };
            }
            Stage::ExtraLo => {
                self.crc.update(&[byte]);
                self.extra_lo = byte;
                self.stage = Stage::ExtraHi;
            }
            Stage::ExtraHi => {
                self.crc.update(&[byte]);
                let length = u32::from(self.extra_lo) | (u32::from(byte) << 8);
                self.stage = if length == 0 {
                    self.after_extra()
                } else {
                    Stage::Extra(length)
                };
            }
            Stage::Extra(remaining) => {
                self.crc.update(&[byte]);
                self.stage = if remaining <= 1 {
                    self.after_extra()
                } else {
                    Stage::Extra(remaining - 1)
                };
            }
            Stage::Name(read) => {
                let next = read + 1;
                self.crc.update(&[byte]);
                if byte == 0 {
                    self.stage = self.after_name();
                } else if next >= MAX_METADATA as u32 {
                    // 与 JVM 读取器一致：8192 字节内没有终止 0 即错误（含边界字节）。
                    return Err(ERR_GZIP_METADATA_LIMIT);
                } else {
                    self.stage = Stage::Name(next);
                }
            }
            Stage::Comment(read) => {
                let next = read + 1;
                self.crc.update(&[byte]);
                if byte == 0 {
                    self.stage = self.after_comment();
                } else if next >= MAX_METADATA as u32 {
                    return Err(ERR_GZIP_METADATA_LIMIT);
                } else {
                    self.stage = Stage::Comment(next);
                }
            }
            Stage::FhcrcLo => {
                // FHCRC 两个字节不参与头部 CRC。
                self.fhcrc_lo = byte;
                self.stage = Stage::FhcrcHi;
            }
            Stage::FhcrcHi => {
                let expected = u16::from(self.fhcrc_lo) | (u16::from(byte) << 8);
                if expected != (self.crc.sum() as u16) {
                    return Err(ERR_GZIP_HEADER_CRC);
                }
                self.stage = Stage::Done;
            }
            Stage::Done => {
                // 调用方在 Done 后不再喂头字节；防御性忽略。
            }
        }
        Ok(())
    }

    fn next_optional(&self) -> Stage {
        if self.flags & FLAG_FEXTRA != 0 {
            Stage::ExtraLo
        } else if self.flags & FLAG_FNAME != 0 {
            Stage::Name(0)
        } else if self.flags & FLAG_FCOMMENT != 0 {
            Stage::Comment(0)
        } else if self.flags & FLAG_FHCRC != 0 {
            Stage::FhcrcLo
        } else {
            Stage::Done
        }
    }

    fn after_extra(&self) -> Stage {
        if self.flags & FLAG_FNAME != 0 {
            Stage::Name(0)
        } else if self.flags & FLAG_FCOMMENT != 0 {
            Stage::Comment(0)
        } else if self.flags & FLAG_FHCRC != 0 {
            Stage::FhcrcLo
        } else {
            Stage::Done
        }
    }

    fn after_name(&self) -> Stage {
        if self.flags & FLAG_FCOMMENT != 0 {
            Stage::Comment(0)
        } else if self.flags & FLAG_FHCRC != 0 {
            Stage::FhcrcLo
        } else {
            Stage::Done
        }
    }

    fn after_comment(&self) -> Stage {
        if self.flags & FLAG_FHCRC != 0 {
            Stage::FhcrcLo
        } else {
            Stage::Done
        }
    }
}

/// 有界 gzip 解码器（不依赖会话表，单测可直接驱动）。
pub struct UnpackStream {
    phase: Phase,
    header: HeaderParser,
    decompress: Decompress,
    crc: Crc,
    size: u64,
    compressed_total: u64,
    buffer: Vec<u8>,
    start: usize,
    trailer: [u8; TRAILER_BYTES],
    trailer_len: usize,
    max_output: u64,
    max_input: u64,
}

impl Default for UnpackStream {
    fn default() -> Self {
        Self::new()
    }
}

impl UnpackStream {
    /// 新建解码器（累计输入/输出上限 `MAX_TOTAL_BYTES`）。
    pub fn new() -> Self {
        Self::with_limits(MAX_TOTAL_BYTES, MAX_TOTAL_BYTES)
    }

    fn with_limits(max_output: u64, max_input: u64) -> Self {
        Self {
            phase: Phase::Header,
            header: HeaderParser::new(),
            decompress: Decompress::new(false),
            crc: Crc::new(),
            size: 0,
            compressed_total: 0,
            buffer: Vec::with_capacity(MAX_INPUT_CHUNK),
            start: 0,
            trailer: [0u8; TRAILER_BYTES],
            trailer_len: 0,
            max_output,
            max_input,
        }
    }

    /// 是否已到达并核验完尾部（EOF）。
    pub fn is_eof(&self) -> bool {
        self.phase == Phase::Eof
    }

    /// 会话失败态（错误码）；未失败为 `None`。
    pub fn failure(&self) -> Option<i32> {
        match self.phase {
            Phase::Failed(code) => Some(code),
            _ => None,
        }
    }

    fn fail(&mut self, code: i32) {
        self.phase = Phase::Failed(code);
    }

    /// 错误可能发生在同一次 `read` 已产出部分明文之后：先把明文交给调用方，下一次
    /// `read` 再返回错误（与 `StrictGzipInputStream` 的“先交付已解压字节、下一轮再抛”一致）。
    fn deliver_or_fail(&mut self, code: i32, produced: usize) -> Result<(usize, u32), i32> {
        self.phase = Phase::Failed(code);
        if produced > 0 {
            Ok((produced, FLAG_NEED_OUTPUT))
        } else {
            Err(code)
        }
    }

    fn compact(&mut self) {
        if self.start == 0 {
            return;
        }
        if self.start >= self.buffer.len() {
            self.buffer.clear();
            self.start = 0;
            return;
        }
        self.buffer.copy_within(self.start.., 0);
        let remaining = self.buffer.len() - self.start;
        self.buffer.truncate(remaining);
        self.start = 0;
    }

    /// 喂入一块压缩字节；返回实际消费的字节数。
    pub fn write(&mut self, input: &[u8]) -> Result<usize, i32> {
        if input.is_empty() {
            return Err(ERR_INVALID_ARGUMENT);
        }
        if input.len() > MAX_INPUT_CHUNK {
            return Err(ERR_INPUT_BOUND);
        }
        match self.phase {
            Phase::Eof => {
                self.phase = Phase::Failed(ERR_GZIP_TRAILING_DATA);
                return Err(ERR_GZIP_TRAILING_DATA);
            }
            Phase::Failed(code) => return Err(code),
            _ => {}
        }
        // 输入上限按**实际接收**的 `take` 计，不按 `requested` 预拒绝：同一块里
        // 含尾随字节时，只有把 `take` 接收进来，native 才能在成员结束后用
        // `GZIP_TRAILING_DATA` 报错，而不是把正常尾随语料误报成 `INPUT_BOUND`
        // （R3 评审 P2-2）。缓冲满（`take == 0`）不消耗额度，返回 0 让调用方先读走输出。
        self.compact();
        let free = MAX_INPUT_CHUNK - self.buffer.len();
        let take = free.min(input.len());
        if self.compressed_total.saturating_add(take as u64) > self.max_input {
            self.phase = Phase::Failed(ERR_INPUT_BOUND);
            return Err(ERR_INPUT_BOUND);
        }
        self.buffer.extend_from_slice(&input[..take]);
        self.compressed_total += take as u64;
        Ok(take)
    }

    /// 取出解压字节；返回 `(produced, flags)`。
    pub fn read(&mut self, out: &mut [u8]) -> Result<(usize, u32), i32> {
        if out.is_empty() {
            return Err(ERR_INVALID_ARGUMENT);
        }
        if out.len() > MAX_OUTPUT_CHUNK {
            return Err(ERR_INVALID_ARGUMENT);
        }
        let mut produced = 0usize;
        loop {
            match self.phase {
                Phase::Failed(code) => return Err(code),
                Phase::Eof => return Ok((produced, FLAG_EOF)),
                Phase::Header => {
                    while self.phase == Phase::Header && self.start < self.buffer.len() {
                        let byte = self.buffer[self.start];
                        self.start += 1;
                        match self.header.step(byte) {
                            Ok(()) => {}
                            Err(code) => return self.deliver_or_fail(code, produced),
                        }
                        if self.header.done() {
                            self.phase = Phase::Body;
                        }
                    }
                    if self.phase == Phase::Header {
                        return Ok((produced, FLAG_NEED_INPUT));
                    }
                }
                Phase::Body => {
                    if produced == out.len() {
                        return Ok((produced, FLAG_NEED_OUTPUT));
                    }
                    let remaining_output = self.max_output.saturating_sub(self.size);
                    if remaining_output == 0 {
                        if produced > 0 {
                            // 先把上限内已产出的字节交给调用方，下一次调用再判定。
                            return Ok((produced, FLAG_NEED_OUTPUT));
                        }
                        // 恰好等于上限的合法流可能只是“结束块不产生字节”：先用空输出
                        // 推进一次，`StreamEnd` 时交给 Trailer 校验（评审 P2-1）。
                        if self.finish_exactly_at_output_bound() {
                            continue;
                        }
                        // 结束块字节仍可能留在调用方手里：空输入探针不可能得到
                        // `StreamEnd`（探针若已把缓冲消费空，也不能据此宣布超限）。
                        // 先报 `NEED_INPUT`，补喂后由下一轮区分 EOF 与硬上限
                        // （R3 评审最后一条 P2：结果不得随 write 分块方式变化）。
                        if self.start == self.buffer.len() {
                            return Ok((produced, FLAG_NEED_INPUT));
                        }
                        // 上限后确实还有明文：`OUTPUT_BOUND` 在 unpack 语境是**硬上限**
                        // （JVM 参考读取器为 `ARCHIVE_LIMIT`），会话进入失败态，不可重试。
                        return self.deliver_or_fail(ERR_OUTPUT_BOUND, produced);
                    }
                    let room = out.len() - produced;
                    let cap = if remaining_output < room as u64 {
                        remaining_output as usize
                    } else {
                        room
                    };
                    let before_in = self.decompress.total_in();
                    let before_out = self.decompress.total_out();
                    let status = {
                        let input = &self.buffer[self.start..];
                        match self.decompress.decompress(
                            input,
                            &mut out[produced..produced + cap],
                            FlushDecompress::None,
                        ) {
                            Ok(status) => status,
                            Err(error) => {
                                // raw deflate（`Decompress::new(false)`）没有 zlib 头的
                                // FDICT 位，`needs_dictionary()` 在 ABI v1 里是防御分支：
                                // 首期语料不可达，保留映射以固定失败域（见单测
                                // `defensive_gzip_codes_are_not_observed`）。
                                let code = if error.needs_dictionary().is_some() {
                                    ERR_GZIP_DICTIONARY
                                } else {
                                    ERR_GZIP_DEFLATE
                                };
                                self.phase = Phase::Failed(code);
                                return Err(code);
                            }
                        }
                    };
                    let consumed = (self.decompress.total_in() - before_in) as usize;
                    let made = (self.decompress.total_out() - before_out) as usize;
                    self.start += consumed;
                    self.size += made as u64;
                    self.crc.update(&out[produced..produced + made]);
                    produced += made;
                    match status {
                        Status::StreamEnd => self.phase = Phase::Trailer,
                        Status::Ok | Status::BufError => {
                            if made == 0 {
                                if self.start == self.buffer.len() {
                                    return Ok((produced, FLAG_NEED_INPUT));
                                }
                                if produced == out.len() {
                                    return Ok((produced, FLAG_NEED_OUTPUT));
                                }
                                // 有空闲输入/输出却零进展是防御分支：flate2 只在契约
                                // 被破坏时走到这里，首期语料不可达（见单测
                                // `defensive_gzip_codes_are_not_observed`）。
                                return self.deliver_or_fail(ERR_GZIP_STALLED, produced);
                            }
                        }
                    }
                }
                Phase::Trailer => {
                    while self.trailer_len < TRAILER_BYTES && self.start < self.buffer.len() {
                        self.trailer[self.trailer_len] = self.buffer[self.start];
                        self.trailer_len += 1;
                        self.start += 1;
                    }
                    if self.trailer_len < TRAILER_BYTES {
                        return Ok((produced, FLAG_NEED_INPUT));
                    }
                    let expected_crc = u32::from_le_bytes([
                        self.trailer[0],
                        self.trailer[1],
                        self.trailer[2],
                        self.trailer[3],
                    ]);
                    let expected_isize = u32::from_le_bytes([
                        self.trailer[4],
                        self.trailer[5],
                        self.trailer[6],
                        self.trailer[7],
                    ]);
                    if expected_crc != self.crc.sum() || expected_isize != self.size as u32 {
                        return self.deliver_or_fail(ERR_GZIP_TRAILER, produced);
                    }
                    if self.start < self.buffer.len() {
                        return self.deliver_or_fail(ERR_GZIP_TRAILING_DATA, produced);
                    }
                    self.phase = Phase::Eof;
                    return Ok((produced, FLAG_EOF));
                }
            }
        }
    }

    /// 累计输出恰好等于上限时，用**空输出**推进一次 deflate，判断流是否在此结束。
    ///
    /// deflate 的结束块可能不产生任何字节；若因为 `remaining_output == 0` 直接报
    /// `OUTPUT_BOUND`，恰好等于上限的合法流会被误判（R3 评审 P2-1）。空输出的
    /// `decompress` 只消费“结束块”标记（`StreamEnd` 时推进到 `Trailer`），不会新增
    /// 明文；其余状态一律不改变 phase。返回 false 且输入缓冲已空时**不能**判定超限：
    /// 结束块字节可能仍在调用方手里，调用处返回 `NEED_INPUT` 等补喂后再判定
    /// （R3 评审最后一条 P2）。
    fn finish_exactly_at_output_bound(&mut self) -> bool {
        let before_in = self.decompress.total_in();
        let before_out = self.decompress.total_out();
        let status = {
            let input = &self.buffer[self.start..];
            self.decompress
                .decompress(input, &mut [], FlushDecompress::None)
        };
        match status {
            Ok(Status::StreamEnd) => {
                let consumed = (self.decompress.total_in() - before_in) as usize;
                debug_assert_eq!((self.decompress.total_out() - before_out) as usize, 0);
                self.start += consumed;
                self.phase = Phase::Trailer;
                true
            }
            // `Ok(Status::Ok | Status::BufError)`：上限之后还有明文；`Err`：空输出下
            // zlib 只应报“无进展”，防御性地按“未结束”处理（仍以硬上限失败，
            // 不伪造 deflate 错误）。
            Ok(_) | Err(_) => false,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::abi::ERR_GZIP_TRUNCATED;

    const LEVELS: [i32; 4] = [0, 1, 6, 9];

    fn patterned(size: usize) -> Vec<u8> {
        let mut payload = vec![0u8; size];
        for (index, byte) in payload.iter_mut().enumerate() {
            *byte = ((index * 31 + 7) % 251) as u8;
        }
        payload
    }

    /// 不可压缩载荷：高熵但确定性，用于压测 deflate 最坏膨胀/待刷数据。
    fn incompressible(size: usize) -> Vec<u8> {
        let mut state = 0x2026_1114_0000_0001u64;
        let mut payload = vec![0u8; size];
        for byte in payload.iter_mut() {
            state = state
                .wrapping_mul(6_364_136_223_846_793_005)
                .wrapping_add(1_442_695_040_888_963_407);
            *byte = (state >> 33) as u8;
        }
        payload
    }

    fn pack_stream(payload: &[u8], level: i32, chunk: usize) -> Vec<u8> {
        let mut stream = match PackStream::new(level) {
            Ok(stream) => stream,
            Err(code) => panic!("level {level}: {code}"),
        };
        let mut gzip = Vec::new();
        let mut out = vec![0u8; FINISH_BOUND.max(pack_bound(chunk))];
        for part in payload.chunks(chunk) {
            let mut offset = 0usize;
            while offset < part.len() {
                let (consumed, produced) = match stream.update(&part[offset..], &mut out) {
                    Ok(value) => value,
                    Err(code) => panic!("update: {code}"),
                };
                assert!(produced <= out.len());
                assert!(consumed > 0 || produced > 0, "update 无进展");
                offset += consumed;
                gzip.extend_from_slice(&out[..produced]);
            }
        }
        let produced = match stream.finish(&mut out) {
            Ok(produced) => produced,
            Err(code) => panic!("finish: {code}"),
        };
        assert!(
            produced <= FINISH_BOUND,
            "finish 输出 {produced} B 超过 FINISH_BOUND"
        );
        gzip.extend_from_slice(&out[..produced]);
        gzip
    }

    fn unpack_stream(gzip: &[u8], feed: usize, read: usize) -> Result<Vec<u8>, i32> {
        let mut stream = UnpackStream::new();
        let mut payload = Vec::new();
        let mut out = vec![0u8; read];
        let mut offset = 0usize;
        loop {
            let mut blocked = false;
            if offset < gzip.len() {
                let end = (offset + feed).min(gzip.len());
                loop {
                    let consumed = stream.write(&gzip[offset..end])?;
                    if consumed == 0 {
                        blocked = true;
                        break;
                    }
                    offset += consumed;
                    if offset == end {
                        break;
                    }
                }
            }
            loop {
                let (produced, flags) = stream.read(&mut out)?;
                payload.extend_from_slice(&out[..produced]);
                if flags & FLAG_EOF != 0 {
                    return Ok(payload);
                }
                if produced == 0 {
                    break;
                }
            }
            if offset >= gzip.len() {
                if blocked {
                    return Err(ERR_INTERNAL);
                }
                // 调用方已没有更多压缩字节：native 层只能报“需要输入”，由 JVM 桥
                // 映射为 GZIP_TRUNCATED；测试辅助函数在这里用同一语义表示。
                return Err(-14);
            }
        }
    }

    fn unpack_ok(gzip: &[u8], feed: usize, read: usize) -> Vec<u8> {
        match unpack_stream(gzip, feed, read) {
            Ok(payload) => payload,
            Err(code) => panic!("unpack 失败: {code}"),
        }
    }

    fn with_extra(gzip: &[u8], extra: &[u8]) -> Vec<u8> {
        assert!(extra.len() <= u16::MAX as usize);
        let mut out = gzip[..HEADER_BYTES].to_vec();
        out[3] |= FLAG_FEXTRA;
        out.extend_from_slice(&(extra.len() as u16).to_le_bytes());
        out.extend_from_slice(extra);
        out.extend_from_slice(&gzip[HEADER_BYTES..]);
        out
    }

    fn with_name(gzip: &[u8], name: &[u8]) -> Vec<u8> {
        let mut out = gzip[..HEADER_BYTES].to_vec();
        out[3] |= FLAG_FNAME;
        out.extend_from_slice(name);
        out.push(0);
        out.extend_from_slice(&gzip[HEADER_BYTES..]);
        out
    }

    fn with_comment(gzip: &[u8], comment: &[u8]) -> Vec<u8> {
        let mut out = gzip[..HEADER_BYTES].to_vec();
        out[3] |= FLAG_FCOMMENT;
        out.extend_from_slice(comment);
        out.push(0);
        out.extend_from_slice(&gzip[HEADER_BYTES..]);
        out
    }

    fn with_fhcrc(gzip: &[u8]) -> Vec<u8> {
        let mut header = gzip[..HEADER_BYTES].to_vec();
        header[3] |= FLAG_FHCRC;
        let mut crc = Crc::new();
        crc.update(&header);
        let mut out = header;
        out.extend_from_slice(&(crc.sum() as u16).to_le_bytes());
        out.extend_from_slice(&gzip[HEADER_BYTES..]);
        out
    }

    /// FEXTRA + FNAME + FCOMMENT + FHCRC 同时存在（RFC 1952 顺序 4/8/16/2）。
    fn with_all_optional(gzip: &[u8]) -> Vec<u8> {
        let mut header = gzip[..HEADER_BYTES].to_vec();
        header[3] |= FLAG_FEXTRA | FLAG_FNAME | FLAG_FCOMMENT | FLAG_FHCRC;
        let mut out = header;
        out.extend_from_slice(&2u16.to_le_bytes());
        out.extend_from_slice(&[0xaa, 0xbb]);
        out.extend_from_slice(b"name.bin");
        out.push(0);
        out.extend_from_slice(b"comment");
        out.push(0);
        let mut crc = Crc::new();
        crc.update(&out);
        out.extend_from_slice(&(crc.sum() as u16).to_le_bytes());
        out.extend_from_slice(&gzip[HEADER_BYTES..]);
        out
    }

    #[test]
    fn pack_bound_covers_every_level_and_chunk() {
        for level in LEVELS {
            for size in [1usize, 2, 15, 1_024, 65_535, MAX_INPUT_CHUNK] {
                let payload = incompressible(size);
                for chunk in [1usize, 4_096, MAX_INPUT_CHUNK] {
                    let gzip = pack_stream(&payload, level, chunk);
                    assert!(gzip.len() > payload.len());
                    // 头部 + 尾部 + 最坏膨胀都必须在 `pack_bound(整段)` 内。
                    assert!(
                        gzip.len() <= pack_bound(size) + TRAILER_BYTES,
                        "level={level} size={size} gzip={} bound={}",
                        gzip.len(),
                        pack_bound(size)
                    );
                }
            }
        }
    }

    #[test]
    fn round_trip_is_chunk_invariant() {
        for level in LEVELS {
            for size in [0usize, 1, 17, 4_096, 65_536, MAX_INPUT_CHUNK] {
                let payload = patterned(size);
                let gzip = pack_stream(&payload, level, 7_919);
                for feed in [1usize, 3, 4_096, 65_535] {
                    for read in [1usize, 5, 4_096, MAX_OUTPUT_CHUNK] {
                        assert_eq!(
                            unpack_ok(&gzip, feed, read),
                            payload,
                            "level={level} size={size} feed={feed} read={read}"
                        );
                    }
                }
            }
        }
    }

    #[test]
    fn empty_payload_pack_emits_header_and_valid_trailer() {
        let gzip = pack_stream(&[], 6, MAX_INPUT_CHUNK);
        assert_eq!(&gzip[..HEADER_BYTES], &GZIP_HEADER);
        assert_eq!(gzip.len(), HEADER_BYTES + 2 + TRAILER_BYTES);
        // 空载荷的 deflate 最终块：固定头之后、尾部之前只有合法的结束块。
        assert!(unpack_ok(&gzip, 1, 1).is_empty());
        let crc = Crc::new();
        let isize = u32::from_le_bytes([
            gzip[gzip.len() - 4],
            gzip[gzip.len() - 3],
            gzip[gzip.len() - 2],
            gzip[gzip.len() - 1],
        ]);
        assert_eq!(isize, 0);
        assert_eq!(
            u32::from_le_bytes([
                gzip[gzip.len() - 8],
                gzip[gzip.len() - 7],
                gzip[gzip.len() - 6],
                gzip[gzip.len() - 5]
            ]),
            crc.sum()
        );
    }

    #[test]
    fn update_and_finish_output_bound_are_retryable_without_state_change() {
        let payload = incompressible(MAX_INPUT_CHUNK);
        let mut stream = match PackStream::new(6) {
            Ok(stream) => stream,
            Err(code) => panic!("{code}"),
        };
        let mut small = vec![0u8; pack_bound(payload.len()) - 1];
        assert_eq!(stream.update(&payload, &mut small), Err(ERR_OUTPUT_BOUND));
        // 失败不消费输入：换足够大的缓冲重试，结果与一次性编码字节一致。
        let mut out = vec![0u8; pack_bound(payload.len())];
        let (consumed, produced) = match stream.update(&payload, &mut out) {
            Ok(value) => value,
            Err(code) => panic!("{code}"),
        };
        assert_eq!(consumed, payload.len());
        let mut gzip = out[..produced].to_vec();
        let mut finish_small = vec![0u8; FINISH_BOUND - 1];
        assert_eq!(stream.finish(&mut finish_small), Err(ERR_OUTPUT_BOUND));
        let mut finish = vec![0u8; FINISH_BOUND];
        let tail = match stream.finish(&mut finish) {
            Ok(produced) => produced,
            Err(code) => panic!("{code}"),
        };
        gzip.extend_from_slice(&finish[..tail]);
        assert_eq!(gzip, pack_stream(&payload, 6, payload.len()));
    }

    #[test]
    fn header_metadata_variants_match_the_jvm_reader_contract() {
        let payload = patterned(1_000);
        let base = pack_stream(&payload, 6, MAX_INPUT_CHUNK);

        // FEXTRA：JVM 读取器接受 u16 长度（不额外限制 8192），Rust 必须一致。
        for extra in [
            vec![],
            vec![0u8; 1],
            vec![0x5a; 8_192],
            vec![0xa5; 8_193],
            vec![0x11; 65_535],
        ] {
            let gzip = with_extra(&base, &extra);
            assert_eq!(unpack_ok(&gzip, 7, 64), payload, "extra={}", extra.len());
        }

        // FNAME / FCOMMENT：终止 0 在内，最多 8192 字节。
        for length in [0usize, 1, 8_191] {
            let gzip = with_name(&base, &vec![b'n'; length]);
            assert_eq!(unpack_ok(&gzip, 7, 64), payload, "fname={length}");
            let gzip = with_comment(&base, &vec![b'c'; length]);
            assert_eq!(unpack_ok(&gzip, 7, 64), payload, "fcomment={length}");
        }
        // 8191 字符 + 终止 0 = 8192 字节：合法；8192 字符无 0：METADATA_LIMIT。
        let limit = with_comment(&base, &vec![b'c'; MAX_METADATA]);
        assert_eq!(
            unpack_stream(&limit, MAX_METADATA, 64),
            Err(ERR_GZIP_METADATA_LIMIT)
        );
        let too_long = with_name(&base, &vec![b'n'; MAX_METADATA]);
        assert_eq!(
            unpack_stream(&too_long, 7, 64),
            Err(ERR_GZIP_METADATA_LIMIT)
        );

        // FHCRC：正确值通过；篡改一个 bit → GZIP_HEADER_CRC。
        let fhcrc = with_fhcrc(&base);
        assert_eq!(unpack_ok(&fhcrc, 7, 64), payload);
        let mut broken = fhcrc.clone();
        let crc_offset = HEADER_BYTES;
        broken[crc_offset] ^= 0x01;
        assert_eq!(unpack_stream(&broken, 7, 64), Err(ERR_GZIP_HEADER_CRC));

        // 组合：FEXTRA + FNAME + FCOMMENT + FHCRC 全部同时存在（顺序 4/8/16/2）。
        let combined = with_all_optional(&base);
        assert_eq!(unpack_ok(&combined, 7, 64), payload);
    }

    #[test]
    fn header_errors_are_typed_and_do_not_become_truncation() {
        let base = pack_stream(b"header", 6, MAX_INPUT_CHUNK);

        let mut magic = base.clone();
        magic[0] = 0x1e;
        assert_eq!(unpack_stream(&magic, 3, 16), Err(ERR_GZIP_HEADER));

        let mut method = base.clone();
        method[2] = 0x07;
        assert_eq!(unpack_stream(&method, 3, 16), Err(ERR_GZIP_HEADER));

        let mut reserved = base.clone();
        reserved[3] |= 0x20;
        assert_eq!(unpack_stream(&reserved, 3, 16), Err(ERR_GZIP_HEADER));

        // 截断在每个前缀：native 只报“需要输入”，不会误报头错误。
        for length in 0..base.len() {
            let prefix = &base[..length];
            let mut stream = UnpackStream::new();
            let mut out = vec![0u8; 16];
            if !prefix.is_empty() {
                let consumed = match stream.write(prefix) {
                    Ok(value) => value,
                    Err(code) => panic!("write: {code}"),
                };
                assert_eq!(consumed, prefix.len());
            }
            match stream.read(&mut out) {
                Ok((_produced, flags)) => {
                    // 头/体被截断时可能已经产出部分明文；唯一要求是“还缺输入”，
                    // 不能把截断误报成头/尾/deflate 错误。
                    assert_eq!(flags & FLAG_NEED_INPUT, FLAG_NEED_INPUT);
                }
                Err(code) => panic!("截断前缀 {length} 误报错误 {code}"),
            }
        }
    }

    #[test]
    fn trailer_and_deflate_errors_match_the_frozen_codes() {
        let payload = patterned(4_096);
        let base = pack_stream(&payload, 6, MAX_INPUT_CHUNK);

        let mut bad_crc = base.clone();
        let crc_offset = bad_crc.len() - TRAILER_BYTES;
        bad_crc[crc_offset] ^= 0xff;
        assert_eq!(unpack_stream(&bad_crc, 7, 1_024), Err(ERR_GZIP_TRAILER));

        let mut bad_isize = base.clone();
        let isize_offset = bad_isize.len() - 4;
        bad_isize[isize_offset] ^= 0xff;
        assert_eq!(unpack_stream(&bad_isize, 7, 1_024), Err(ERR_GZIP_TRAILER));

        let mut trailing = base.clone();
        trailing.push(0);
        assert_eq!(
            unpack_stream(&trailing, 7, 1_024),
            Err(ERR_GZIP_TRAILING_DATA)
        );

        let mut multi = base.clone();
        multi.extend_from_slice(&pack_stream(b"second", 6, MAX_INPUT_CHUNK));
        assert_eq!(unpack_stream(&multi, 7, 1_024), Err(ERR_GZIP_TRAILING_DATA));

        // 非法 deflate：固定头 + 全 0xff 的 deflate 数据。
        let mut invalid = GZIP_HEADER.to_vec();
        invalid.extend_from_slice(&[0xff; 32]);
        assert_eq!(unpack_stream(&invalid, 7, 1_024), Err(ERR_GZIP_DEFLATE));
    }

    #[test]
    fn deferred_trailing_byte_is_rejected_after_eof() {
        let payload = patterned(64);
        let base = pack_stream(&payload, 6, MAX_INPUT_CHUNK);
        let mut stream = UnpackStream::new();
        let mut out = vec![0u8; 128];
        let consumed = match stream.write(&base) {
            Ok(value) => value,
            Err(code) => panic!("{code}"),
        };
        assert_eq!(consumed, base.len());
        loop {
            match stream.read(&mut out) {
                Ok((_produced, flags)) => {
                    if flags & FLAG_EOF != 0 {
                        break;
                    }
                }
                Err(code) => panic!("{code}"),
            }
        }
        assert!(stream.is_eof());
        // EOF 之后仍有字节 = 尾随数据（JVM 读取器读到底层多一个字节也一样拒绝）。
        assert_eq!(stream.write(&[0x00]), Err(ERR_GZIP_TRAILING_DATA));
    }

    #[test]
    fn cumulative_limits_are_enforced_without_unbounded_growth() {
        let payload = patterned(4_096);
        let gzip = pack_stream(&payload, 6, MAX_INPUT_CHUNK);

        // 输出累计上限：把上限设成 100 字节，合法流在产出超过上限后失败。
        let mut limited = UnpackStream::with_limits(100, MAX_TOTAL_BYTES);
        let consumed = match limited.write(&gzip) {
            Ok(value) => value,
            Err(code) => panic!("{code}"),
        };
        assert_eq!(consumed, gzip.len());
        let mut out = vec![0u8; 64];
        let mut produced = 0usize;
        let error = loop {
            match limited.read(&mut out) {
                Ok((made, flags)) => {
                    produced += made;
                    if flags & FLAG_EOF != 0 {
                        panic!("输出上限没有生效");
                    }
                    if made == 0 {
                        panic!("read 无进展");
                    }
                }
                Err(code) => break code,
            }
        };
        assert_eq!((error, produced), (ERR_OUTPUT_BOUND, 100));
        assert_eq!(limited.failure(), Some(ERR_OUTPUT_BOUND));

        // 压缩输入累计上限：上限小于流长度 → INPUT_BOUND。
        let mut input_limited = UnpackStream::with_limits(MAX_TOTAL_BYTES, 16);
        assert_eq!(input_limited.write(&gzip[..8]), Ok(8));
        assert_eq!(input_limited.write(&gzip[8..]), Err(ERR_INPUT_BOUND));
        assert_eq!(input_limited.failure(), Some(ERR_INPUT_BOUND));
    }

    /// 在给定累计输出上限下解完整个流：返回（已产出字节, 最终结果）。
    fn unpack_with_output_limit(gzip: &[u8], max_output: u64) -> (Vec<u8>, Result<(), i32>) {
        let mut stream = UnpackStream::with_limits(max_output, MAX_TOTAL_BYTES);
        let mut payload = Vec::new();
        let mut out = vec![0u8; 97];
        let mut offset = 0usize;
        loop {
            while offset < gzip.len() {
                let consumed = match stream.write(&gzip[offset..]) {
                    Ok(value) => value,
                    Err(code) => return (payload, Err(code)),
                };
                if consumed == 0 {
                    break;
                }
                offset += consumed;
            }
            match stream.read(&mut out) {
                Ok((made, flags)) => {
                    payload.extend_from_slice(&out[..made]);
                    if flags & FLAG_EOF != 0 {
                        return (payload, Ok(()));
                    }
                    if made == 0 {
                        // 调用方已经喂完全部压缩字节，native 仍要输入 → 截断。
                        return (payload, Err(ERR_GZIP_TRUNCATED));
                    }
                }
                Err(code) => return (payload, Err(code)),
            }
        }
    }

    #[test]
    fn exact_output_limit_succeeds_and_one_byte_short_fails() {
        // 正例（评审 P2-1）：上限恰好等于明文长度（以及多 1 字节）时，合法流必须 EOF。
        // 覆盖空载荷（上限 0：结束块不产生字节）与整块边界。
        for payload in [
            Vec::new(),
            patterned(1),
            patterned(4_096),
            patterned(MAX_INPUT_CHUNK),
        ] {
            let gzip = pack_stream(&payload, 6, 7_919);
            for max_output in [payload.len() as u64, payload.len() as u64 + 1] {
                let (bytes, result) = unpack_with_output_limit(&gzip, max_output);
                assert_eq!(
                    result,
                    Ok(()),
                    "payload={} limit={max_output}",
                    payload.len()
                );
                assert_eq!(
                    bytes,
                    payload,
                    "payload={} limit={max_output}",
                    payload.len()
                );
            }
        }

        // 反例：上限比明文少 1 字节 → 先交付上限内字节，下一次 read 报硬上限；
        // 该错误在 unpack 语境对应 JVM 的 ARCHIVE_LIMIT，不可当作“缓冲不足重试”。
        let payload = patterned(4_096);
        let gzip = pack_stream(&payload, 6, MAX_INPUT_CHUNK);
        let (bytes, result) = unpack_with_output_limit(&gzip, payload.len() as u64 - 1);
        assert_eq!(result, Err(ERR_OUTPUT_BOUND));
        assert_eq!(bytes.len(), payload.len() - 1);
        assert_eq!(bytes, payload[..payload.len() - 1]);
        // 上限 0 且明文非空同样立即失败。
        let (bytes, result) = unpack_with_output_limit(&gzip, 0);
        assert_eq!((bytes.len(), result), (0, Err(ERR_OUTPUT_BOUND)));
    }

    /// 评审最后 1 条 P2 辅助：先只喂 `gzip[..split]`，读到“零进展”后补喂剩余字节。
    /// 返回（累计明文, 首次零进展的 flags, 首次零进展时已产出字节, 最终结果）。
    fn unpack_with_split_output_limit(
        gzip: &[u8],
        split: usize,
        max_output: u64,
    ) -> (Vec<u8>, u32, usize, Result<(), i32>) {
        assert!(split < gzip.len(), "split 必须留出补喂的尾部");
        let mut stream = UnpackStream::with_limits(max_output, MAX_TOTAL_BYTES);
        let mut payload = Vec::new();
        let mut out = vec![0u8; 97];
        match stream.write(&gzip[..split]) {
            Ok(consumed) => assert_eq!(consumed, split),
            Err(code) => return (payload, 0, 0, Err(code)),
        }
        let (stall_flags, stall_produced) = loop {
            match stream.read(&mut out) {
                Ok((made, flags)) => {
                    payload.extend_from_slice(&out[..made]);
                    if flags & FLAG_EOF != 0 {
                        let produced = payload.len();
                        return (payload, 0, produced, Ok(()));
                    }
                    if made == 0 {
                        break (flags, payload.len());
                    }
                }
                Err(code) => {
                    let produced = payload.len();
                    return (payload, 0, produced, Err(code));
                }
            }
        };
        let mut offset = split;
        loop {
            while offset < gzip.len() {
                let consumed = match stream.write(&gzip[offset..]) {
                    Ok(value) => value,
                    Err(code) => return (payload, stall_flags, stall_produced, Err(code)),
                };
                if consumed == 0 {
                    break;
                }
                offset += consumed;
            }
            match stream.read(&mut out) {
                Ok((made, flags)) => {
                    payload.extend_from_slice(&out[..made]);
                    if flags & FLAG_EOF != 0 {
                        return (payload, stall_flags, stall_produced, Ok(()));
                    }
                    if made == 0 {
                        // 尾部已全部喂入仍要输入：合法流不该发生。
                        return (payload, stall_flags, stall_produced, Err(ERR_INTERNAL));
                    }
                }
                Err(code) => return (payload, stall_flags, stall_produced, Err(code)),
            }
        }
    }

    #[test]
    fn exact_output_limit_succeeds_when_tail_is_fed_after_need_input() {
        // 评审最后 1 条 P2：上限恰好等于明文长度时，deflate 结束块字节可能仍在
        // 调用方手里。旧实现用空输入探针必然拿不到 `StreamEnd`，把“还要输入”
        // 误报成硬上限 `OUTPUT_BOUND`，结果取决于 write 分块方式（违反方案 §4.5）。
        // 先喂到最后 1 个 deflate 字节前：读到上限后必须是 `NEED_INPUT`（且已产出
        // 恰好明文长度），补喂尾部后必须 EOF。
        let payload = patterned(200_000);
        let gzip = pack_stream(&payload, 6, MAX_INPUT_CHUNK);
        let deflate_end = gzip.len() - TRAILER_BYTES;
        assert!(deflate_end > 16);

        let (bytes, flags, stall_produced, result) =
            unpack_with_split_output_limit(&gzip, deflate_end - 1, payload.len() as u64);
        assert_eq!(
            result,
            Ok(()),
            "恰好等于上限的合法流不得因 write 分块误报 -3"
        );
        assert_eq!(
            flags, FLAG_NEED_INPUT,
            "缓冲耗尽时必须要求输入，而不是硬上限"
        );
        assert_eq!(stall_produced, payload.len());
        assert_eq!(bytes, payload);

        // 结束块/尾部逐字节切分以及上游分块点：补喂后结果必须一致（分配不变）。
        // 首次零进展可能早于上限（普通缺输入）或恰好在上限（结束块未到），
        // 两者都必须是 NEED_INPUT；上限处的精确断言见上面的主场景。
        for split in (deflate_end - 16..gzip.len()).chain([1, 9, HEADER_BYTES]) {
            let (bytes, flags, _stall_produced, result) =
                unpack_with_split_output_limit(&gzip, split, payload.len() as u64);
            assert_eq!(result, Ok(()), "split={split}");
            assert_eq!(bytes, payload, "split={split}");
            assert_eq!(flags, FLAG_NEED_INPUT, "split={split}");
        }

        // 明文比上限多 1 字节仍必须报 -3（不漏判）：整段喂入与分块补喂两条路径。
        let limit = payload.len() as u64 - 1;
        let (bytes, result) = unpack_with_output_limit(&gzip, limit);
        assert_eq!(
            (bytes.len(), result),
            (payload.len() - 1, Err(ERR_OUTPUT_BOUND))
        );
        for split in [deflate_end - 1, deflate_end, gzip.len() - 1] {
            let (bytes, flags, _stall_produced, result) =
                unpack_with_split_output_limit(&gzip, split, limit);
            assert_eq!(
                result,
                Err(ERR_OUTPUT_BOUND),
                "split={split} flags={flags} 不得漏判超限"
            );
            assert_eq!(bytes.len(), payload.len() - 1, "split={split}");
        }
    }

    #[test]
    fn input_bound_counts_accepted_bytes_and_keeps_trailing_detection() {
        // 评审 P2-2：输入上限必须按实际接收的 `take` 计。先填满 native 输入缓冲的
        // 前 L-1 字节，再喂“成员最后 1 字节 + 超过剩余缓冲的尾随字节”：请求长度
        // requested > 上限余量，但 take 恰好在上限内。旧实现按 requested 误报
        // INPUT_BOUND；修复后只接收 take，native 看到尾随字节应报 GZIP_TRAILING_DATA。
        let payload = incompressible(MAX_INPUT_CHUNK - 1_024);
        let gzip = pack_stream(&payload, 0, MAX_INPUT_CHUNK);
        assert!(
            gzip.len() < MAX_INPUT_CHUNK,
            "level 0 gzip 长度 {} 超出测试前提",
            gzip.len()
        );
        let mut stream = UnpackStream::with_limits(MAX_TOTAL_BYTES, MAX_INPUT_CHUNK as u64);
        let prefix = &gzip[..gzip.len() - 1];
        assert_eq!(stream.write(prefix), Ok(prefix.len()));
        let free = MAX_INPUT_CHUNK - prefix.len();
        assert!(free >= 2);
        // 第二块 = 成员最后 1 字节 + (free + 1) 个尾随字节：requested = free + 2，
        // take = free；接收的 free 字节里含 1 个尾随字节。
        let mut second = vec![0u8; free + 2];
        second[0] = gzip[gzip.len() - 1];
        assert_eq!(stream.write(&second), Ok(free));
        assert_eq!(
            stream.compressed_total, MAX_INPUT_CHUNK as u64,
            "累计输入必须正好等于上限"
        );

        let mut out = vec![0u8; 4_096];
        let mut produced = 0usize;
        let error = loop {
            match stream.read(&mut out) {
                Ok((made, flags)) => {
                    produced += made;
                    assert_eq!(flags & FLAG_EOF, 0, "尾随字节必须阻止 EOF");
                    assert!(made > 0, "read 无进展");
                }
                Err(code) => break code,
            }
        };
        assert_eq!(error, ERR_GZIP_TRAILING_DATA);
        assert_eq!(produced, payload.len());
        assert_eq!(stream.failure(), Some(ERR_GZIP_TRAILING_DATA));
    }

    #[test]
    fn optional_header_prefix_truncation_is_need_input() {
        // 评审 P2-9：可选头（FEXTRA/FNAME/FCOMMENT/FHCRC）逐前缀截断都必须表现为
        // “还需要输入”，不能误报头/尾/deflate 错误。
        let payload = patterned(512);
        let base = pack_stream(&payload, 6, MAX_INPUT_CHUNK);
        let mixed = with_all_optional(&base);
        for length in 0..mixed.len() {
            let mut stream = UnpackStream::new();
            let mut out = vec![0u8; 64];
            let prefix = &mixed[..length];
            if !prefix.is_empty() {
                let consumed = match stream.write(prefix) {
                    Ok(value) => value,
                    Err(code) => panic!("prefix={length} write: {code}"),
                };
                assert_eq!(consumed, prefix.len());
            }
            loop {
                match stream.read(&mut out) {
                    Ok((produced, flags)) => {
                        if flags & FLAG_NEED_INPUT != 0 {
                            break;
                        }
                        assert_eq!(
                            flags & FLAG_NEED_OUTPUT,
                            FLAG_NEED_OUTPUT,
                            "prefix={length} 非 NEED_INPUT/NEED_OUTPUT: {flags}"
                        );
                        assert!(produced > 0, "prefix={length} NEED_OUTPUT 无进展");
                    }
                    Err(code) => panic!("prefix={length} 误报错误 {code}"),
                }
            }
        }
        // 完整流仍然合法，证伪“逐前缀用例把语料本身弄坏”。
        assert_eq!(unpack_ok(&mixed, 7, 64), payload);
    }

    #[test]
    fn defensive_gzip_codes_are_not_observed() {
        // 可达性说明（评审 P2-9）：`GZIP_DICTIONARY` 需要 zlib FDICT 位，而本 ABI 只解
        // raw deflate（`Decompress::new(false)`）；`GZIP_STALLED` 是 flate2 契约被破坏时
        // 的防御分支。首期语料两者都不可达；用合法流 + 随机破坏 + 随机字节证伪，
        // 避免把防御分支写成“已被语料覆盖”。
        let base = pack_stream(&patterned(2_048), 6, MAX_INPUT_CHUNK);
        let mut state = 0x2026_1114_0000_0003u64;
        for _ in 0..512 {
            state = state
                .wrapping_mul(6_364_136_223_846_793_005)
                .wrapping_add(1_442_695_040_888_963_407);
            let mut view = base.clone();
            let index = (state as usize) % view.len();
            view[index] ^= ((state >> 32) % 255 + 1) as u8;
            let (_, result) = unpack_with_output_limit(&view, MAX_TOTAL_BYTES);
            if let Err(code) = result {
                assert_ne!(code, ERR_GZIP_DICTIONARY);
                assert_ne!(code, ERR_GZIP_STALLED);
            }
        }
        for length in 0..64usize {
            let mut view = Vec::with_capacity(length);
            for _ in 0..length {
                state = state
                    .wrapping_mul(6_364_136_223_846_793_005)
                    .wrapping_add(1_442_695_040_888_963_407);
                view.push((state >> 40) as u8);
            }
            let (_, result) = unpack_with_output_limit(&view, MAX_TOTAL_BYTES);
            if let Err(code) = result {
                assert_ne!(code, ERR_GZIP_DICTIONARY);
                assert_ne!(code, ERR_GZIP_STALLED);
            }
        }
    }

    #[test]
    fn random_corruption_never_panics_and_is_deterministic() {
        let base = pack_stream(&patterned(1_024), 6, MAX_INPUT_CHUNK);
        let mut state = 0x2026_1114_0000_0002u64;
        for _ in 0..256 {
            state = state
                .wrapping_mul(6_364_136_223_846_793_005)
                .wrapping_add(1_442_695_040_888_963_407);
            let mut view = base.clone();
            let index = (state as usize) % view.len();
            view[index] ^= ((state >> 32) % 255 + 1) as u8;
            let first = unpack_stream(&view, 7, 128);
            let second = unpack_stream(&view, 7, 128);
            assert_eq!(first, second);
        }
        for _ in 0..256 {
            state = state
                .wrapping_mul(6_364_136_223_846_793_005)
                .wrapping_add(1_442_695_040_888_963_407);
            let length = (state as usize) % 64;
            let mut view = Vec::with_capacity(length);
            for _ in 0..length {
                state = state
                    .wrapping_mul(6_364_136_223_846_793_005)
                    .wrapping_add(1_442_695_040_888_963_407);
                view.push((state >> 40) as u8);
            }
            assert_eq!(unpack_stream(&view, 5, 16), unpack_stream(&view, 5, 16));
        }
    }

    /// 会话表测试会互相影响，用进程内互斥串行（不依赖测试线程模型）。
    static SESSION_TESTS: Mutex<()> = Mutex::new(());

    fn session_guard() -> MutexGuard<'static, ()> {
        match SESSION_TESTS.lock() {
            Ok(guard) => guard,
            Err(poisoned) => poisoned.into_inner(),
        }
    }

    fn clear_sessions() {
        lock_sessions().clear();
    }

    #[test]
    fn session_lifecycle_rejects_unknown_wrong_kind_and_excess_sessions() {
        let _guard = session_guard();
        clear_sessions();

        assert_eq!(pack_create(-1), Err(ERR_INVALID_ARGUMENT));
        assert_eq!(pack_create(10), Err(ERR_INVALID_ARGUMENT));
        let pack = match pack_create(DEFAULT_LEVEL) {
            Ok(handle) => handle,
            Err(code) => panic!("{code}"),
        };
        let unpack = match unpack_create() {
            Ok(handle) => handle,
            Err(code) => panic!("{code}"),
        };
        assert_eq!(unpack_write(pack, b"x"), Err(ERR_STALE_HANDLE));
        assert_eq!(pack_abort(unpack), Err(ERR_STALE_HANDLE));
        assert_eq!(unpack_abort(pack), Err(ERR_STALE_HANDLE));
        assert_eq!(unpack_read(0, &mut [0u8; 8]), Err(ERR_STALE_HANDLE));

        // 会话上限 4：已有 2 个，再创建 2 个后第 5 个被拒绝。
        let second = match pack_create(6) {
            Ok(handle) => handle,
            Err(code) => panic!("{code}"),
        };
        let third = match unpack_create() {
            Ok(handle) => handle,
            Err(code) => panic!("{code}"),
        };
        assert_eq!(unpack_create(), Err(ERR_SESSION_LIMIT));
        assert_eq!(pack_abort(pack), Ok(0));
        assert_eq!(pack_abort(second), Ok(0));
        assert_eq!(unpack_abort(unpack), Ok(0));
        assert_eq!(unpack_abort(third), Ok(0));
        assert_eq!(pack_abort(pack), Err(ERR_STALE_HANDLE));
    }

    #[test]
    fn session_pack_and_unpack_round_trip_through_the_table() {
        let _guard = session_guard();
        clear_sessions();

        let payload = patterned(3 * MAX_INPUT_CHUNK / 4 + 13);
        let handle = match pack_create(9) {
            Ok(handle) => handle,
            Err(code) => panic!("{code}"),
        };
        let mut gzip = Vec::new();
        let mut out = vec![0u8; pack_bound(MAX_INPUT_CHUNK)];
        for part in payload.chunks(MAX_INPUT_CHUNK) {
            let value = match pack_update(handle, part, &mut out) {
                Ok(value) => value,
                Err(code) => panic!("{code}"),
            };
            let produced = (value & 0xffff_ffff) as usize;
            let consumed = (value >> 32) as usize;
            assert_eq!(consumed, part.len());
            gzip.extend_from_slice(&out[..produced]);
        }
        let mut finish = vec![0u8; FINISH_BOUND];
        let tail = match pack_finish(handle, &mut finish) {
            Ok(produced) => produced as usize,
            Err(code) => panic!("{code}"),
        };
        gzip.extend_from_slice(&finish[..tail]);
        assert_eq!(pack_abort(handle), Err(ERR_STALE_HANDLE));
        assert_eq!(pack_finish(handle, &mut finish), Err(ERR_STALE_HANDLE));

        let decode = match unpack_create() {
            Ok(handle) => handle,
            Err(code) => panic!("{code}"),
        };
        let mut decoded = Vec::new();
        let mut chunk = vec![0u8; 4_096];
        let mut offset = 0usize;
        loop {
            let mut progressed = false;
            while offset < gzip.len() {
                let consumed = match unpack_write(decode, &gzip[offset..]) {
                    Ok(consumed) => consumed as usize,
                    Err(code) => panic!("{code}"),
                };
                if consumed == 0 {
                    break;
                }
                offset += consumed;
                progressed = true;
            }
            loop {
                let value = match unpack_read(decode, &mut chunk) {
                    Ok(value) => value,
                    Err(code) => panic!("{code}"),
                };
                let produced = (value >> 32) as usize;
                let flags = value as u32;
                decoded.extend_from_slice(&chunk[..produced]);
                if flags & FLAG_EOF != 0 {
                    break;
                }
                if produced == 0 {
                    break;
                }
                progressed = true;
            }
            if offset == gzip.len() && decoded.len() == payload.len() {
                break;
            }
            if !progressed {
                panic!("会话没有进展");
            }
        }
        assert_eq!(decoded, payload);
        assert_eq!(unpack_abort(decode), Ok(0));
    }
}

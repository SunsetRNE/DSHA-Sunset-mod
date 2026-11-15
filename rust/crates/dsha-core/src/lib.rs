//! DSHA Rust 旁路 crate。
//!
//! 首期只允许三类封闭纯函数（ADR-0004 决定四）：单成员 gzip 编解码、只读摘要、
//! 计划只读校验。本 crate **不接收路径字符串、不打开文件（唯一的 fd 访问是由
//! Kotlin 打开并核验的只读 `pread`）、不创建进程/线程、不安装信号处理器、
//! 不写 stdout/stderr/logcat、不联网**。
//!
//! R0 冻结 ABI 骨架；R1 接 `digest`（有界只读 SHA-256/SHA-1）；R2 接
//! `verify_plan`（PlanView v1 只读校验）；R3 接 `archive_pack`/`archive_unpack`
//! （单成员 RFC 1952 gzip 流式编解码）。`abiVersion` 恒为 1；ABI 版本、符号名、
//! 错误码与上限在 R0 冻结（R1–R3 不得改；变化 = 新 ABI 版本 + 新评审）。
//! 本 crate 仍不接入任何生产调用点（A3 接线由 Lead 串行安排）。

#![deny(unsafe_code)]
#![deny(unsafe_op_in_unsafe_fn)]
#![warn(unused_must_use)]

pub mod abi;
pub mod digest;
pub mod gzip;
pub mod plan;

// 唯一允许 `unsafe` 的两个边界（ADR-0004 决定四 / 方案 §4.8）：
// - `jni`：JNI 胶水（R1 起调用 GetDirectBufferAddress/Capacity + ExceptionCheck）；
// - `fd`：只读 fd 读取（pread；不 open/close、不接收路径）。
// 其余模块必须是 `#![forbid(unsafe_code)]`，由 tools/check-rust-boundaries.py 核对。
#[allow(unsafe_code)]
mod fd;

#[allow(unsafe_code)]
mod jni;

#[cfg(test)]
mod tests {
    use crate::abi::{
        ABI_VERSION, ERR_GZIP_DEFLATE, ERR_GZIP_HEADER, ERR_GZIP_TRAILER, ERR_INTERNAL,
        ERR_INVALID_ARGUMENT, ERR_JNI_PENDING, ERR_NOT_IMPLEMENTED, ERR_OK, ERR_OUTPUT_BOUND,
        ERR_PANIC, ERR_PLAN_EXPIRED, ERR_PLAN_FIELD_RANGE, ERR_PLAN_STRUCTURE, ERR_SESSION_LIMIT,
        ERR_SHORT_READ, ERR_STALE_HANDLE, ERR_UNSUPPORTED_ALGORITHM,
    };
    // R3 之后 `NOT_IMPLEMENTED` 只作为 ABI 预留码存在（无导出入口会返回它）。
    const _: i32 = ERR_NOT_IMPLEMENTED;

    #[test]
    fn abi_version_is_frozen_at_one() {
        assert_eq!(ABI_VERSION, 1);
    }

    #[test]
    fn error_codes_are_unique_and_signed_as_documented() {
        let codes = [
            ERR_OK,
            ERR_INVALID_ARGUMENT,
            ERR_OUTPUT_BOUND,
            ERR_STALE_HANDLE,
            ERR_SESSION_LIMIT,
            ERR_INTERNAL,
            ERR_PANIC,
            ERR_JNI_PENDING,
            ERR_SHORT_READ,
            ERR_UNSUPPORTED_ALGORITHM,
            ERR_GZIP_HEADER,
            ERR_GZIP_DEFLATE,
            ERR_GZIP_TRAILER,
            ERR_PLAN_STRUCTURE,
            ERR_PLAN_FIELD_RANGE,
            ERR_PLAN_EXPIRED,
            ERR_NOT_IMPLEMENTED,
        ];
        assert_eq!(codes[0], 0);
        for pair in codes.windows(2) {
            assert!(pair[1] < 0, "错误码必须为负数: {}", pair[1]);
            assert_ne!(pair[0], pair[1], "错误码不得重复");
        }
        assert_eq!(ERR_PANIC, -7);
        assert_eq!(ERR_NOT_IMPLEMENTED, -26);
    }

    #[test]
    fn abi_limits_match_the_frozen_contract() {
        assert_eq!(crate::digest::MAX_LENGTH, 33_554_432);
        assert_eq!(crate::digest::CHUNK, 262_144);
        assert_eq!(crate::gzip::MAX_INPUT_CHUNK, 262_144);
        assert_eq!(crate::gzip::MAX_METADATA, 8_192);
        assert_eq!(crate::plan::MAX_VIEW_BYTES, 2 * 1024 * 1024);
        assert_eq!(&crate::plan::MAGIC, b"DSHP");
    }

    #[test]
    fn guard_maps_unwind_panic_to_error_code() {
        // 仅测试代码允许构造 panic；生产入口没有 panic!/unwrap/expect 路径。
        assert_eq!(
            crate::jni::guard(|| panic!("测试注入：catch_unwind 必须映射为 PANIC")),
            i64::from(ERR_PANIC)
        );
        assert_eq!(crate::jni::guard(|| 7), 7);
    }

    #[test]
    fn exported_symbols_probe_and_archive_stubs_are_real_now() {
        let env = std::ptr::null_mut();
        let class = std::ptr::null_mut();
        let abi = crate::jni::Java_com_deepseekharness_app_util_RustNative_abiVersion(env, class);
        assert_eq!(abi, ABI_VERSION);
        // R3 起 archive 入口是真实实现：非法 level 在 JNI 之前被拒绝（不创建会话，
        // 避免与 gzip 模块的会话表并发单测互相影响）；合法会话与数据面由 gzip 单测
        // 和主机 JVM 差分覆盖。
        assert_eq!(
            crate::jni::Java_com_deepseekharness_app_util_RustNative_archivePackCreate(
                env, class, -1
            ),
            i64::from(ERR_INVALID_ARGUMENT)
        );
        // null direct buffer（env 也为空）在 JNI 层返回 INVALID_ARGUMENT，不 UB。
        assert_eq!(
            crate::jni::Java_com_deepseekharness_app_util_RustNative_archiveUnpackWrite(
                env,
                class,
                1,
                std::ptr::null_mut()
            ),
            i64::from(ERR_INVALID_ARGUMENT)
        );
        // digest 的标量参数校验发生在 JNI 调用之前：null env + 非法 fd 必须安全地
        // 返回冻结错误码，不触碰 JNI、不崩溃（真实 JNI 调用由主机 JVM 差分覆盖）。
        assert_eq!(
            crate::jni::Java_com_deepseekharness_app_util_RustNative_digest(
                env,
                class,
                -1,
                0,
                1,
                crate::digest::ALGORITHM_SHA256,
                std::ptr::null_mut(),
            ),
            i64::from(ERR_INVALID_ARGUMENT)
        );
        // 空指针 env（JVM 不会传入）在合法标量下按调用方错误处理，不调用 JNI。
        assert_eq!(
            crate::jni::Java_com_deepseekharness_app_util_RustNative_digest(
                env,
                class,
                0,
                0,
                1,
                crate::digest::ALGORITHM_SHA256,
                std::ptr::null_mut(),
            ),
            i64::from(ERR_INVALID_ARGUMENT)
        );
        // 未知算法在 JNI 之前就被拒绝。
        assert_eq!(
            crate::jni::Java_com_deepseekharness_app_util_RustNative_digest(
                env,
                class,
                0,
                0,
                1,
                99,
                std::ptr::null_mut(),
            ),
            i64::from(ERR_UNSUPPORTED_ALGORITHM)
        );
        // 全部导出符号都在 R0 冻结；这里逐一取函数地址，防止改名后只改 Rust 忘记 Java。
        let _ = (
            crate::jni::Java_com_deepseekharness_app_util_RustNative_digest,
            crate::jni::Java_com_deepseekharness_app_util_RustNative_archivePackUpdate,
            crate::jni::Java_com_deepseekharness_app_util_RustNative_archivePackFinish,
            crate::jni::Java_com_deepseekharness_app_util_RustNative_archivePackAbort,
            crate::jni::Java_com_deepseekharness_app_util_RustNative_archiveUnpackCreate,
            crate::jni::Java_com_deepseekharness_app_util_RustNative_archiveUnpackWrite,
            crate::jni::Java_com_deepseekharness_app_util_RustNative_archiveUnpackRead,
            crate::jni::Java_com_deepseekharness_app_util_RustNative_archiveUnpackAbort,
            crate::jni::Java_com_deepseekharness_app_util_RustNative_verifyPlan,
        );
    }
}

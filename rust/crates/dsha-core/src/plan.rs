//! R2 实现：`PlanView` v1 规范化视图的只读校验（A3 任务基线 §3）。
//!
//! 本模块不解析 JSON、不读时钟（`now_ms` 是入参）、不读文件/环境、不写日志、
//! 不分配与输入无关的内存；同一输入两次调用结果完全一致。`PlanView` 只携带
//! 计数、枚举、位图、摘要与短串，没有路径、命令或文件清单。
//!
//! 字节布局与规则按 A3 §3.2–§3.7 冻结；R2 把 `type` 码、枚举数值、字段 `flags`
//! 语义与原因码去重/升序写进本文件的常量（Lead Q5-A 裁决：属实现细节，不需要
//! ABI v2）。R0 已冻结的六个常量（`MAGIC`/`VIEW_VERSION`/`MAX_VIEW_BYTES`/
//! `MAX_FIELDS`/`MAX_FIELD_BYTES`/`MAX_REASONS`）保持不变。
//!
//! 多原因规则（§3.7）：除硬上限（长度不足 48 字节、超过 2 MiB）外不因首错提前
//! 返回；原因载荷是去重、升序的码集合，v1 期望条数 ≤ 6（`MAX_REASONS` 只是缓冲
//! 余量）。v1 最多可同时命中 6 个原因码，`Reasons::add` 在容量满时忽略新码，
//! 因此不会产生半成品集合。
#![forbid(unsafe_code)]

use crate::abi::{
    ERR_PLAN_EXPIRED, ERR_PLAN_FIELD_RANGE, ERR_PLAN_LIMIT, ERR_PLAN_POLICY_VERSION,
    ERR_PLAN_STRUCTURE, ERR_PLAN_UNKNOWN_FIELD,
};

/// 头部 magic（小端字节 `44 53 48 50` = "DSHP"）。
pub const MAGIC: [u8; 4] = *b"DSHP";

/// `viewVersion`（首期只接受 1；其它值 → `PLAN_STRUCTURE`，不是 `POLICY_VERSION`）。
pub const VIEW_VERSION: u16 = 1;

/// 允许的 `policyVersion` 集合（首期 {1}；其它值 → `PLAN_POLICY_VERSION`）。
pub const POLICY_VERSION: u16 = 1;

/// 单次视图最大长度（2 MiB = `BackupLimits.MANIFEST`）。
pub const MAX_VIEW_BYTES: usize = 2 * 1024 * 1024;

/// 字段数上限。
pub const MAX_FIELDS: u32 = 64;

/// 每字段长度上限（字符串字段另受 UTF-8 非空、无 NUL 约束）。
pub const MAX_FIELD_BYTES: u32 = 4_096;

/// 返回原因集合上限（按字段序/码序）。
pub const MAX_REASONS: usize = 8;

/// 头部字节数（§3.2：紧凑布局，无对齐填充）。
pub const HEADER_BYTES: usize = 48;

/// v1 必须恰好包含的字段数（§3.2/§3.3）。
pub const FIELD_COUNT: u32 = 15;

/// v1 期望的原因条数上限（§3.7：`count <= min(maxReasons, 6)`）。
pub const MAX_EXPECTED_REASONS: usize = 6;

/// 头部 `flags` 的 bit0：计划带有效期。
pub const HEADER_FLAG_HAS_EXPIRY: u32 = 1;

/// 头部 `flags` 允许位（其余位必须 0）。
pub const HEADER_FLAGS_ALLOWED: u32 = HEADER_FLAG_HAS_EXPIRY;

// 类型码（§3.2 冻结）。
/// 1 字节无符号整数（`length` 必须为 1）。
pub const TYPE_U8: u8 = 1;
/// 4 字节小端无符号整数（`length` 必须为 4）。
pub const TYPE_U32: u8 = 2;
/// 8 字节小端无符号整数（`length` 必须为 8）。
pub const TYPE_U64: u8 = 3;
/// 32 字节摘要（`length` 必须为 32）。
pub const TYPE_BYTES32: u8 = 4;
/// UTF-8 短串（`length` 1..=4096）。
pub const TYPE_UTF8: u8 = 5;

// 字段 id（§3.3 冻结）。
pub const FIELD_FORMAT_VERSION: u16 = 1;
pub const FIELD_OPERATION: u16 = 2;
pub const FIELD_INTEGRITY: u16 = 3;
pub const FIELD_ROOT_COUNT: u16 = 4;
pub const FIELD_ENTRY_COUNT: u16 = 5;
pub const FIELD_BYTE_COUNT: u16 = 6;
pub const FIELD_CREATED_AT_MS: u16 = 7;
pub const FIELD_SOURCE_DIGEST: u16 = 8;
pub const FIELD_SELECTED_MASK: u16 = 9;
pub const FIELD_TARGET_MAPPING_KIND: u16 = 10;
pub const FIELD_APP_VERSION: u16 = 11;
pub const FIELD_DATA_FORMAT: u16 = 12;
pub const FIELD_SENSITIVE_POLICY: u16 = 13;
pub const FIELD_PLUGIN_COUNT: u16 = 14;
pub const FIELD_PLAN_DIGEST: u16 = 15;

// 枚举码（§3.4 冻结）。
pub const OPERATION_EXPORT: u8 = 1;
pub const OPERATION_MAINTENANCE: u8 = 2;
pub const OPERATION_RESCUE: u8 = 3;

pub const INTEGRITY_QUIESCENT: u8 = 1;
pub const INTEGRITY_EXTERNAL_CHECKED: u8 = 2;
pub const INTEGRITY_PARTIAL: u8 = 3;
pub const INTEGRITY_BEST_EFFORT: u8 = 4;

pub const TARGET_MAPPING_STABLE_PRIVATE: u8 = 1;
pub const TARGET_MAPPING_LEGACY_PRIVATE: u8 = 2;
pub const TARGET_MAPPING_STABLE_GUEST: u8 = 3;
pub const TARGET_MAPPING_LEGACY_GUEST: u8 = 4;

// 取值范围（§3.3 冻结）。
/// 字段 1 的唯一允许值。
pub const FORMAT_VERSION: u32 = 5;
/// `rootCount` 下限（计划必须至少写入一个根）。
pub const MIN_ROOT_COUNT: u32 = 1;
/// `rootCount` 上限（Q1-A：本次计划实际写入根数，>64 由投影器记 `PLAN_VIEW_ROOT_LIMIT`）。
pub const MAX_ROOT_COUNT: u32 = 64;
/// `entryCount` 下限。
pub const MIN_ENTRY_COUNT: u32 = 1;
/// `entryCount` 上限（与 `BackupLimits.ENTRIES` 对齐）。
pub const MAX_ENTRY_COUNT: u32 = 100_000;
/// `byteCount` 上限（16 GiB，与 `BackupLimits.BYTES` 对齐）。
pub const MAX_BYTE_COUNT: u64 = 16 * 1024 * 1024 * 1024;
/// `selectedMask` 允许的位（bit0..bit4），取值 1..=31。
pub const MIN_SELECTED_MASK: u32 = 1;
pub const MAX_SELECTED_MASK: u32 = 31;
/// `pluginCount` 上限（与 `NativePluginGraph` 的 4096 对齐）。
pub const MAX_PLUGIN_COUNT: u32 = 4_096;
/// `appVersion` 字节上限（身份清单 `ci/release-identity*.json` 当前 38 字符，上限 256）。
pub const MAX_APP_VERSION_BYTES: u32 = 256;
/// `dataFormat` 字节上限。
pub const MAX_DATA_FORMAT_BYTES: u32 = 64;
/// `sensitivePolicy` 字节上限。
pub const MAX_SENSITIVE_POLICY_BYTES: u32 = 64;

/// `sensitivePolicy` v1 闭集（Q2-A；覆盖全部生产写者，新增值必须抬 `policyVersion`）。
pub const SENSITIVE_POLICIES: [&str; 5] = [
    "UNENCRYPTED",
    "PASSWORD_ENCRYPTED",
    "APP_PRIVATE_STAGING",
    "LEGACY_PLAINTEXT",
    "PRIVATE_LOCAL_NATIVE_STATE_IN_PLACE",
];

// 原因码：线上载荷值 = `ERR_PLAN_*` 的绝对值（§3.7）。
pub const REASON_STRUCTURE: u32 = (-ERR_PLAN_STRUCTURE) as u32;
pub const REASON_FIELD_RANGE: u32 = (-ERR_PLAN_FIELD_RANGE) as u32;
pub const REASON_POLICY_VERSION: u32 = (-ERR_PLAN_POLICY_VERSION) as u32;
pub const REASON_EXPIRED: u32 = (-ERR_PLAN_EXPIRED) as u32;
pub const REASON_LIMIT: u32 = (-ERR_PLAN_LIMIT) as u32;
pub const REASON_UNKNOWN_FIELD: u32 = (-ERR_PLAN_UNKNOWN_FIELD) as u32;

/// 去重、升序的原因码集合（码值 20..=25，§3.7）。
///
/// 定长数组实现：不按输入长度分配，`add` 在 `MAX_REASONS` 满时忽略新码；v1 最多
/// 6 个不同码，因此正常路径永远不会触发“忽略”。
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct Reasons {
    codes: [u32; MAX_REASONS],
    len: usize,
}

impl Default for Reasons {
    fn default() -> Self {
        Self {
            codes: [0; MAX_REASONS],
            len: 0,
        }
    }
}

impl Reasons {
    /// 空集合。
    pub fn new() -> Self {
        Self::default()
    }

    /// 是否没有任何原因（= VALID）。
    pub fn is_empty(&self) -> bool {
        self.len == 0
    }

    /// 原因条数。
    pub fn len(&self) -> usize {
        self.len
    }

    /// 升序码视图。
    pub fn codes(&self) -> &[u32] {
        &self.codes[..self.len]
    }

    /// 加入一个原因码：重复忽略、按升序插入；容量满时忽略（v1 不会发生）。
    pub fn add(&mut self, code: u32) {
        if code == 0 || self.len >= MAX_REASONS {
            return;
        }
        if self.codes()[..self.len].binary_search(&code).is_ok() {
            return;
        }
        let mut index = self.len;
        while index > 0 && self.codes[index - 1] > code {
            self.codes[index] = self.codes[index - 1];
            index -= 1;
        }
        self.codes[index] = code;
        self.len += 1;
    }
}

fn read_u8(view: &[u8], offset: usize) -> Option<u8> {
    view.get(offset).copied()
}

fn read_u16(view: &[u8], offset: usize) -> Option<u16> {
    let end = offset.checked_add(2)?;
    let bytes: [u8; 2] = view.get(offset..end)?.try_into().ok()?;
    Some(u16::from_le_bytes(bytes))
}

fn read_u32(view: &[u8], offset: usize) -> Option<u32> {
    let end = offset.checked_add(4)?;
    let bytes: [u8; 4] = view.get(offset..end)?.try_into().ok()?;
    Some(u32::from_le_bytes(bytes))
}

fn read_u64(view: &[u8], offset: usize) -> Option<u64> {
    let end = offset.checked_add(8)?;
    let bytes: [u8; 8] = view.get(offset..end)?.try_into().ok()?;
    Some(u64::from_le_bytes(bytes))
}

/// 头部内嵌的 `nowMs`（§3.6）；结构不足 44 字节时返回 `None`。
///
/// JNI 入口用它与视图内嵌时间保持一致；`validate` 的调用方可用入参覆盖。
pub fn embedded_now_ms(view: &[u8]) -> Option<u64> {
    read_u64(view, 36)
}

/// 字段 id 的期望类型码（§3.3）；未知 id 返回 `None`。
fn expected_type(id: u16) -> Option<u8> {
    match id {
        FIELD_FORMAT_VERSION | FIELD_ROOT_COUNT | FIELD_ENTRY_COUNT | FIELD_SELECTED_MASK
        | FIELD_PLUGIN_COUNT => Some(TYPE_U32),
        FIELD_OPERATION | FIELD_INTEGRITY | FIELD_TARGET_MAPPING_KIND => Some(TYPE_U8),
        FIELD_BYTE_COUNT | FIELD_CREATED_AT_MS => Some(TYPE_U64),
        FIELD_SOURCE_DIGEST | FIELD_PLAN_DIGEST => Some(TYPE_BYTES32),
        FIELD_APP_VERSION | FIELD_DATA_FORMAT | FIELD_SENSITIVE_POLICY => Some(TYPE_UTF8),
        _ => None,
    }
}

/// 类型码与 `length` 是否自洽（§3.2）；UTF8 的 1..=4096 在此按类型层校验。
fn type_length_ok(type_code: u8, length: u32) -> bool {
    match type_code {
        TYPE_U8 => length == 1,
        TYPE_U32 => length == 4,
        TYPE_U64 => length == 8,
        TYPE_BYTES32 => length == 32,
        TYPE_UTF8 => (1..=MAX_FIELD_BYTES).contains(&length),
        _ => false,
    }
}

/// 单个字段的值语义（§3.3/§3.4）。只在 id/type/length 形状全部自洽时调用。
fn check_value(id: u16, value: &[u8], reasons: &mut Reasons) {
    match id {
        FIELD_FORMAT_VERSION => {
            if read_u32(value, 0) != Some(FORMAT_VERSION) {
                reasons.add(REASON_FIELD_RANGE);
            }
        }
        FIELD_OPERATION => {
            let code = read_u8(value, 0).unwrap_or(0);
            if !(OPERATION_EXPORT..=OPERATION_RESCUE).contains(&code) {
                reasons.add(REASON_FIELD_RANGE);
            }
        }
        FIELD_INTEGRITY => {
            let code = read_u8(value, 0).unwrap_or(0);
            if !(INTEGRITY_QUIESCENT..=INTEGRITY_BEST_EFFORT).contains(&code) {
                reasons.add(REASON_FIELD_RANGE);
            }
        }
        FIELD_ROOT_COUNT => {
            let count = read_u32(value, 0).unwrap_or(0);
            if !(MIN_ROOT_COUNT..=MAX_ROOT_COUNT).contains(&count) {
                reasons.add(REASON_FIELD_RANGE);
            }
        }
        FIELD_ENTRY_COUNT => {
            let count = read_u32(value, 0).unwrap_or(0);
            if !(MIN_ENTRY_COUNT..=MAX_ENTRY_COUNT).contains(&count) {
                reasons.add(REASON_FIELD_RANGE);
            }
        }
        FIELD_BYTE_COUNT => {
            if read_u64(value, 0).unwrap_or(0) > MAX_BYTE_COUNT {
                reasons.add(REASON_FIELD_RANGE);
            }
        }
        // createdAtMs 接受任意 u64（§3.3：无跨字段规则）。
        FIELD_CREATED_AT_MS => {}
        // 摘要内容 v1 不校验（§3.3/§3.5）；长度已由类型层约束。
        FIELD_SOURCE_DIGEST | FIELD_PLAN_DIGEST => {}
        FIELD_SELECTED_MASK => {
            let mask = read_u32(value, 0).unwrap_or(0);
            if !(MIN_SELECTED_MASK..=MAX_SELECTED_MASK).contains(&mask) {
                reasons.add(REASON_FIELD_RANGE);
            }
        }
        FIELD_TARGET_MAPPING_KIND => {
            let code = read_u8(value, 0).unwrap_or(0);
            if !(TARGET_MAPPING_STABLE_PRIVATE..=TARGET_MAPPING_LEGACY_GUEST).contains(&code) {
                reasons.add(REASON_FIELD_RANGE);
            }
        }
        // UTF8 字段（11/12/13）由 `check_utf8_field` 处理：字符串规则的判定与
        // 「类型长度 1..=4096」独立，因此超字段上限与空串在类型层失败时也照样判定。
        FIELD_APP_VERSION | FIELD_DATA_FORMAT | FIELD_SENSITIVE_POLICY => {}
        FIELD_PLUGIN_COUNT => check_max_u32(value, MAX_PLUGIN_COUNT, reasons),
        _ => {}
    }
}

/// U32 字段的“不大于上限”检查（`pluginCount`）。
fn check_max_u32(value: &[u8], max: u32, reasons: &mut Reasons) {
    if read_u32(value, 0).unwrap_or(0) > max {
        reasons.add(REASON_FIELD_RANGE);
    }
}

/// UTF8 字段各自的更紧上限（§3.3）；未知 id 返回类型层上限。
fn utf8_max_bytes(id: u16) -> u32 {
    match id {
        FIELD_APP_VERSION => MAX_APP_VERSION_BYTES,
        FIELD_DATA_FORMAT => MAX_DATA_FORMAT_BYTES,
        FIELD_SENSITIVE_POLICY => MAX_SENSITIVE_POLICY_BYTES,
        _ => MAX_FIELD_BYTES,
    }
}

/// 字符串通用规则（§3.3）：空串/超字段上限/NUL → FIELD_RANGE；非法 UTF-8 →
/// STRUCTURE；`sensitivePolicy` 另需在闭集内（§3.4）。
///
/// 只在「id 已知且类型码匹配」时调用；长度 0 或 > 4096 时类型层已记 STRUCTURE，
/// 这里仍判定可独立判定的字符串规则（不因首错提前返回）。
fn check_utf8_field(id: u16, value: &[u8], length: u32, reasons: &mut Reasons) {
    if length == 0 {
        // 空串：同时违反「类型长度 1..=4096」（调用点的 STRUCTURE）与「非空」。
        reasons.add(REASON_FIELD_RANGE);
        return;
    }
    if length > utf8_max_bytes(id) {
        reasons.add(REASON_FIELD_RANGE);
    }
    if length > MAX_FIELD_BYTES {
        // 超出类型长度上限：长度已判无效，不再解码（值语义不可判定）。
        return;
    }
    let text = match core::str::from_utf8(value) {
        Ok(text) => text,
        Err(_) => {
            reasons.add(REASON_STRUCTURE);
            return;
        }
    };
    if value.contains(&0) {
        reasons.add(REASON_FIELD_RANGE);
    }
    if id == FIELD_SENSITIVE_POLICY && !SENSITIVE_POLICIES.contains(&text) {
        reasons.add(REASON_FIELD_RANGE);
    }
}

/// 校验一份 `PlanView` 字节；`now_ms` 是调用方给定的“当前时间”（Rust 不读系统时钟）。
///
/// 通过返回 `Ok(())`；否则返回去重升序的原因集合。硬上限（`< 48` 字节、
/// `> 2 MiB`）在该层提前拒绝：前者无法解析头部，后者按 §3.7 只记 `PLAN_LIMIT`
/// （不继续遍历超大输入）。
pub fn validate(view: &[u8], now_ms: u64) -> Result<(), Reasons> {
    let mut reasons = Reasons::new();
    if view.len() < HEADER_BYTES {
        reasons.add(REASON_STRUCTURE);
        return Err(reasons);
    }
    if view.len() > MAX_VIEW_BYTES {
        reasons.add(REASON_LIMIT);
        return Err(reasons);
    }

    // 头部（§3.2）。
    if view.get(0..4) != Some(MAGIC.as_slice()) {
        reasons.add(REASON_STRUCTURE);
    }
    if read_u16(view, 4) != Some(VIEW_VERSION) {
        reasons.add(REASON_STRUCTURE);
    }
    if read_u16(view, 6) != Some(POLICY_VERSION) {
        reasons.add(REASON_POLICY_VERSION);
    }
    if read_u32(view, 8).map(|value| value as usize) != Some(view.len()) {
        reasons.add(REASON_STRUCTURE);
    }
    let field_count = read_u32(view, 12).unwrap_or(0);
    if field_count > MAX_FIELDS {
        reasons.add(REASON_LIMIT);
    }
    if field_count != FIELD_COUNT {
        reasons.add(REASON_STRUCTURE);
    }
    let flags = read_u32(view, 16).unwrap_or(0);
    if flags & !HEADER_FLAGS_ALLOWED != 0 {
        reasons.add(REASON_STRUCTURE);
    }
    let generated_at_ms = read_u64(view, 20).unwrap_or(0);
    let expires_at_ms = read_u64(view, 28).unwrap_or(0);
    if read_u32(view, 44).unwrap_or(0) != 0 {
        reasons.add(REASON_STRUCTURE);
    }

    // 有效期（§3.6，Q3-A：畸形与“已过期”同归 PLAN_EXPIRED）。
    let has_expiry = flags & HEADER_FLAG_HAS_EXPIRY != 0;
    if !has_expiry && expires_at_ms != 0 {
        reasons.add(REASON_STRUCTURE);
    }
    if has_expiry && (expires_at_ms <= generated_at_ms || now_ms >= expires_at_ms) {
        reasons.add(REASON_EXPIRED);
    }

    // 字段区（§3.2/§3.3）：紧凑排列、无填充、id 升序且 1..=15 各一次。
    let mut offset = HEADER_BYTES;
    let mut parsed_fields: u32 = 0;
    let mut expected_id: u32 = 1;
    while offset < view.len() {
        if view.len().saturating_sub(offset) < 8 {
            reasons.add(REASON_STRUCTURE);
            break;
        }
        let id = read_u16(view, offset).unwrap_or(0);
        let type_code = read_u8(view, offset + 2).unwrap_or(0);
        let field_flags = read_u8(view, offset + 3).unwrap_or(0);
        let length = read_u32(view, offset + 4).unwrap_or(0);
        let value_start = offset + 8;
        let Some(value_end) = value_start.checked_add(length as usize) else {
            reasons.add(REASON_STRUCTURE);
            break;
        };
        if value_end > view.len() {
            reasons.add(REASON_STRUCTURE);
            break;
        }
        let value = &view[value_start..value_end];
        offset = value_end;
        parsed_fields = parsed_fields.saturating_add(1);

        if field_flags != 0 {
            reasons.add(REASON_STRUCTURE);
        }
        let expected = expected_type(id);
        if expected.is_none() {
            reasons.add(REASON_UNKNOWN_FIELD);
        }
        if u32::from(id) != expected_id {
            reasons.add(REASON_STRUCTURE);
        } else {
            expected_id = expected_id.saturating_add(1);
        }
        let shape_ok = type_length_ok(type_code, length);
        if !shape_ok {
            reasons.add(REASON_STRUCTURE);
        }
        let Some(expected) = expected else {
            continue;
        };
        if type_code != expected {
            reasons.add(REASON_STRUCTURE);
            continue;
        }
        // id 已知且类型匹配：UTF8 的字符串规则即便类型长度失败也可独立判定。
        if type_code == TYPE_UTF8 {
            check_utf8_field(id, value, length, &mut reasons);
        } else if shape_ok {
            check_value(id, value, &mut reasons);
        }
    }
    if parsed_fields != FIELD_COUNT {
        reasons.add(REASON_STRUCTURE);
    }
    if expected_id != FIELD_COUNT.saturating_add(1) {
        reasons.add(REASON_STRUCTURE);
    }

    if reasons.is_empty() {
        Ok(())
    } else {
        Err(reasons)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn put_u16(out: &mut Vec<u8>, value: u16) {
        out.extend_from_slice(&value.to_le_bytes());
    }

    fn put_u32(out: &mut Vec<u8>, value: u32) {
        out.extend_from_slice(&value.to_le_bytes());
    }

    fn put_u64(out: &mut Vec<u8>, value: u64) {
        out.extend_from_slice(&value.to_le_bytes());
    }

    fn field(id: u16, type_code: u8, value: &[u8]) -> Vec<u8> {
        let mut out = Vec::with_capacity(8 + value.len());
        put_u16(&mut out, id);
        out.push(type_code);
        out.push(0);
        put_u32(&mut out, value.len() as u32);
        out.extend_from_slice(value);
        out
    }

    fn utf8_field(id: u16, text: &str) -> Vec<u8> {
        field(id, TYPE_UTF8, text.as_bytes())
    }

    const CANONICAL_GENERATED_MS: u64 = 1_760_000_000_500;
    const CANONICAL_NOW_MS: u64 = 1_760_000_000_500;
    const CANONICAL_APP_VERSION: &str = "0.2.1-rc.5-20261113.1000-dsh0.2.2-rc.1";
    const CANONICAL_SENSITIVE_POLICY: &str = "PRIVATE_LOCAL_NATIVE_STATE_IN_PLACE";

    fn canonical_fields() -> Vec<Vec<u8>> {
        let mut source_digest = [0u8; 32];
        for (index, byte) in source_digest.iter_mut().enumerate() {
            *byte = index as u8;
        }
        let mut plan_digest = [0u8; 32];
        for (index, byte) in plan_digest.iter_mut().enumerate() {
            *byte = 0xA0 + index as u8;
        }
        vec![
            field(
                FIELD_FORMAT_VERSION,
                TYPE_U32,
                &FORMAT_VERSION.to_le_bytes(),
            ),
            field(FIELD_OPERATION, TYPE_U8, &[OPERATION_EXPORT]),
            field(FIELD_INTEGRITY, TYPE_U8, &[INTEGRITY_QUIESCENT]),
            field(FIELD_ROOT_COUNT, TYPE_U32, &1u32.to_le_bytes()),
            field(FIELD_ENTRY_COUNT, TYPE_U32, &42u32.to_le_bytes()),
            field(FIELD_BYTE_COUNT, TYPE_U64, &1024u64.to_le_bytes()),
            field(
                FIELD_CREATED_AT_MS,
                TYPE_U64,
                &1_760_000_000_000u64.to_le_bytes(),
            ),
            field(FIELD_SOURCE_DIGEST, TYPE_BYTES32, &source_digest),
            field(FIELD_SELECTED_MASK, TYPE_U32, &3u32.to_le_bytes()),
            field(
                FIELD_TARGET_MAPPING_KIND,
                TYPE_U8,
                &[TARGET_MAPPING_STABLE_PRIVATE],
            ),
            utf8_field(FIELD_APP_VERSION, CANONICAL_APP_VERSION),
            utf8_field(FIELD_DATA_FORMAT, "UNINSPECTED"),
            utf8_field(FIELD_SENSITIVE_POLICY, CANONICAL_SENSITIVE_POLICY),
            field(FIELD_PLUGIN_COUNT, TYPE_U32, &12u32.to_le_bytes()),
            field(FIELD_PLAN_DIGEST, TYPE_BYTES32, &plan_digest),
        ]
    }

    fn header(
        total_length: u32,
        field_count: u32,
        flags: u32,
        generated_at_ms: u64,
        expires_at_ms: u64,
        now_ms: u64,
    ) -> Vec<u8> {
        let mut out = Vec::with_capacity(HEADER_BYTES);
        out.extend_from_slice(&MAGIC);
        put_u16(&mut out, VIEW_VERSION);
        put_u16(&mut out, POLICY_VERSION);
        put_u32(&mut out, total_length);
        put_u32(&mut out, field_count);
        put_u32(&mut out, flags);
        put_u64(&mut out, generated_at_ms);
        put_u64(&mut out, expires_at_ms);
        put_u64(&mut out, now_ms);
        put_u32(&mut out, 0);
        out
    }

    fn assemble(fields: &[Vec<u8>], flags: u32, generated: u64, expires: u64, now: u64) -> Vec<u8> {
        let total = fields.iter().map(Vec::len).sum::<usize>() + HEADER_BYTES;
        let mut out = header(total as u32, FIELD_COUNT, flags, generated, expires, now);
        for item in fields {
            out.extend_from_slice(item);
        }
        out
    }

    fn canonical() -> Vec<u8> {
        assemble(
            &canonical_fields(),
            0,
            CANONICAL_GENERATED_MS,
            0,
            CANONICAL_NOW_MS,
        )
    }

    /// 用替换后的单个字段重建视图（保持头部与其它字段不变）。
    fn with_field(id: u16, replacement: Vec<u8>) -> Vec<u8> {
        let mut fields = canonical_fields();
        let index = fields
            .iter()
            .position(|item| read_u16(item, 0) == Some(id))
            .unwrap_or_else(|| panic!("测试夹具缺少字段 {id}"));
        fields[index] = replacement;
        assemble(&fields, 0, CANONICAL_GENERATED_MS, 0, CANONICAL_NOW_MS)
    }

    fn reasons_of(view: &[u8], now: u64) -> Vec<u32> {
        match validate(view, now) {
            Ok(()) => Vec::new(),
            Err(reasons) => reasons.codes().to_vec(),
        }
    }

    fn patch_u16(view: &mut [u8], offset: usize, value: u16) {
        view[offset..offset + 2].copy_from_slice(&value.to_le_bytes());
    }

    fn patch_u32(view: &mut [u8], offset: usize, value: u32) {
        view[offset..offset + 4].copy_from_slice(&value.to_le_bytes());
    }

    fn patch_u64(view: &mut [u8], offset: usize, value: u64) {
        view[offset..offset + 8].copy_from_slice(&value.to_le_bytes());
    }

    /// u64 的 8 字节大端填充，便于构造 invalid UTF-8。
    fn padded(count: usize) -> Vec<u8> {
        vec![b'x'; count]
    }

    fn hex(bytes: &[u8]) -> String {
        let mut out = String::with_capacity(bytes.len() * 2);
        for byte in bytes {
            out.push_str(&format!("{byte:02x}"));
        }
        out
    }

    #[test]
    fn reason_codes_are_the_absolute_abi_error_codes() {
        assert_eq!(REASON_STRUCTURE, 20);
        assert_eq!(REASON_FIELD_RANGE, 21);
        assert_eq!(REASON_POLICY_VERSION, 22);
        assert_eq!(REASON_EXPIRED, 23);
        assert_eq!(REASON_LIMIT, 24);
        assert_eq!(REASON_UNKNOWN_FIELD, 25);
        assert_eq!(MAX_EXPECTED_REASONS, 6);
        assert_eq!(HEADER_BYTES, 48);
        assert_eq!(FIELD_COUNT, 15);
    }

    #[test]
    fn canonical_view_is_valid_and_byte_pinned() {
        let view = canonical();
        // 字节布局冻结（§3.2–§3.4）：任何编码漂移都会让这份 pin 失败。
        assert_eq!(hex(&view), CANONICAL_VIEW_HEX);
        assert_eq!(validate(&view, CANONICAL_NOW_MS), Ok(()));
        assert_eq!(embedded_now_ms(&view), Some(CANONICAL_NOW_MS));
        // 规范化夹具使用当前真实 versionName（身份清单 `ci/release-identity*.json`
        // 为 38 字符；基线文档里的“44 字符”是过时估计，身份清单才是权威）。
        assert_eq!(CANONICAL_APP_VERSION.len(), 38);
        assert!(SENSITIVE_POLICIES.contains(&CANONICAL_SENSITIVE_POLICY));
    }

    #[test]
    fn header_structure_rules_are_reported_without_early_return() {
        let mut magic = canonical();
        magic[0] = b'X';
        assert_eq!(reasons_of(&magic, CANONICAL_NOW_MS), vec![REASON_STRUCTURE]);

        let mut version = canonical();
        patch_u16(&mut version, 4, 2);
        assert_eq!(
            reasons_of(&version, CANONICAL_NOW_MS),
            vec![REASON_STRUCTURE]
        );

        let mut total = canonical();
        let total_length = total.len() as u32 + 1;
        patch_u32(&mut total, 8, total_length);
        assert_eq!(reasons_of(&total, CANONICAL_NOW_MS), vec![REASON_STRUCTURE]);

        let mut field_count = canonical();
        patch_u32(&mut field_count, 12, 14);
        assert_eq!(
            reasons_of(&field_count, CANONICAL_NOW_MS),
            vec![REASON_STRUCTURE]
        );

        let mut flags = canonical();
        patch_u32(&mut flags, 16, 2);
        assert_eq!(reasons_of(&flags, CANONICAL_NOW_MS), vec![REASON_STRUCTURE]);

        let mut reserved = canonical();
        patch_u32(&mut reserved, 44, 1);
        assert_eq!(
            reasons_of(&reserved, CANONICAL_NOW_MS),
            vec![REASON_STRUCTURE]
        );
    }

    #[test]
    fn policy_version_is_a_separate_reason() {
        let mut view = canonical();
        patch_u16(&mut view, 6, 2);
        assert_eq!(
            reasons_of(&view, CANONICAL_NOW_MS),
            vec![REASON_POLICY_VERSION]
        );
    }

    #[test]
    fn hard_limits_stop_parsing() {
        assert_eq!(reasons_of(&padded(47), 0), vec![REASON_STRUCTURE]);
        let oversized = vec![0u8; MAX_VIEW_BYTES + 1];
        assert_eq!(reasons_of(&oversized, 0), vec![REASON_LIMIT]);
        // 恰好 2 MiB 不触发 LIMIT（结构与 magic 错误照常报告）。
        let boundary = vec![0u8; MAX_VIEW_BYTES];
        let reasons = reasons_of(&boundary, 0);
        assert!(!reasons.contains(&REASON_LIMIT), "{reasons:?}");
        assert!(reasons.contains(&REASON_STRUCTURE), "{reasons:?}");

        let mut field_count = canonical();
        patch_u32(&mut field_count, 12, 65);
        assert_eq!(
            reasons_of(&field_count, CANONICAL_NOW_MS),
            vec![REASON_STRUCTURE, REASON_LIMIT]
        );
    }

    #[test]
    fn field_sequence_rules_cover_order_duplicate_missing_and_unknown() {
        // 乱序：交换字段 1 与 2。
        let mut fields = canonical_fields();
        fields.swap(0, 1);
        let swapped = assemble(&fields, 0, CANONICAL_GENERATED_MS, 0, CANONICAL_NOW_MS);
        assert_eq!(
            reasons_of(&swapped, CANONICAL_NOW_MS),
            vec![REASON_STRUCTURE]
        );

        // 重复：字段 2 出现两次（总数 16）。
        let mut fields = canonical_fields();
        fields.insert(2, fields[1].clone());
        let duplicated = assemble(&fields, 0, CANONICAL_GENERATED_MS, 0, CANONICAL_NOW_MS);
        assert_eq!(
            reasons_of(&duplicated, CANONICAL_NOW_MS),
            vec![REASON_STRUCTURE]
        );

        // 缺失：删掉字段 15。
        let mut fields = canonical_fields();
        fields.pop();
        let missing = assemble(&fields, 0, CANONICAL_GENERATED_MS, 0, CANONICAL_NOW_MS);
        assert_eq!(
            reasons_of(&missing, CANONICAL_NOW_MS),
            vec![REASON_STRUCTURE]
        );

        // 未知 id：追加 id=16 的 U32 字段。
        let mut fields = canonical_fields();
        fields.push(field(16, TYPE_U32, &7u32.to_le_bytes()));
        let unknown = assemble(&fields, 0, CANONICAL_GENERATED_MS, 0, CANONICAL_NOW_MS);
        assert_eq!(
            reasons_of(&unknown, CANONICAL_NOW_MS),
            vec![REASON_STRUCTURE, REASON_UNKNOWN_FIELD]
        );

        // 尾随 1 字节。
        let mut trailing = canonical();
        trailing.push(0);
        assert_eq!(
            reasons_of(&trailing, CANONICAL_NOW_MS),
            vec![REASON_STRUCTURE]
        );

        // 截断 1 字节。
        let mut truncated = canonical();
        truncated.pop();
        assert_eq!(
            reasons_of(&truncated, CANONICAL_NOW_MS),
            vec![REASON_STRUCTURE]
        );
    }

    #[test]
    fn field_type_length_and_flags_are_structural() {
        // 字段 flags 非 0。
        let mut flagged = field(FIELD_OPERATION, TYPE_U8, &[OPERATION_EXPORT]);
        flagged[3] = 1;
        assert_eq!(
            reasons_of(&with_field(FIELD_OPERATION, flagged), CANONICAL_NOW_MS),
            vec![REASON_STRUCTURE]
        );

        // U8 字段长度 2。
        let long_u8 = field(FIELD_OPERATION, TYPE_U8, &[1, 2]);
        assert_eq!(
            reasons_of(&with_field(FIELD_OPERATION, long_u8), CANONICAL_NOW_MS),
            vec![REASON_STRUCTURE]
        );

        // 未知类型码。
        let unknown_type = field(FIELD_ROOT_COUNT, 9, &1u32.to_le_bytes());
        assert_eq!(
            reasons_of(
                &with_field(FIELD_ROOT_COUNT, unknown_type),
                CANONICAL_NOW_MS
            ),
            vec![REASON_STRUCTURE]
        );

        // 已知 id 用错类型（U8 长 1 对 U32 字段）。
        let wrong_type = field(FIELD_ROOT_COUNT, TYPE_U8, &[1]);
        assert_eq!(
            reasons_of(&with_field(FIELD_ROOT_COUNT, wrong_type), CANONICAL_NOW_MS),
            vec![REASON_STRUCTURE]
        );

        // 摘要长度 31 / 33。
        for length in [31usize, 33] {
            let digest = field(FIELD_SOURCE_DIGEST, TYPE_BYTES32, &padded(length));
            assert_eq!(
                reasons_of(&with_field(FIELD_SOURCE_DIGEST, digest), CANONICAL_NOW_MS),
                vec![REASON_STRUCTURE]
            );
        }
        let digest = field(FIELD_PLAN_DIGEST, TYPE_BYTES32, &padded(31));
        assert_eq!(
            reasons_of(&with_field(FIELD_PLAN_DIGEST, digest), CANONICAL_NOW_MS),
            vec![REASON_STRUCTURE]
        );
    }

    #[test]
    fn numeric_ranges_match_the_frozen_table() {
        let cases: Vec<(u16, u8, Vec<u8>, bool)> = vec![
            (
                FIELD_FORMAT_VERSION,
                TYPE_U32,
                5u32.to_le_bytes().to_vec(),
                true,
            ),
            (
                FIELD_FORMAT_VERSION,
                TYPE_U32,
                4u32.to_le_bytes().to_vec(),
                false,
            ),
            (FIELD_OPERATION, TYPE_U8, vec![0], false),
            (FIELD_OPERATION, TYPE_U8, vec![1], true),
            (FIELD_OPERATION, TYPE_U8, vec![3], true),
            (FIELD_OPERATION, TYPE_U8, vec![4], false),
            (FIELD_INTEGRITY, TYPE_U8, vec![0], false),
            (FIELD_INTEGRITY, TYPE_U8, vec![4], true),
            (FIELD_INTEGRITY, TYPE_U8, vec![5], false),
            (
                FIELD_ROOT_COUNT,
                TYPE_U32,
                0u32.to_le_bytes().to_vec(),
                false,
            ),
            (
                FIELD_ROOT_COUNT,
                TYPE_U32,
                1u32.to_le_bytes().to_vec(),
                true,
            ),
            (
                FIELD_ROOT_COUNT,
                TYPE_U32,
                64u32.to_le_bytes().to_vec(),
                true,
            ),
            (
                FIELD_ROOT_COUNT,
                TYPE_U32,
                65u32.to_le_bytes().to_vec(),
                false,
            ),
            (
                FIELD_ENTRY_COUNT,
                TYPE_U32,
                0u32.to_le_bytes().to_vec(),
                false,
            ),
            (
                FIELD_ENTRY_COUNT,
                TYPE_U32,
                1u32.to_le_bytes().to_vec(),
                true,
            ),
            (
                FIELD_ENTRY_COUNT,
                TYPE_U32,
                100_000u32.to_le_bytes().to_vec(),
                true,
            ),
            (
                FIELD_ENTRY_COUNT,
                TYPE_U32,
                100_001u32.to_le_bytes().to_vec(),
                false,
            ),
            (
                FIELD_BYTE_COUNT,
                TYPE_U64,
                0u64.to_le_bytes().to_vec(),
                true,
            ),
            (
                FIELD_BYTE_COUNT,
                TYPE_U64,
                MAX_BYTE_COUNT.to_le_bytes().to_vec(),
                true,
            ),
            (
                FIELD_BYTE_COUNT,
                TYPE_U64,
                (MAX_BYTE_COUNT + 1).to_le_bytes().to_vec(),
                false,
            ),
            (
                FIELD_SELECTED_MASK,
                TYPE_U32,
                0u32.to_le_bytes().to_vec(),
                false,
            ),
            (
                FIELD_SELECTED_MASK,
                TYPE_U32,
                1u32.to_le_bytes().to_vec(),
                true,
            ),
            (
                FIELD_SELECTED_MASK,
                TYPE_U32,
                31u32.to_le_bytes().to_vec(),
                true,
            ),
            (
                FIELD_SELECTED_MASK,
                TYPE_U32,
                32u32.to_le_bytes().to_vec(),
                false,
            ),
            (FIELD_TARGET_MAPPING_KIND, TYPE_U8, vec![0], false),
            (FIELD_TARGET_MAPPING_KIND, TYPE_U8, vec![1], true),
            (FIELD_TARGET_MAPPING_KIND, TYPE_U8, vec![4], true),
            (FIELD_TARGET_MAPPING_KIND, TYPE_U8, vec![5], false),
            (
                FIELD_PLUGIN_COUNT,
                TYPE_U32,
                0u32.to_le_bytes().to_vec(),
                true,
            ),
            (
                FIELD_PLUGIN_COUNT,
                TYPE_U32,
                4_096u32.to_le_bytes().to_vec(),
                true,
            ),
            (
                FIELD_PLUGIN_COUNT,
                TYPE_U32,
                4_097u32.to_le_bytes().to_vec(),
                false,
            ),
            (
                FIELD_CREATED_AT_MS,
                TYPE_U64,
                0u64.to_le_bytes().to_vec(),
                true,
            ),
            (
                FIELD_CREATED_AT_MS,
                TYPE_U64,
                u64::MAX.to_le_bytes().to_vec(),
                true,
            ),
        ];
        for (id, type_code, value, valid) in cases {
            let view = with_field(id, field(id, type_code, &value));
            let reasons = reasons_of(&view, CANONICAL_NOW_MS);
            if valid {
                assert_eq!(reasons, Vec::<u32>::new(), "id={id} value={value:?}");
            } else {
                assert_eq!(reasons, vec![REASON_FIELD_RANGE], "id={id} value={value:?}");
            }
        }
    }

    #[test]
    fn string_rules_cover_caps_nul_utf8_and_closed_policy_set() {
        let long_app_version = padded(257);
        assert_eq!(
            reasons_of(
                &with_field(
                    FIELD_APP_VERSION,
                    field(FIELD_APP_VERSION, TYPE_UTF8, &long_app_version)
                ),
                CANONICAL_NOW_MS
            ),
            vec![REASON_FIELD_RANGE]
        );

        let long_data_format = padded(65);
        assert_eq!(
            reasons_of(
                &with_field(
                    FIELD_DATA_FORMAT,
                    field(FIELD_DATA_FORMAT, TYPE_UTF8, &long_data_format)
                ),
                CANONICAL_NOW_MS
            ),
            vec![REASON_FIELD_RANGE]
        );

        // 4097 字节同时违反类型长度上限（STRUCTURE）与字段上限（FIELD_RANGE）。
        let too_long = padded(4_097);
        assert_eq!(
            reasons_of(
                &with_field(
                    FIELD_DATA_FORMAT,
                    field(FIELD_DATA_FORMAT, TYPE_UTF8, &too_long)
                ),
                CANONICAL_NOW_MS
            ),
            vec![REASON_STRUCTURE, REASON_FIELD_RANGE]
        );

        // 空串：类型长度 1..=4096 与非空规则同时失败。
        assert_eq!(
            reasons_of(
                &with_field(FIELD_DATA_FORMAT, utf8_field(FIELD_DATA_FORMAT, "")),
                CANONICAL_NOW_MS
            ),
            vec![REASON_STRUCTURE, REASON_FIELD_RANGE]
        );

        // NUL 与非法 UTF-8。
        assert_eq!(
            reasons_of(
                &with_field(FIELD_DATA_FORMAT, utf8_field(FIELD_DATA_FORMAT, "A\u{0}B")),
                CANONICAL_NOW_MS
            ),
            vec![REASON_FIELD_RANGE]
        );
        assert_eq!(
            reasons_of(
                &with_field(
                    FIELD_DATA_FORMAT,
                    field(FIELD_DATA_FORMAT, TYPE_UTF8, &[0xff, 0xfe])
                ),
                CANONICAL_NOW_MS
            ),
            vec![REASON_STRUCTURE]
        );

        // sensitivePolicy 闭集：5 个生产值全部通过，未知值 FIELD_RANGE。
        for policy in SENSITIVE_POLICIES {
            let view = with_field(
                FIELD_SENSITIVE_POLICY,
                utf8_field(FIELD_SENSITIVE_POLICY, policy),
            );
            assert_eq!(validate(&view, CANONICAL_NOW_MS), Ok(()), "{policy}");
        }
        let unknown = with_field(
            FIELD_SENSITIVE_POLICY,
            utf8_field(FIELD_SENSITIVE_POLICY, "PLAINTEXTISH"),
        );
        assert_eq!(
            reasons_of(&unknown, CANONICAL_NOW_MS),
            vec![REASON_FIELD_RANGE]
        );
    }

    #[test]
    fn expiry_rules_follow_the_one_millisecond_boundaries() {
        let flags = HEADER_FLAG_HAS_EXPIRY;
        let generated = 1_000u64;
        let expires = 2_000u64;
        let fields = canonical_fields();
        let at = |now: u64| assemble(&fields, flags, generated, expires, now);

        assert_eq!(validate(&at(1_999), 1_999), Ok(()));
        assert_eq!(reasons_of(&at(2_000), 2_000), vec![REASON_EXPIRED]);
        assert_eq!(reasons_of(&at(2_000), 2_001), vec![REASON_EXPIRED]);
        assert_eq!(validate(&at(1_999), 1_999), Ok(()));
        // 视图内嵌 nowMs 与入参不同：入参覆盖（§4.3/§4.4）。
        assert_eq!(reasons_of(&at(1_000), 1_999), Vec::<u32>::new());
        assert_eq!(reasons_of(&at(1_999), 2_000), vec![REASON_EXPIRED]);

        // expires == generated / expires < generated 都归 PLAN_EXPIRED（Q3-A）。
        let degenerate = assemble(&fields, flags, 5_000, 5_000, 4_000);
        assert_eq!(reasons_of(&degenerate, 4_000), vec![REASON_EXPIRED]);
        let backwards = assemble(&fields, flags, 5_000, 4_999, 4_000);
        assert_eq!(reasons_of(&backwards, 4_000), vec![REASON_EXPIRED]);

        // hasExpiry=0 时 expires 必须为 0。
        let orphan = assemble(&fields, 0, 1_000, 1, 1_000);
        assert_eq!(reasons_of(&orphan, 1_000), vec![REASON_STRUCTURE]);
    }

    #[test]
    fn multiple_reasons_are_deduplicated_and_ascending() {
        let mut view = with_field(
            FIELD_ROOT_COUNT,
            field(FIELD_ROOT_COUNT, TYPE_U32, &0u32.to_le_bytes()),
        );
        patch_u16(&mut view, 6, 2);
        patch_u32(&mut view, 16, HEADER_FLAG_HAS_EXPIRY);
        patch_u64(&mut view, 28, 10);
        assert_eq!(
            reasons_of(&view, 10),
            vec![REASON_FIELD_RANGE, REASON_POLICY_VERSION, REASON_EXPIRED]
        );
    }

    #[test]
    fn reasons_buffer_dedupes_sorts_and_caps_at_max_reasons() {
        let mut reasons = Reasons::new();
        assert!(reasons.is_empty());
        reasons.add(REASON_UNKNOWN_FIELD);
        reasons.add(REASON_STRUCTURE);
        reasons.add(REASON_STRUCTURE);
        reasons.add(REASON_EXPIRED);
        assert_eq!(reasons.codes(), &[20, 23, 25]);
        for code in 0..12u32 {
            reasons.add(100 + code);
        }
        assert_eq!(reasons.len(), MAX_REASONS);
        let mut previous = 0;
        for code in reasons.codes() {
            assert!(*code > 0 && *code >= previous);
            previous = *code;
        }
    }

    #[test]
    fn randomized_views_are_deterministic_and_never_panic() {
        let mut state = 0x2026_1113_0000_0001u64;
        let mut next = || {
            state = state
                .wrapping_mul(6_364_136_223_846_793_005)
                .wrapping_add(1_442_695_040_888_963_407);
            state
        };
        for _ in 0..256 {
            let length = (next() % 128) as usize;
            let mut view = Vec::with_capacity(length);
            for _ in 0..length {
                view.push((next() % 256) as u8);
            }
            let first = validate(&view, 0);
            let second = validate(&view, 0);
            assert_eq!(first, second);
        }
        // 合法视图上的随机单字节破坏：任一结果都必须可重复。
        let base = canonical();
        for _ in 0..256 {
            let mut view = base.clone();
            let index = (next() as usize) % view.len();
            view[index] ^= (next() % 255 + 1) as u8;
            assert_eq!(
                validate(&view, CANONICAL_NOW_MS),
                validate(&view, CANONICAL_NOW_MS)
            );
        }
    }

    #[test]
    fn jni_entry_rejects_null_buffers_without_touching_jni() {
        let env = std::ptr::null_mut();
        let class = std::ptr::null_mut();
        let invalid = crate::abi::ERR_INVALID_ARGUMENT;
        let call = |view: *mut std::ffi::c_void,
                    view_length: i32,
                    reasons: *mut std::ffi::c_void,
                    max_reasons: i32| {
            crate::jni::Java_com_deepseekharness_app_util_RustNative_verifyPlan(
                env,
                class,
                view,
                view_length,
                reasons,
                max_reasons,
            )
        };
        assert_eq!(
            call(std::ptr::null_mut(), 0, std::ptr::null_mut(), 0),
            invalid
        );
        assert_eq!(
            call(std::ptr::null_mut(), -1, std::ptr::null_mut(), 0),
            invalid
        );
        assert_eq!(
            call(std::ptr::null_mut(), 0, std::ptr::null_mut(), -1),
            invalid
        );
    }

    // 规范视图的字节 pin：由 15 个字段（§3.3）与上述固定值确定性生成。
    const CANONICAL_VIEW_HEX: &str = "4453485001000100630100000f00000000000000f4c12cc8990100000000000000000000f4c12cc8990100000000000001000200040000000500000002000100010000000103000100010000000104000200040000000100000005000200040000002a00000006000300080000000004000000000000070003000800000000c02cc8990100000800040020000000000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f0900020004000000030000000a00010001000000010b00050026000000302e322e312d72632e352d32303236313131332e313030302d647368302e322e322d72632e310c0005000b000000554e494e535045435445440d00050023000000505249564154455f4c4f43414c5f4e41544956455f53544154455f494e5f504c4143450e000200040000000c0000000f00040020000000a0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbdbebf";
}

"""最小只读 ELF 检查：Rust 产物与 APK 内 libdsha_rs.so 共用（不依赖 readelf）。

只解析 ELF64 小端（Android arm64 / Linux aarch64 宿主）：

- `machine`：e_machine（183 = EM_AARCH64）；
- `aligned_16k`：每个 PT_LOAD 的 p_align >= 16384 且 (p_offset - p_vaddr) % 16384 == 0；
- `relro_mapped`：PT_GNU_RELRO 区间在 4 KiB / 16 KiB 页映射内都能落到某个 PT_LOAD；
- `needed`：PT_DYNAMIC 的 DT_NEEDED 字符串（按出现顺序去重）。

这些是静态检查，不替代真机 16 KB 内核加载。
"""
from __future__ import annotations

import struct
from pathlib import Path

ELF_MAGIC = b"\x7fELF\x02\x01"
EM_AARCH64 = 183
PT_LOAD = 1
PT_DYNAMIC = 2
PT_GNU_RELRO = 0x6474E552
DT_NULL = 0
DT_NEEDED = 1
DT_STRTAB = 5
DT_STRSZ = 10


class ElfError(ValueError):
    """ELf 结构不合法；调用方必须失败而不是猜测。"""


def _read(data: bytes, offset: int, size: int) -> bytes:
    if offset < 0 or size < 0 or offset + size > len(data):
        raise ElfError("ELF 截断: offset=%d size=%d" % (offset, size))
    return data[offset:offset + size]


def inspect_bytes(data: bytes) -> dict:
    """返回 ELF 静态契约；非 ELF64 小端直接抛 ElfError。"""
    if len(data) < 64 or data[:6] != ELF_MAGIC:
        raise ElfError("不是 ELF64 小端文件")
    kind, machine = struct.unpack_from("<HH", data, 16)
    if kind not in (2, 3):
        raise ElfError("e_type 不是 EXEC/DYN: %d" % kind)
    phoff = struct.unpack_from("<Q", data, 32)[0]
    phentsize, phnum = struct.unpack_from("<HH", data, 54)
    if not phnum or phentsize < 56 or phoff + phentsize * phnum > len(data):
        raise ElfError("无法读取程序头")

    loads: list[tuple[int, int]] = []
    loaded: list[tuple[int, int, int]] = []  # (vaddr_start, vaddr_end, file_offset)
    relros: list[tuple[int, int]] = []
    aligned = True
    dynamic: tuple[int, int] | None = None
    for index in range(phnum):
        base = phoff + index * phentsize
        p_type = struct.unpack_from("<I", data, base)[0]
        p_offset, p_vaddr = struct.unpack_from("<QQ", data, base + 8)
        p_filesz = struct.unpack_from("<Q", data, base + 32)[0]
        p_memsz = struct.unpack_from("<Q", data, base + 40)[0]
        p_align = struct.unpack_from("<Q", data, base + 48)[0]
        if p_type == PT_LOAD:
            loads.append((p_vaddr, p_vaddr + p_memsz))
            loaded.append((p_vaddr, p_vaddr + p_memsz, p_offset))
            if p_align < 16384 or (p_offset - p_vaddr) % 16384 != 0:
                aligned = False
        elif p_type == PT_GNU_RELRO:
            relros.append((p_vaddr, p_vaddr + p_memsz))
        elif p_type == PT_DYNAMIC:
            dynamic = (p_offset, p_filesz)

    relro_mapped = True
    for page in (4096, 16384):
        intervals = sorted((start // page * page, (end + page - 1) // page * page)
                           for start, end in loads)
        for start, end in relros:
            cursor, limit = start // page * page, (end + page - 1) // page * page
            for left, right in intervals:
                if left <= cursor < right:
                    cursor = right
            relro_mapped &= cursor >= limit

    needed: list[str] = []
    if dynamic is not None:
        offset, size = dynamic
        entries = []
        for base in range(offset, offset + size, 16):
            if base + 16 > len(data):
                break
            tag, value = struct.unpack_from("<QQ", data, base)
            entries.append((tag, value))
            if tag == DT_NULL:
                break
        strtab_vaddr = next((value for tag, value in entries if tag == DT_STRTAB), None)
        strsz = next((value for tag, value in entries if tag == DT_STRSZ), 0)
        strings = b""
        if strtab_vaddr is not None:
            for start, end, file_offset in loaded:
                if start <= strtab_vaddr < end:
                    strings = _read(data, file_offset + strtab_vaddr - start, strsz)
                    break
            else:
                raise ElfError("DT_STRTAB 不在任何 PT_LOAD 内")
        for tag, value in entries:
            if tag != DT_NEEDED:
                continue
            if value >= len(strings):
                raise ElfError("DT_NEEDED 偏移越界")
            end = strings.find(b"\0", value)
            if end < 0:
                raise ElfError("DT_NEEDED 字符串未终止")
            name = strings[value:end].decode("utf-8", "strict")
            if name not in needed:
                needed.append(name)

    return dict(machine=machine, aligned_16k=aligned, relro_mapped=relro_mapped,
                needed=needed, program_loads=loads, relro_ranges=relros)


def inspect(path: Path | str) -> dict:
    return inspect_bytes(Path(path).read_bytes())


SHT_DYNSYM = 11
STB_GLOBAL = 1
STB_WEAK = 2
STT_FUNC = 2


def exported_functions(data: bytes) -> list[str]:
    """返回 .dynsym 中已定义（st_shndx != SHN_UNDEF）的全局/弱函数符号名。"""
    if len(data) < 64 or data[:6] != ELF_MAGIC:
        raise ElfError("不是 ELF64 小端文件")
    shoff = struct.unpack_from("<Q", data, 40)[0]
    shentsize, shnum = struct.unpack_from("<HH", data, 58)
    if not shnum or shentsize < 64 or shoff + shentsize * shnum > len(data):
        raise ElfError("无法读取节头")
    sections = []
    for index in range(shnum):
        base = shoff + index * shentsize
        sh_name, sh_type = struct.unpack_from("<II", data, base)
        sh_offset, sh_size = struct.unpack_from("<QQ", data, base + 24)
        sh_link, _sh_info = struct.unpack_from("<II", data, base + 40)
        sh_entsize = struct.unpack_from("<Q", data, base + 56)[0]
        sections.append(dict(name=sh_name, type=sh_type, offset=sh_offset,
                             size=sh_size, link=sh_link, entsize=sh_entsize))
    symbols: list[str] = []
    for section in sections:
        if section["type"] != SHT_DYNSYM:
            continue
        if section["link"] >= len(sections):
            raise ElfError("SHT_DYNSYM 的字符串表链接越界")
        string_section = sections[section["link"]]
        strings = _read(data, string_section["offset"], string_section["size"])
        entsize = section["entsize"] or 24
        if entsize < 24:
            raise ElfError("SHT_DYNSYM 条目过小")
        for base in range(section["offset"], section["offset"] + section["size"], entsize):
            entry = _read(data, base, 24)
            st_name = struct.unpack_from("<I", entry, 0)[0]
            st_info = entry[4]
            st_shndx = struct.unpack_from("<H", entry, 6)[0]
            binding, kind = st_info >> 4, st_info & 0x0F
            if st_shndx == 0 or binding not in (STB_GLOBAL, STB_WEAK) or kind != STT_FUNC:
                continue
            end = strings.find(b"\0", st_name)
            if st_name >= len(strings) or end < 0:
                raise ElfError("符号名字符串未终止")
            symbols.append(strings[st_name:end].decode("utf-8", "strict"))
    return sorted(symbols)


def inspect_with_symbols(path: Path | str) -> dict:
    data = Path(path).read_bytes()
    return {**inspect_bytes(data), "exported_functions": exported_functions(data)}

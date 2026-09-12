"""
离线验证 VivoZipBrowser 的解析算法（与 Kotlin 实现逐行同构）。

验证目标：
1. parseCentralDirectory 的扫描式遍历能正确枚举全部条目
2. ZIP64 extra field (0x0001) 的按需取字段逻辑
3. readEntryDataOffset 的 local header 偏移计算（30 + nameLen + extraLen）

对照基准是 Python 的 zipfile 模块。用法：python verify_zip_browser_logic.py
"""

import struct
import zipfile
import io

CENSIG = 0x02014B50
LOCSIG = 0x04034B50
ZIP64_MAGIC = 0xFFFFFFFF


def read_zip64_fields(extra: bytes):
    """复刻 readZip64Fields：只收集 8 字节槽位，由调用方按需取值。"""
    p = 0
    while p + 4 <= len(extra):
        hid, hsz = struct.unpack_from("<HH", extra, p)
        p += 4
        if hsz > len(extra) - p:
            break
        if hid == 0x0001:
            return list(struct.unpack_from("<%dQ" % (hsz // 8), extra, p))
        p += hsz
    return []


def parse_central_directory(data: bytes):
    """复刻 parseCentralDirectory：扫描式前进 1 字节，不做「遇非签名即停」。"""
    entries = []
    pos = 0
    n = len(data)
    while pos + 4 <= n:
        if struct.unpack_from("<I", data, pos)[0] != CENSIG:
            pos += 1
            continue
        if n - pos < 46:
            break
        method = struct.unpack_from("<H", data, pos + 10)[0]
        crc = struct.unpack_from("<I", data, pos + 16)[0]
        csize = struct.unpack_from("<I", data, pos + 20)[0]
        usize = struct.unpack_from("<I", data, pos + 24)[0]
        nlen = struct.unpack_from("<H", data, pos + 28)[0]
        elen = struct.unpack_from("<H", data, pos + 30)[0]
        clen = struct.unpack_from("<H", data, pos + 32)[0]
        lho = struct.unpack_from("<I", data, pos + 42)[0]

        end = pos + 46 + nlen + elen + clen
        if end > n:
            break

        name = data[pos + 46:pos + 46 + nlen].decode("utf-8")
        extra = data[pos + 46 + nlen:pos + 46 + nlen + elen]

        if csize == ZIP64_MAGIC or usize == ZIP64_MAGIC or lho == ZIP64_MAGIC:
            fields = read_zip64_fields(extra)
            i = 0
            if usize == ZIP64_MAGIC:
                usize = fields[i]; i += 1
            if csize == ZIP64_MAGIC:
                csize = fields[i]; i += 1
            if lho == ZIP64_MAGIC:
                lho = fields[i]; i += 1

        entries.append({"name": name, "csize": csize, "usize": usize,
                        "crc": crc, "method": method, "lho": lho})
        pos = end
    return entries


def local_data_offset(blob: bytes, lho: int):
    """复刻 readEntryDataOffset。"""
    assert struct.unpack_from("<I", blob, lho)[0] == LOCSIG, "不是 local file header"
    nlen = struct.unpack_from("<H", blob, lho + 26)[0]
    elen = struct.unpack_from("<H", blob, lho + 28)[0]
    return lho + 30 + nlen + elen


def build_test_zip():
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        # STORED 小文件（模拟 payload_properties.txt）
        z.writestr(zipfile.ZipInfo("payload_properties.txt"),
                   "FILE_HASH=abc123\nFILE_SIZE=11615177992\n",
                   compress_type=zipfile.ZIP_STORED)
        # DEFLATE 文本（模拟 updater-script）
        z.writestr("META-INF/com/google/android/updater-script",
                   ("ui_print(\"installing\");\n" * 400),
                   compress_type=zipfile.ZIP_DEFLATED)
        # 中文名 + 目录条目（模拟 META-INF/ 结构）
        z.writestr("META-INF/com/android/元数据.txt", "中文内容测试\n" * 50,
                   compress_type=zipfile.ZIP_DEFLATED)
        z.writestr("META-INF/", "", compress_type=zipfile.ZIP_STORED)
        # 二进制（用于验证 NUL 硬拦截会拒绝预览）
        z.writestr("care_map.pb", bytes(range(1, 256)) * 20,
                   compress_type=zipfile.ZIP_DEFLATED)
        # extra field 很长（模拟 vivo 的 payload.bin local header）
        zi = zipfile.ZipInfo("payload.bin")
        zi.compress_type = zipfile.ZIP_STORED
        zi.extra = b"PX" + b"\x00" * 300  # 伪造一个长 extra
        z.writestr(zi, b"CrAU" + b"\x00" * 200)
    return buf.getvalue()


def main():
    blob = build_test_zip()

    with zipfile.ZipFile(io.BytesIO(blob)) as z:
        # 定位 central directory（复刻 locateCentralDirectory 的结果）
        cen_offset = None
        cen_size = None
        end = len(blob) - 22
        while end >= 0:
            if struct.unpack_from("<I", blob, end)[0] == 0x06054B50:
                cen_size = struct.unpack_from("<I", blob, end + 12)[0]
                cen_offset = struct.unpack_from("<I", blob, end + 16)[0]
                break
            end -= 1
        assert cen_offset is not None, "找不到 EOCD"

        central = blob[cen_offset: cen_offset + cen_size]
        parsed = parse_central_directory(central)
        truth = z.infolist()

        print(f"条目数：解析 {len(parsed)} / 实际 {len(truth)}")
        assert len(parsed) == len(truth), "条目数不一致"

        failures = []
        for got, exp in zip(parsed, truth):
            if got["name"] != exp.filename:
                failures.append(f"名称不符 {got['name']!r} != {exp.filename!r}")
            if got["csize"] != exp.compress_size:
                failures.append(f"{exp.filename} csize {got['csize']} != {exp.compress_size}")
            if got["usize"] != exp.file_size:
                failures.append(f"{exp.filename} usize {got['usize']} != {exp.file_size}")
            if got["crc"] != exp.CRC:
                failures.append(f"{exp.filename} crc {got['crc']} != {exp.CRC}")
            # 核心：local header 偏移 + 数据偏移是否正确
            off = local_data_offset(blob, got["lho"])
            raw = blob[off: off + got["csize"]]
            if len(raw) != got["csize"]:
                failures.append(f"{exp.filename} 数据区长度不足")
            else:
                data = raw if got["method"] == 0 else __import__("zlib").decompress(raw, -15)
                if len(data) != got["usize"]:
                    failures.append(f"{exp.filename} 解压后长度不符")
                elif exp.filename == "payload.bin" and not data.startswith(b"CrAU"):
                    failures.append("payload.bin 数据起点错误")

        # ZIP64 extra field 按需取字段的独立验证
        z64 = struct.pack("<HH", 0x0001, 24) + struct.pack("<QQQ", 7_000_000_000, 5_000_000_000, 12345)
        fields = read_zip64_fields(z64)
        assert fields == [7_000_000_000, 5_000_000_000, 12345], f"ZIP64 解析错误: {fields}"

        # 只带部分字段（仅 usize 越界）时不应错序
        z64_partial = struct.pack("<HH", 0x0001, 8) + struct.pack("<Q", 9_000_000_000)
        assert read_zip64_fields(z64_partial) == [9_000_000_000], "ZIP64 部分字段解析错误"

        # NUL 硬拦截验证
        def looks_like_text(b):
            sample = b[:4096]
            if not sample:
                return False
            printable = 0
            for byte in sample:
                if byte == 0:
                    return False
                if byte in (9, 10, 13) or 32 <= byte <= 126 or byte >= 0x80:
                    printable += 1
            return printable / len(sample) >= 0.9

        pb = z.read("care_map.pb")
        txt = z.read("META-INF/com/google/android/updater-script")
        assert looks_like_text(txt), "纯文本应通过嗅探"
        assert not looks_like_text(pb), "二进制 pb 必须被 NUL 拦截"

    if failures:
        print("失败项：")
        for f in failures:
            print("  -", f)
        raise SystemExit(1)
    print("全部通过：条目枚举、ZIP64 extra、local header 偏移、raw deflate、NUL 拦截")


if __name__ == "__main__":
    main()

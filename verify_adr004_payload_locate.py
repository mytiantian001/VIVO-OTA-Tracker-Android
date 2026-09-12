# -*- coding: utf-8 -*-
"""
验证 ADR-004 的 payload.bin 定位改造（与 Kotlin 实现逐行同构）。

对照两组实现：
- OLD：PayloadUtil.getPayloadOffset（尾部 4KB EOCD + locateLocalFileHeader + 256KB 缓冲）
- NEW：VivoZipBrowser（listZipEntries + parseCentralDirectory + readEntryDataOffset 1KB 首读）

验证目标：
1. 常规包（payload.bin STORED、extra 21B）两组结果一致，且等于历史基线 61
2. 超长 extra field：NEW 的「1KB 首读 + 精确补读」与 OLD 的 256KB 结果一致，且流量大幅下降
3. ZIP64（localHeaderOffset 为 0xFFFFFFFF）：OLD 失效，NEW 正确
4. payload.bin 为 DEFLATE：NEW 明确报 PAYLOAD_NOT_STORED，OLD 静默返回错误偏移
5. 无 payload.bin（recovery 全量包）：两组都报 NOT_A_PAYLOAD_ZIP

本项目无 JVM 单测基建，沿用 verify_zip_browser_logic.py 的同构验证方式。
用法：python verify_adr004_payload_locate.py
"""

import struct
import zlib

LOCSIG = 0x04034B50
CENSIG = 0x02014B50
ENDSIG = 0x06054B50
ZIP64_ENDSIG = 0x06064B50
ZIP64_LOCSIG = 0x07064B50
ZIP64_MAGIC = 0xFFFFFFFF

ENDHDR = 22
ZIP64_LOCHDR = 20
OLD_LOCAL_HEADER_BYTES = 256 * 1024
NEW_LOCAL_HEADER_FIRST_READ = 1024
TAIL_BYTES = 4096

PAYLOAD_NAME = "payload.bin"


# ---------------- zip 构造（手工拼字节，便于控制 extra 长度与 ZIP64 标记） ----------------

def build_zip(entries, zip64=False, lho_override=None, extra_central=None):
    out = bytearray()
    central = []
    for e in entries:
        lho = len(out)
        name = e["name"].encode()
        data = e.get("data", b"")
        method = e.get("method", 0)
        el = e.get("extra_local", b"")
        crc = zlib.crc32(data) & 0xFFFFFFFF
        out += struct.pack("<IHHHHHIIIHH", LOCSIG, 45, 0, method, 0, 0, crc, len(data), len(data), len(name), len(el))
        out += name + el + data
        central.append((name, lho, len(data), len(data), method, crc))
    cd_off = len(out)
    for idx, (name, lho, csize, usize, method, crc) in enumerate(central):
        ec = (extra_central or b"") if name.decode() == PAYLOAD_NAME else b""
        lho_field = lho_override if (lho_override is not None and name.decode() == PAYLOAD_NAME) else lho
        usize_field = ZIP64_MAGIC if zip64 and name.decode() == PAYLOAD_NAME else usize
        csize_field = ZIP64_MAGIC if zip64 and name.decode() == PAYLOAD_NAME else csize
        out += struct.pack(
            "<IHHHHHHIIIHHHHHII", CENSIG, 0x031E, 45, 0, method, 0, 0,
            crc, csize_field, usize_field,
            len(name), len(ec), 0, 0, 0, 0, lho_field
        )
        out += name + ec
    cd_size = len(out) - cd_off

    if zip64:
        z64_off = len(out)
        out += struct.pack("<IQHHIIQQQQ", ZIP64_ENDSIG, 44, 45, 45, 0, 0, len(central), len(central), cd_size, cd_off)
        out += struct.pack("<IIQI", ZIP64_LOCSIG, 0, z64_off, 1)
        out += struct.pack("<IHHHHIIH", ENDSIG, 0, 0, len(central), len(central), ZIP64_MAGIC, ZIP64_MAGIC, 0)
    else:
        out += struct.pack("<IHHHHIIH", ENDSIG, 0, 0, len(central), len(central), cd_size, cd_off, 0)
    return bytes(out)


# ---------------- OLD：PayloadUtil ----------------

def old_locate_central_directory(tail, file_length):
    cen_size = -1
    cen_offset = -1
    n = len(tail)
    for i in range(0, n - ENDHDR + 1):
        pos = (n - ENDHDR) - i
        if struct.unpack_from("<I", tail, pos)[0] != ENDSIG:
            continue
        p = pos + 4
        # 复刻 Kotlin：读到 ENDSIG 后 position 前进 12，取 4 字节判断是否为 0xFFFFFFFF
        if struct.unpack_from("<I", tail, p + 12)[0] == ZIP64_MAGIC:
            lp = p - ZIP64_LOCHDR - 4
            if struct.unpack_from("<I", tail, lp)[0] == ZIP64_LOCSIG:
                z64 = struct.unpack_from("<Q", tail, lp + 8)[0]
                zp = n - (file_length - z64)
                if struct.unpack_from("<I", tail, zp)[0] == ZIP64_ENDSIG:
                    cen_size = struct.unpack_from("<q", tail, zp + 40)[0]
                    cen_offset = struct.unpack_from("<q", tail, zp + 48)[0]
        else:
            cen_size = struct.unpack_from("<I", tail, p + 8)[0]
            cen_offset = struct.unpack_from("<I", tail, p + 12)[0]
            break
    return cen_offset, cen_size


def old_locate_local_file_header(cd, file_name):
    pos = 0
    n = len(cd)
    while pos + 4 <= n:
        if struct.unpack_from("<I", cd, pos)[0] != CENSIG:
            return -1  # OLD：遇非签名即 break
        p = pos + 4 + 24
        name_len = struct.unpack_from("<H", cd, p)[0]
        extra_len = struct.unpack_from("<H", cd, p + 2)[0]
        comment_len = struct.unpack_from("<H", cd, p + 4)[0]
        # CENSIG 固定部分：读三个 short 后 position=pos+34，Kotlin 再前进 8 字节取 lho(pos+42)
        lho = struct.unpack_from("<I", cd, p + 14)[0]
        # 文件名紧随 46 字节固定部分（p=pos+28，故 name 在 p+18）
        name = cd[p + 18:p + 18 + name_len].decode("utf-8")
        if name.endswith("payload.bin") or file_name == name:
            return lho
        pos = p + 18 + name_len + extra_len + comment_len
    return -1


def old_locate_local_file_offset(local):
    if struct.unpack_from("<I", local, 0)[0] != LOCSIG:
        return -1
    name_len = struct.unpack_from("<H", local, 26)[0]
    extra_len = struct.unpack_from("<H", local, 28)[0]
    data_offset = 30 + name_len + extra_len
    if data_offset > len(local):
        return -1
    return data_offset


def old_payload_offset(data):
    """返回 (offset, 流量字节数)。"""
    tail = data[-TAIL_BYTES:]
    traffic = len(tail)
    cen_offset, cen_size = old_locate_central_directory(tail, len(data))
    if cen_offset < 0 or cen_size <= 0:
        raise IOException("NOT_A_VALID_ZIP")
    cd = data[cen_offset:cen_offset + cen_size]
    traffic += len(cd)
    lho = old_locate_local_file_header(cd, PAYLOAD_NAME)
    if lho < 0:
        raise IOException("NOT_A_PAYLOAD_ZIP")
    local = data[lho:lho + OLD_LOCAL_HEADER_BYTES]
    traffic += len(local)
    off = old_locate_local_file_offset(local)
    if off < 0:
        raise IOException("Failed to parse payload.bin local header")
    # OLD 另有 32B + 64B 两次调试探针
    traffic += 32 + 64
    return lho + off, traffic


# ---------------- NEW：VivoZipBrowser ----------------

def read_zip64_fields(extra):
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


def parse_central_directory(data):
    entries = []
    pos = 0
    n = len(data)
    while pos + 4 <= n:
        if struct.unpack_from("<I", data, pos)[0] != CENSIG:
            pos += 1  # NEW：扫描式前进 1 字节
            continue
        if n - pos < 46:
            break
        method = struct.unpack_from("<H", data, pos + 10)[0]
        csize = struct.unpack_from("<I", data, pos + 20)[0]
        usize = struct.unpack_from("<I", data, pos + 24)[0]
        name_len = struct.unpack_from("<H", data, pos + 28)[0]
        extra_len = struct.unpack_from("<H", data, pos + 30)[0]
        comment_len = struct.unpack_from("<H", data, pos + 32)[0]
        lho = struct.unpack_from("<I", data, pos + 42)[0]
        end = pos + 46 + name_len + extra_len + comment_len
        if end > n:
            break
        name = data[pos + 46:pos + 46 + name_len].decode("utf-8")
        extra = data[pos + 46 + name_len:pos + 46 + name_len + extra_len]
        if ZIP64_MAGIC in (csize, usize, lho):
            fields = read_zip64_fields(extra)
            i = 0
            if usize == ZIP64_MAGIC and i < len(fields):
                usize = fields[i]
                i += 1
            if csize == ZIP64_MAGIC and i < len(fields):
                csize = fields[i]
                i += 1
            if lho == ZIP64_MAGIC and i < len(fields):
                lho = fields[i]
                i += 1
        entries.append({"name": name, "method": method, "lho": lho, "csize": csize, "usize": usize})
        pos = end
    return entries


def new_payload_offset(data):
    """返回 (offset, 流量字节数)。"""
    tail = data[-min(TAIL_BYTES, len(data)):]
    traffic = len(tail)
    cen_offset, cen_size = old_locate_central_directory(tail, len(data))  # 迁入后逻辑不变
    if cen_offset < 0 or cen_size <= 0:
        raise IOException("NOT_A_VALID_ZIP")
    cd = data[cen_offset:cen_offset + cen_size]
    traffic += len(cd)
    entries = parse_central_directory(cd)
    entry = next((e for e in entries if e["name"].endswith("payload.bin")), None)
    if entry is None:
        raise IOException("NOT_A_PAYLOAD_ZIP")
    if entry["method"] != 0:
        raise IOException("PAYLOAD_NOT_STORED")

    first = min(NEW_LOCAL_HEADER_FIRST_READ, len(data) - entry["lho"])
    header = data[entry["lho"]:entry["lho"] + first]
    traffic += len(header)
    if len(header) < 30:
        raise IOException("BAD_LOCAL_HEADER")
    name_len = struct.unpack_from("<H", header, 26)[0]
    extra_len = struct.unpack_from("<H", header, 28)[0]
    data_offset = 30 + name_len + extra_len
    if data_offset > len(header):
        header = data[entry["lho"]:entry["lho"] + data_offset]  # 精确补读
        traffic += len(header)
        if len(header) < data_offset:
            raise IOException("BAD_LOCAL_HEADER")
    return entry["lho"] + data_offset, traffic


# ---------------- 用例 ----------------

class IOException(Exception):
    pass


def case_baseline():
    """实测 vivo 包形态：payload.bin 首个条目、STORED、extra 20B。

    vivo_payload_log.txt 的历史基线：30 + len("payload.bin") + 20 = 61。
    """
    data = build_zip([
        {"name": PAYLOAD_NAME, "data": b"CrAU" + b"\x00" * 200, "extra_local": b"\x00" * 20},
        {"name": "META-INF/com/android/metadata", "data": b"ota-property-files=1\n"},
    ])
    return data, 30 + len(PAYLOAD_NAME) + 20


def case_long_extra():
    """vivo 的 extra field 可达数万字节。

    包体必须 >256KB，否则 OLD 的 256KB 缓冲会被文件长度截断，
    反而显得比 NEW 省流量（小文件假象）。
    """
    data = build_zip([
        {"name": PAYLOAD_NAME, "data": b"CrAU" + b"\x00" * 200, "extra_local": b"\x00" * 50000},
        {"name": "pad.img", "data": b"\x00" * (300 * 1024)},
    ])
    return data, 30 + len(PAYLOAD_NAME) + 50000


def case_zip64():
    """localHeaderOffset 置 0xFFFFFFFF，由 ZIP64 extra 提供真实值。"""
    real_lho = 0
    extra = struct.pack("<HH", 0x0001, 24) + struct.pack("<QQQ", 1024, 1024, real_lho)
    data = build_zip(
        [{"name": PAYLOAD_NAME, "data": b"CrAU" + b"\x00" * 200, "extra_local": b"\x00" * 20}],
        zip64=True, lho_override=ZIP64_MAGIC, extra_central=extra
    )
    return data, real_lho + 30 + len(PAYLOAD_NAME) + 20


def case_deflate():
    data = build_zip([{"name": PAYLOAD_NAME, "data": b"\x78\x9c" + b"\x00" * 100, "method": 8}])
    return data, None


def case_no_payload():
    data = build_zip([{"name": "super.img", "data": b"\x00" * 100}, {"name": "vbmeta.img", "data": b"\x00" * 10}])
    return data, None


def main():
    results = []

    # 1. 常规包：两组一致，且等于历史基线 61
    data, expect = case_baseline()
    o, ot = old_payload_offset(data)
    n, nt = new_payload_offset(data)
    results.append(("baseline OLD==NEW==61", o == n == expect, "old=%d new=%d" % (o, n)))
    results.append(("baseline 流量下降", nt < ot, "old=%dB new=%dB" % (ot, nt)))

    # 2. 超长 extra：结果一致，且 NEW 不再无脑读 256KB
    data, expect = case_long_extra()
    o, ot = old_payload_offset(data)
    n, nt = new_payload_offset(data)
    results.append(("long extra OLD==NEW", o == n == expect, "old=%d new=%d" % (o, n)))
    results.append(("long extra 流量下降", nt < ot, "old=%dB new=%dB" % (ot, nt)))

    # 3. ZIP64：OLD 失效，NEW 正确
    data, expect = case_zip64()
    try:
        o = old_payload_offset(data)[0]
        old_ok = (o == expect)
    except Exception as e:
        # OLD 拿到 0xFFFFFFFF 后直接越界/缓冲下溢，属预期失效
        o, old_ok = type(e).__name__, False
    n = new_payload_offset(data)[0]
    results.append(("zip64 NEW 正确", n == expect, "new=%d expect=%d" % (n, expect)))
    results.append(("zip64 OLD 失效（已知缺陷）", not old_ok, "old=%s" % (o,)))

    # 4. DEFLATE：NEW 明确报错
    data, _ = case_deflate()
    new_err = None
    try:
        new_payload_offset(data)
    except IOException as e:
        new_err = str(e)
    results.append(("deflate NEW 报 PAYLOAD_NOT_STORED", new_err == "PAYLOAD_NOT_STORED", "err=%s" % new_err))

    # 5. 无 payload.bin：两组都报 NOT_A_PAYLOAD_ZIP
    data, _ = case_no_payload()
    errs = []
    for fn in (old_payload_offset, new_payload_offset):
        try:
            fn(data)
            errs.append(None)
        except IOException as e:
            errs.append(str(e))
    results.append(("no payload 两组一致报错", errs == ["NOT_A_PAYLOAD_ZIP"] * 2, "errs=%s" % errs))

    failed = 0
    for name, ok, detail in results:
        print("[%s] %s (%s)" % ("PASS" if ok else "FAIL", name, detail))
        if not ok:
            failed += 1
    print("\n%d/%d passed" % (len(results) - failed, len(results)))
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())

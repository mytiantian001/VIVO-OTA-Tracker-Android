"""
验证新增能力的核心算法（与 Kotlin 实现同构）：

1. CERT.RSA：从 PKCS#7 中定位 X.509 证书（Kotlin 端再交给 CertificateFactory）
2. 嵌套 zip：内层 zip 的条目枚举与数据偏移是否与外层算法一致
3. 无扩展名文本文件（metadata / updater-script）的预览入口判定

用法：python verify_nested_and_cert.py
"""

import io
import struct
import zipfile

import verify_zip_browser_logic as base


def read_tlv(buf, pos):
    """读一个 DER TLV，返回 (tag, 内容起始, 长度, 下一个位置)。"""
    if pos + 2 > len(buf):
        return None
    tag = buf[pos]
    first = buf[pos + 1]
    p = pos + 2
    if first & 0x80:
        n = first & 0x7F
        if n > 4 or p + n > len(buf):
            return None
        length = int.from_bytes(buf[p:p + n], "big")
        p += n
    else:
        length = first
    if p + length > len(buf):
        return None
    return tag, p, length, p + length


def find_certificates(buf):
    """
    与 Kotlin 端 parseCertificates 同构：
    只匹配 30 82 形式的 SEQUENCE，再校验内部为
    SEQUENCE(tbs) SEQUENCE(algid) BIT STRING(sig) 三段。
    """
    certs = []
    i = 0
    n = len(buf)
    while i + 4 <= n:
        if not (buf[i] == 0x30 and buf[i + 1] == 0x82):
            i += 1
            continue
        outer = read_tlv(buf, i)
        if not outer:
            i += 1
            continue
        tag, cstart, clen, nxt = outer
        if tag != 0x30 or clen < 200:
            i += 1
            continue
        tbs = read_tlv(buf, cstart)
        if not tbs or tbs[0] != 0x30:
            i += 1
            continue
        algid = read_tlv(buf, tbs[3])
        if not algid or algid[0] != 0x30:
            i += 1
            continue
        sig = read_tlv(buf, algid[3])
        if not sig or sig[0] != 0x03:
            i += 1
            continue
        certs.append((i, clen))
        i = nxt
    return certs


def test_certificate_location():
    """确认 openssl 样本可解，且非证书数据零误报。"""
    data = open("CERT_sample.p7b", "rb").read()
    certs = find_certificates(data)
    assert len(certs) >= 1, "未能定位证书"
    off, clen = certs[0]
    der = data[off:off + 4 + clen]
    open("probe_extracted.der", "wb").write(der)
    print(f"[证书] 定位 {len(certs)} 个，首个 偏移={off} 长度={clen}")

    for name, blob in [
        ("纯文本", b"hello\n" * 200),
        ("全零", bytes(1024)),
        ("随机", bytes(range(256)) * 8),
    ]:
        assert len(find_certificates(blob)) == 0, f"{name} 误报"
    print("[证书] 负向测试通过（纯文本/全零/随机 均无误报）")


def build_nested_zip():
    """外层 zip 内含一个内层 zip，内层再含一个文本文件。"""
    inner = io.BytesIO()
    with zipfile.ZipFile(inner, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("inner/notes.txt", "inner content line\n" * 50)
        z.writestr("inner/META-INF/com/android/metadata", "ota-type=BLOCK\npost-sdk-level=35\n")

    outer = io.BytesIO()
    with zipfile.ZipFile(outer, "w") as z:
        z.writestr("payload.bin", b"CrAU" + bytes(500), compress_type=zipfile.ZIP_STORED)
        # 内层 zip 以 DEFLATE 存进外层
        z.writestr("nested/bundle.zip", inner.getvalue(), compress_type=zipfile.ZIP_DEFLATED)
    return outer.getvalue()


def test_nested_zip():
    blob = build_nested_zip()

    # 外层条目
    with zipfile.ZipFile(io.BytesIO(blob)) as z:
        end = len(blob) - 22
        while struct.unpack_from("<I", blob, end)[0] != 0x06054B50:
            end -= 1
        cen_size = struct.unpack_from("<I", blob, end + 12)[0]
        cen_off = struct.unpack_from("<I", blob, end + 16)[0]
        outer_entries = base.parse_central_directory(blob[cen_off: cen_off + cen_size])
        assert len(outer_entries) == 2, f"外层条目数应为 2，实际 {len(outer_entries)}"
        print(f"[嵌套] 外层条目 {len(outer_entries)} 个: "
              f"{[e['name'] for e in outer_entries]}")

        # 取出内层 zip（模拟 Kotlin 端 readEntryBytes 后构造 MemoryByteSource）
        nested_entry = next(e for e in outer_entries if e["name"].endswith(".zip"))
        off = base.local_data_offset(blob, nested_entry["lho"])
        raw = blob[off: off + nested_entry["csize"]]
        assert nested_entry["method"] == 8, "内层 zip 应为 DEFLATE，以覆盖解压路径"
        import zlib
        inner_blob = zlib.decompress(raw, -15)  # raw deflate
        assert len(inner_blob) == nested_entry["usize"], "内层解压长度不符"

    # 用同一套算法枚举内层
    end = len(inner_blob) - 22
    while struct.unpack_from("<I", inner_blob, end)[0] != 0x06054B50:
        end -= 1
    cen_size = struct.unpack_from("<I", inner_blob, end + 12)[0]
    cen_off = struct.unpack_from("<I", inner_blob, end + 16)[0]
    inner_entries = base.parse_central_directory(inner_blob[cen_off: cen_off + cen_size])
    assert len(inner_entries) == 2, f"内层条目数应为 2，实际 {len(inner_entries)}"
    print(f"[嵌套] 内层条目 {len(inner_entries)} 个: "
          f"{[e['name'] for e in inner_entries]}")

    # 内层条目的数据偏移与内容校验
    with zipfile.ZipFile(io.BytesIO(inner_blob)) as z:
        for e in inner_entries:
            off = base.local_data_offset(inner_blob, e["lho"])
            raw = inner_blob[off: off + e["csize"]]
            data = raw if e["method"] == 0 else zlib.decompress(raw, -15)
            expect = z.read(e["name"])
            assert data == expect, f"内层条目 {e['name']} 内容不符"
            print(f"[嵌套] 内层条目 '{e['name']}' 校验通过（{len(data)} bytes）")


def test_text_filename_detection():
    """无扩展名的 metadata / updater-script 应被判为可预览文本。"""
    text_ext = [".txt", ".text", ".prop", ".properties", ".sh", ".rc", ".xml", ".json",
                ".cfg", ".conf", ".csv", ".log", ".md", ".ini", ".mf", ".sf", ".script"]
    text_names = ["metadata", "updater-script", "otacert", "LICENSE", "NOTICE"]

    def is_text(name):
        fn = name.rsplit("/", 1)[-1]
        return any(fn.lower().endswith(x) for x in text_ext) or \
               any(n.lower() == fn.lower() for n in text_names)

    for name in ["META-INF/com/android/metadata",
                 "META-INF/com/google/android/updater-script",
                 "payload_properties.txt",
                 "META-INF/MANIFEST.MF"]:
        assert is_text(name), f"{name} 应判为文本"
        print(f"[文件名] {name} -> 可预览")
    for name in ["care_map.pb", "boot.img", "payload.bin"]:
        assert not is_text(name), f"{name} 不应判为文本"
        print(f"[文件名] {name} -> 不预览")


def main():
    test_certificate_location()
    test_nested_zip()
    test_text_filename_detection()
    print("\n全部通过：证书定位、嵌套 zip 枚举与数据校验、无扩展名文本判定")


if __name__ == "__main__":
    main()

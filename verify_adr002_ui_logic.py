# -*- coding: utf-8 -*-
"""
验证 ADR-002 的界面逻辑（与 Kotlin 实现同构）：

1. computeRootItems：顶层按路径首段合成文件夹，散装文件在后，各自按名称排序
2. computeViewEntries：文件夹内平铺、搜索覆盖整个当前 zip 层
3. relativeName：去掉已进入的文件夹前缀
4. buildTitle：路径过长时中间段省略
"""

import collections


class Entry:
    def __init__(self, name, usize=0, csize=0, method=0):
        self.name = name
        self.usize = usize
        self.csize = csize
        self.method = method

    @property
    def is_directory(self):
        return self.name.endswith("/")


def compute_root_items(entries):
    """顶层：合成文件夹在前、散装文件在后，各自按名称排序。"""
    folders = {}
    loose = []
    for e in entries:
        if e.is_directory:
            continue
        idx = e.name.find("/")
        if idx < 0:
            loose.append(e)
        else:
            top = e.name[:idx]
            cnt, size = folders.get(top, (0, 0))
            folders[top] = (cnt + 1, size + e.usize)
    items = []
    for name in sorted(folders.keys()):
        cnt, size = folders[name]
        items.append(("folder", name, cnt, size))
    for e in sorted(loose, key=lambda x: x.name):
        items.append(("entry", e.name, 0, 0))
    return items


def compute_view_entries(entries, folder, query):
    """
    搜索覆盖整个当前 zip 层（含子目录）；否则只取该文件夹下的后代。
    两种情况下都按名称排序。
    """
    if query:
        base = [e for e in entries if not e.is_directory]
    elif folder is not None:
        base = [e for e in entries
                if not e.is_directory and e.name.startswith(folder + "/")]
    else:
        base = []
    if query:
        base = [e for e in base if query.lower() in e.name.lower()]
    return sorted(base, key=lambda x: x.name)


def relative_name(name, folder):
    prefix = folder + "/" if folder else ""
    return name[len(prefix):] if prefix and name.startswith(prefix) else name


def build_title(breadcrumb, current_folder):
    parts = list(breadcrumb)
    if current_folder:
        parts.append(current_folder)
    if not parts:
        return "…"
    if len(parts) <= 3:
        return " / ".join(parts)
    return "%s / … / %s" % (parts[0], parts[-1])


def main():
    entries = [
        Entry("payload.bin", 11_000_000_000, 11_000_000_000),
        Entry("payload_properties.txt", 300, 300),
        Entry("META-INF/com/android/metadata", 1024, 400),
        Entry("META-INF/com/android/otacert", 900, 500),
        Entry("META-INF/CERT.RSA", 1200, 800),
        Entry("META-INF/MANIFEST.MF", 2000, 600),
        Entry("nested/bundle.zip", 4096, 2000, 8),
        Entry("META-INF/", 0),
    ]

    # 1. 顶层分组
    items = compute_root_items(entries)
    print("[顶层] 共 %d 项:" % len(items))
    for kind, name, cnt, size in items:
        if kind == "folder":
            print("   [文件夹] %-12s %d 项, %d bytes" % (name, cnt, size))
        else:
            print("   [文件]   %s" % name)

    folders = [i for i in items if i[0] == "folder"]
    files = [i for i in items if i[0] == "entry"]
    assert [f[1] for f in folders] == ["META-INF", "nested"], "文件夹应按名称排序"
    assert [f[1] for f in files] == ["payload.bin",
                                     "payload_properties.txt"], "散装文件应排序"
    assert items.index(folders[-1]) < items.index(files[0]), "文件夹必须排在文件之前"
    meta = next(f for f in folders if f[1] == "META-INF")
    assert meta[2] == 4, "META-INF 应有 4 个条目，实际 %d" % meta[2]

    # 2. 进入 META-INF：内部平铺，去掉前缀
    view = compute_view_entries(entries, "META-INF", "")
    print("\n[进入 META-INF] %d 项:" % len(view))
    for e in view:
        print("   %s -> %s" % (e.name, relative_name(e.name, "META-INF")))
    assert len(view) == 4, "META-INF 下应有 4 项"
    assert [e.name for e in view] == [
        "META-INF/CERT.RSA",
        "META-INF/MANIFEST.MF",
        "META-INF/com/android/metadata",
        "META-INF/com/android/otacert",
    ], "文件夹内应按名称排序"
    assert relative_name("META-INF/com/android/metadata", "META-INF") == \
        "com/android/metadata", "相对路径应去掉已进前缀"

    # 3. 搜索覆盖整个当前 zip 层（含子目录内条目）
    hits = compute_view_entries(entries, None, "metadata")
    print("\n[搜索 metadata] %d 项:" % len(hits))
    for e in hits:
        print("   %s" % e.name)
    assert len(hits) == 1 and hits[0].name == "META-INF/com/android/metadata", \
        "搜索必须命中子目录内的条目，否则用户会以为文件不存在"
    assert compute_view_entries(entries, None, "RSA")[0].name == "META-INF/CERT.RSA"

    # 4. 标题省略
    print("\n[标题]")
    print("   1 段:", build_title(["ota.zip"], None))
    print("   2 段:", build_title(["ota.zip"], "META-INF"))
    print("   4 段:", build_title(["ota.zip", "a.zip", "b.zip"], "META-INF"))
    assert build_title(["ota.zip"], None) == "ota.zip"
    assert build_title(["ota.zip"], "META-INF") == "ota.zip / META-INF"
    assert build_title(["ota.zip", "a.zip", "b.zip"], "META-INF") == \
        "ota.zip / … / META-INF", "过长时应省略中间段，保留首尾"

    print("\n全部通过：顶层分组与排序、文件夹内平铺、跨子目录搜索、标题省略")


if __name__ == "__main__":
    main()

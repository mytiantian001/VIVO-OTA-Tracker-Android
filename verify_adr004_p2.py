# -*- coding: utf-8 -*-
"""
验证 ADR-004 P2 的重试分类与块缓存（与 Kotlin 实现同构）。

验证目标：
1. HTTP 失败分类：429/5xx 可重试，200/416/其它 4xx 不可重试
2. 重试策略：最多 3 次、退避 450/900ms、优先 Retry-After、耗尽后收敛为 NETWORK_ERROR
   —— 回归重点：429 不得被报成「服务器不支持分段读取」
3. 块缓存：同区间重复读只打一次底层；跨块读正确拼接；大块直通；LRU 只保留 4 块

用法：python verify_adr004_p2.py
"""

MAX_ATTEMPTS = 3
BASE_RETRY_DELAY_MS = 450
MAX_RETRY_DELAY_MS = 4000


# ---------------- 重试/分类（复刻 VivoPayloadHttpUtil） ----------------

class RangeMarkerException(Exception):
    def __init__(self, message):
        super().__init__(message)


class RetryableHttpException(Exception):
    def __init__(self, message, retry_after_ms=None):
        super().__init__(message)
        self.retry_after_ms = retry_after_ms


def parse_retry_after_ms(header):
    if header is None:
        return None
    try:
        seconds = int(header.strip())
    except ValueError:
        return None
    return max(0, min(seconds * 1000, MAX_RETRY_DELAY_MS))


def classify_failure(code, retry_after=None):
    if code == 416:
        return RangeMarkerException("RANGE_INVALID_OFFSET")
    if code == 429:
        return RetryableHttpException("HTTP_429", parse_retry_after_ms(retry_after))
    if 500 <= code <= 599:
        return RetryableHttpException("HTTP_%d" % code)
    return RangeMarkerException("RANGE_NOT_SUPPORTED")


def backoff_ms(attempt):
    factor = 1 << max(0, min(attempt - 1, 4))
    return min(BASE_RETRY_DELAY_MS * factor, MAX_RETRY_DELAY_MS)


def run_with_retry(tag, block, log):
    """block(): 抛异常或返回值；返回 (结果, 尝试次数, 退避序列)。"""
    delays = []
    last = None
    for attempt in range(1, MAX_ATTEMPTS + 1):
        try:
            return block(), attempt, delays
        except RangeMarkerException:
            raise
        except RetryableHttpException as e:
            last = e
            if attempt >= MAX_ATTEMPTS:
                break
            wait = e.retry_after_ms if e.retry_after_ms is not None else backoff_ms(attempt)
            delays.append(wait)
            log.append("%s: 第 %d 次失败(%s)，%dms 后重试" % (tag, attempt, e, wait))
        except IOError as e:
            last = e
            if attempt >= MAX_ATTEMPTS:
                break
            wait = backoff_ms(attempt)
            delays.append(wait)
            log.append("%s: 第 %d 次网络失败(%s)，%dms 后重试" % (tag, attempt, e, wait))
    raise IOError("NETWORK_ERROR")


# ---------------- 块缓存（复刻 CachingByteSource） ----------------

class FakeBase:
    """统计底层被请求的区间，用于验证缓存命中。"""

    def __init__(self, data):
        self.data = data
        self.requests = []

    @property
    def size(self):
        return len(self.data)

    def read_at(self, offset, length):
        self.requests.append((offset, length))
        if offset < 0 or offset >= len(self.data):
            return 0
        return min(length, len(self.data) - offset)


class CachingSource:
    def __init__(self, base, block_size=1 << 20, max_blocks=4, bypass=4 << 20):
        self.base = base
        self.block_size = block_size
        self.max_blocks = max_blocks
        self.bypass = bypass
        self.blocks = {}  # dict 保持插入顺序，模拟 LinkedHashMap

    @property
    def size(self):
        return self.base.size

    def read_at(self, offset, length):
        total = self.size
        if length <= 0 or offset < 0 or offset >= total:
            return 0
        requested = min(length, total - offset)
        if requested > self.bypass:
            return self.base.read_at(offset, requested)

        copied = 0
        position = offset
        while copied < requested:
            index = position // self.block_size
            block = self._block_at(index)
            block_start = index * self.block_size
            src = position - block_start
            if src >= len(block):
                break
            n = min(len(block) - src, requested - copied)
            copied += n
            position += n
        return copied

    def _block_at(self, index):
        if index in self.blocks:
            block = self.blocks.pop(index)
            self.blocks[index] = block  # 命中续期
            return block
        start = index * self.block_size
        length = min(self.block_size, self.size - start)
        read = self.base.read_at(start, length)
        block = b"\x00" * read  # 只关心长度，不存真实字节
        self.blocks[index] = block
        while len(self.blocks) > self.max_blocks:
            oldest = next(iter(self.blocks))
            del self.blocks[oldest]
        return block


def main():
    results = []

    # 1. 分类矩阵
    results.append((
        "429 可重试（不再误报 Range 不支持）",
        isinstance(classify_failure(429, "2"), RetryableHttpException)
        and not isinstance(classify_failure(429, "2"), RangeMarkerException),
        "",
    ))
    results.append(("Retry-After=2s 被采纳", classify_failure(429, "2").retry_after_ms == 2000, ""))
    results.append(("503 可重试", isinstance(classify_failure(503), RetryableHttpException), ""))
    results.append(("200（Range 被忽略）不可重试且报 RANGE_NOT_SUPPORTED",
                    isinstance(classify_failure(200), RangeMarkerException)
                    and str(classify_failure(200)) == "RANGE_NOT_SUPPORTED", ""))
    results.append(("416 报 RANGE_INVALID_OFFSET",
                    str(classify_failure(416)) == "RANGE_INVALID_OFFSET", ""))
    results.append(("403 不可重试", isinstance(classify_failure(403), RangeMarkerException), ""))
    results.append(("退避序列 450/900/1800", [backoff_ms(1), backoff_ms(2), backoff_ms(3)] == [450, 900, 1800], ""))
    results.append(("退避封顶 4000ms", backoff_ms(6) == 4000, ""))

    # 2. 429 两次后成功：应重试并采纳 Retry-After
    log = []
    calls = {"n": 0}

    def flaky():
        calls["n"] += 1
        if calls["n"] <= 2:
            raise classify_failure(429, "1")
        return "OK"

    value, attempts, delays = run_with_retry("t", flaky, log)
    results.append(("429 重试后成功", value == "OK" and attempts == 3, "attempts=%d" % attempts))
    results.append(("采纳 Retry-After(1000ms×2)", delays == [1000, 1000], "delays=%s" % delays))

    # 3. 连续 5xx：耗尽后收敛为 NETWORK_ERROR
    log = []

    def always_500():
        raise classify_failure(500)

    try:
        run_with_retry("t", always_500, log)
        err = None
    except IOError as e:
        err = str(e)
    results.append(("5xx 耗尽 → NETWORK_ERROR", err == "NETWORK_ERROR", "err=%s" % err))

    # 4. 200 立即失败，不重试
    log = []
    calls = {"n": 0}

    def returns_200():
        calls["n"] += 1
        raise classify_failure(200)

    try:
        run_with_retry("t", returns_200, log)
        err = None
    except RangeMarkerException as e:
        err = str(e)
    results.append(("200 不重试（只请求一次）", err == "RANGE_NOT_SUPPORTED" and calls["n"] == 1,
                    "err=%s calls=%d" % (err, calls["n"])))

    # 5. 块缓存：同区间重复读只打一次底层
    base = FakeBase(b"\x00" * (5 << 20))
    cache = CachingSource(base)
    cache.read_at(4096, 64)
    cache.read_at(4096, 64)
    cache.read_at(8192, 64)  # 同一块内
    results.append(("同块重复读只请求一次", len(base.requests) == 1, "requests=%s" % base.requests))

    # 6. 跨块读：请求数 = 跨越的块数
    base = FakeBase(b"\x00" * (5 << 20))
    cache = CachingSource(base)
    cache.read_at((1 << 20) - 10, 32)  # 跨第 0、1 块
    results.append(("跨块读请求两块", len(base.requests) == 2, "requests=%s" % base.requests))

    # 7. 大块直通且不进缓存
    base = FakeBase(b"\x00" * (20 << 20))
    cache = CachingSource(base)
    cache.read_at(0, 5 << 20)
    results.append((">4MB 直通不缓存", len(base.requests) == 1 and len(cache.blocks) == 0,
                    "requests=%d blocks=%d" % (len(base.requests), len(cache.blocks))))

    # 8. LRU 只保留 4 块
    base = FakeBase(b"\x00" * (20 << 20))
    cache = CachingSource(base)
    for i in range(6):
        cache.read_at(i << 20, 16)
    results.append(("LRU 上限 4 块", len(cache.blocks) == 4, "blocks=%d" % len(cache.blocks)))

    # 9. 命中后续期：访问块0、1..4 后块0 仍是最近使用
    base = FakeBase(b"\x00" * (20 << 20))
    cache = CachingSource(base)
    for i in range(4):
        cache.read_at(i << 20, 16)
    cache.read_at(0, 16)          # 续期块 0
    cache.read_at(4 << 20, 16)    # 淘汰块 1
    results.append(("LRU 淘汰最久未用(块1)", 1 not in cache.blocks and 0 in cache.blocks,
                    "blocks=%s" % sorted(cache.blocks)))

    failed = 0
    for name, ok, detail in results:
        suffix = " (%s)" % detail if detail else ""
        print("[%s] %s%s" % ("PASS" if ok else "FAIL", name, suffix))
        if not ok:
            failed += 1
    print("\n%d/%d passed" % (len(results) - failed, len(results)))
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())

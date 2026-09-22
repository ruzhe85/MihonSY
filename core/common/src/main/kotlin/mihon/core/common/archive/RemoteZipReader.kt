package mihon.core.common.archive

import eu.kanade.tachiyomi.util.storage.CbzCrypto
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.DataFormatException
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

// SY --> Komiho Phase3：远程 ZIP 纯 Kotlin 随机读取器（方案 A + 加密支持方案 C）。
//
// 背景：libarchive 的 seekable zip 读取器虽然可用，但 [ArchiveReader.getInputStream]
// 是「新建流从头逐条目迭代」的 Mihon 设计（本地 mmap 零成本无所谓），叠加回调链路上
// 「30 字节 local header 读取被放大成 256KB Range 请求」后，每页打开流量 ≥ 整个文件。
//
// 本类绕开 libarchive：打开时只拉 **尾部 64KB**（解析 EOCD / ZIP64）+ **中央目录**
// （一次 Range），建立 名字→(localOffset, compSize, method) 索引；之后每页
// 直接 Range 拉 local header + 该页压缩数据，按需解密/解压。
// **每页流量 = 条目数据本身 + 首次打开的 ~几十 KB**，跳页 O(1)。
//
// 加密支持（方案 C）：
// - ZipCrypto（传统 PKWARE，7-Zip 等工具默认）：密钥派生 + 12B 加密头校验字节 + 异或流。
// - WinZip AES（AE-1/AE-2，WinRAR/WinZip/Bandizip 等）：PBKDF2-HmacSHA1(1000 轮) +
//   AES-CTR（计数器为前 8 字节小端、从 1 起——与 libarchive 一致，Java 标准 CTR 是大端不可用）+
//   HMAC-SHA1 前 10 字节认证（覆盖密文）。
// - 密码复用 CbzCrypto 全局密码（与本地加密 CBZ 同源）。无密码时 encrypted=true +
//   wrongPassword=null → 阅读器弹密码框；有密码时构造期用「加密头校验字节 / passVer」
//   低成本校验（各读 12 / salt+2 字节），错密码 → wrongPassword=true → 弹框重输。
//   （ZipCrypto 校验字节有 1/256 误判率 → 校验通过后若 CD CRC 可信且条目 ≤32MB 再整条
//   下载解密解压比对 CRC32（1/2^32），错密码在打开期 100% 拦截弹框，不会漏到读页期。）
//
// - 线程安全：索引建好后只读不可变；每页独立的 InputStream；source 自身线程安全。
// - 不支持的情况（非 ZIP、bzip2/zstd 等压缩方法、zip64 越界）在**构造期**抛
//   [RemoteZipUnsupportedException]，调用方回落 libarchive 路径，行为不回退。
// - 实现了与 [ArchiveReader] 相同的 [ArchiveHandle] 窄接口，ArchivePageLoader 零改动复用。
class RemoteZipReader(private val source: RandomAccessSource) : ArchiveHandle {

    /** 构造期即判定「该文件不适合本路径」，调用方应回落 libarchive。 */
    class RemoteZipUnsupportedException(message: String) : IOException(message)

    private class CdEntry(
        val name: String,
        val isFile: Boolean,
        val encrypted: Boolean,
        // 实际压缩方法（AES 条目取 0x9901 extra 内的方法，其余取 CD method）
        val method: Int,
        val compSize: Long,
        val uncompSize: Long,
        // CD 的 CRC32（未压缩数据的 CRC；bit3 置位时不可信）
        val crc: Long,
        // bit3（data descriptor）置位：ZipCrypto 校验字节取 DOS time 高字节，且 CRC 不可信
        val useTimeCheckByte: Boolean,
        val localOffset: Long,
        // ZipCrypto 校验字节：bit3(data descriptor) 置位用 DOS time 高字节，否则用 CRC 高字节
        val checkByte: Int,
        // WinZip AES 强度：0=非 AES；1/2/3 = AES-128/192/256
        val aesStrength: Int,
    )

    override val archiveHashCode: Int = source.hashCode()

    private var hasEncrypted = false

    // 构造期校验：无密码（文件加密）→ null（弹框）；有密码且校验通过 → false；错密码 → true
    override var wrongPassword: Boolean? = null
        private set

    override val encrypted: Boolean get() = hasEncrypted

    private val entries: Map<String, CdEntry>
    private val passwordBytes: ByteArray?

    init {
        val parsed = parseCentralDirectory()
        entries = parsed.first
        hasEncrypted = parsed.second
        passwordBytes = if (hasEncrypted && CbzCrypto.isPasswordSet()) {
            CbzCrypto.getDecryptedPasswordCbz()
        } else {
            null
        }
        if (hasEncrypted) {
            wrongPassword = if (passwordBytes == null) {
                logcat(LogPriority.INFO) { "[RemoteZip] 加密包无已存密码 → 弹框 entry=${parsed.third?.name}" }
                null // 无密码 → 阅读器弹密码输入框
            } else {
                // hasEncrypted=true 必有加密条目（parseCentralDirectory 保证），null 仅是穷尽性防御
                val probe = parsed.third ?: throw RemoteZipUnsupportedException("内部错误：加密标记为真但无加密条目")
                val ok = validatePassword(probe)
                logcat(LogPriority.INFO) {
                    "[RemoteZip] 密码校验=${if (ok) "通过" else "失败"} entry=${probe.name} " +
                        "method=${probe.method} bit3=${probe.useTimeCheckByte} " +
                        "compSize=${probe.compSize} uncompSize=${probe.uncompSize}"
                }
                !ok
            }
        }
    }

    // ---------------------------------------------------------------- 构造期解析

    /** @return 条目索引 / 是否含加密条目 / 第一个加密条目（供密码校验） */
    private fun parseCentralDirectory(): Triple<Map<String, CdEntry>, Boolean, CdEntry?> {
        val fileSize = source.size

        // 1. 拉尾部 64KB，找 EOCD（PK\005\006，从后往前扫以容忍注释）
        val tailLen = minOf(TAIL_SIZE.toLong(), fileSize).toInt()
        val tail = readFully(fileSize - tailLen, tailLen)
        val eocdIdx = findSignature(tail, EOCD_SIG, fromEnd = true)
            ?: throw RemoteZipUnsupportedException("未找到 EOCD（不是 ZIP？）")

        var totalEntries = tail.u16(eocdIdx + 10).toLong()
        var cdSize = tail.u32(eocdIdx + 12).toLong() and 0xFFFFFFFFL
        var cdOffset = tail.u32(eocdIdx + 16).toLong() and 0xFFFFFFFFL

        // 2. ZIP64：任一字段饱和 → 经 EOCD64 locator（PK\006\007，紧贴 EOCD 前 20 字节）定位 EOCD64（PK\006\006）
        if (cdOffset == 0xFFFFFFFFL || cdSize == 0xFFFFFFFFL || totalEntries == 0xFFFFL) {
            val tailStart = fileSize - tailLen
            val locAbs = tailStart + eocdIdx - 20
            if (locAbs < 0) throw RemoteZipUnsupportedException("ZIP64 字段饱和但 EOCD64 locator 越界")
            val locator = if (eocdIdx >= 20) tail.copyOfRange(eocdIdx - 20, eocdIdx) else readFully(locAbs, 20)
            if (locator.u32(0).toLong() != ZIP64_LOC_SIG.toLong()) {
                throw RemoteZipUnsupportedException("ZIP64 字段饱和但找不到 EOCD64 locator")
            }
            val eocd64Offset = locator.u64(8)
            val eocd64 = readFully(eocd64Offset, 56)
            if (eocd64.u32(0).toLong() != ZIP64_EOCD_SIG.toLong()) {
                throw RemoteZipUnsupportedException("EOCD64 签名不符")
            }
            totalEntries = eocd64.u64(32)
            cdSize = eocd64.u64(40)
            cdOffset = eocd64.u64(48)
        }

        // 3. 拉中央目录并解析
        if (cdSize > Int.MAX_VALUE) throw RemoteZipUnsupportedException("中央目录超大（超过 2GB）")
        val cd = readFully(cdOffset, cdSize.toInt())

        val map = HashMap<String, CdEntry>(totalEntries.coerceAtMost(1_000_000).toInt())
        var p = 0
        var count = 0
        var anyEncrypted = false
        var firstEncrypted: CdEntry? = null
        while (p + 46 <= cd.size && count < totalEntries) {
            if (cd.u32(p).toLong() != CDH_SIG.toLong()) break // 防御：目录损坏即停
            val flags = cd.u16(p + 8)
            val useTimeCheckByte = flags and 0x0008 != 0 // bit3：CRC 存于 data descriptor，CD crc 不可信
            var method = cd.u16(p + 10)
            val crc = cd.u32(p + 14).toLong() and 0xFFFFFFFFL
            var compSize = cd.u32(p + 20).toLong() and 0xFFFFFFFFL
            var uncompSize = cd.u32(p + 24).toLong() and 0xFFFFFFFFL
            val nameLen = cd.u16(p + 28)
            val extraLen = cd.u16(p + 30)
            val commentLen = cd.u16(p + 32)
            var localOffset = cd.u32(p + 42).toLong() and 0xFFFFFFFFL
            // ZipCrypto 校验字节数据：DOS time 高字节（CD offset 13 = time 低字节序的第二个字节）
            val dosTimeHigh = cd[p + 13].toInt() and 0xFF
            val name = cd.decodeToString(p + 46, p + 46 + nameLen)

            // ZIP64 扩展字段（0x0001）：按固定顺序 uncompSize(8)/compSize(8)/offset(8)/disk(4)，
            // 仅在对应基础字段饱和时出现。本类不需要 uncompSize，但必须按饱和情况逐字段
            // 消费，才能正确定位 compSize/offset 的位置。
            if (uncompSize == 0xFFFFFFFFL || compSize == 0xFFFFFFFFL || localOffset == 0xFFFFFFFFL) {
                var q = p + 46 + nameLen
                val extraEnd = q + extraLen
                while (q + 4 <= extraEnd) {
                    val id = cd.u16(q)
                    val sz = cd.u16(q + 2)
                    if (id == 0x0001) {
                        var r = q + 4
                        val dataEnd = q + 4 + sz
                        if (uncompSize == 0xFFFFFFFFL && r + 8 <= dataEnd) {
                            uncompSize = cd.u64(r); r += 8
                        }
                        if (compSize == 0xFFFFFFFFL && r + 8 <= dataEnd) {
                            compSize = cd.u64(r); r += 8
                        }
                        if (localOffset == 0xFFFFFFFFL && r + 8 <= dataEnd) {
                            localOffset = cd.u64(r); r += 8
                        }
                        // disk(4) 不需要（单盘读取）
                        break
                    }
                    q += 4 + sz
                }
            }

            var aesStrength = 0
            var isEncrypted = flags and 0x0001 != 0
            if (method == 99) {
                // WinZip AES：实际压缩方法在 0x9901 extra 内（version(2) vendor(2) strength(1) method(2)）
                var q = p + 46 + nameLen
                val extraEnd = q + extraLen
                while (q + 4 <= extraEnd) {
                    val id = cd.u16(q)
                    val sz = cd.u16(q + 2)
                    if (id == 0x9901 && sz >= 7) {
                        aesStrength = cd[q + 7].toInt() and 0xFF
                        method = cd.u16(q + 8)
                        break
                    }
                    q += 4 + sz
                }
                if (aesStrength == 0) throw RemoteZipUnsupportedException("method=99 但缺 0x9901 AES extra（$name）")
                if (aesStrength !in 1..3) throw RemoteZipUnsupportedException("未知 AES 强度 $aesStrength（$name）")
                isEncrypted = true
            }
            if (method != METHOD_STORED && method != METHOD_DEFLATED) {
                throw RemoteZipUnsupportedException("不支持的压缩方法 method=$method（$name）")
            }
            if (compSize > Int.MAX_VALUE) throw RemoteZipUnsupportedException("单条目超 2GB（$name）")

            if (!name.endsWith("/") && name.isNotEmpty()) {
                val checkByte = if (flags and 0x0008 != 0) dosTimeHigh else ((crc ushr 24) and 0xFF).toInt()
                val entry = CdEntry(
                    name = name,
                    isFile = true,
                    encrypted = isEncrypted,
                    method = method,
                    compSize = compSize,
                    uncompSize = uncompSize,
                    crc = crc,
                    useTimeCheckByte = useTimeCheckByte,
                    localOffset = localOffset,
                    checkByte = checkByte,
                    aesStrength = aesStrength,
                )
                map[name] = entry
                if (isEncrypted) {
                    anyEncrypted = true
                    if (firstEncrypted == null) firstEncrypted = entry
                }
            }
            p += 46 + nameLen + extraLen + commentLen
            count++
        }

        if (map.isEmpty()) throw RemoteZipUnsupportedException("中央目录无有效条目")
        return Triple(map, anyEncrypted, firstEncrypted)
    }

    // ---------------------------------------------------------------- 密码校验

    /**
     * 构造期密码校验。
     * - AES：读 salt+2 比对 passVer —— PBKDF2 派生值比对，确定性判定，无随机性。
     * - ZipCrypto：先读 12B 加密头校验字节（1/256 误判率）；通过后若条目不大再整条
     *   下载解密验证：
     *   - CD CRC 可信（bit3 未置位）→ 解压后比对 CRC32（1/2^32）；
     *   - bit3 置位（CD CRC 不可信，data descriptor 存 CRC）→ 解压整条验 deflate 结构
     *     （错密码解出的随机数据几乎必然 DataFormatException，可靠性远超 1 字节校验）。
     *   错密码必须在构造期被拦截，否则流入读页期后只报页面错误、无法弹密码框
     *   （渲染层不感知密码异常）。仅 bit3+STORED（流式存储加密，极罕见）仍依赖 12B。
     */
    private fun validatePassword(entry: CdEntry): Boolean {
        val dataStart = locateData(entry)
        return if (entry.aesStrength != 0) {
            val keyLen = aesKeyLen(entry.aesStrength)
            val saltLen = aesSaltLen(entry.aesStrength)
            val head = readFully(dataStart, saltLen + 2)
            val derived = pbkdf2Sha1(passwordBytes!!, head.copyOfRange(0, saltLen), keyLen)
            (derived[2 * keyLen].toInt() and 0xFF) == (head[saltLen].toInt() and 0xFF) &&
                (derived[2 * keyLen + 1].toInt() and 0xFF) == (head[saltLen + 1].toInt() and 0xFF)
        } else {
            val head = readFully(dataStart, 12)
            val zc = ZipCryptoCipher(passwordBytes!!)
            val plain = ByteArray(12)
            for (i in 0..11) plain[i] = zc.dec(head[i])
            if ((plain[11].toInt() and 0xFF) != entry.checkByte) return false

            // 增强：条目不大 → 整条验证，消除 1/256 误判（错密码漏到读页期）。
            // bit3+STORED（CD CRC 不可信且无 deflate 结构可验）不进此分支，退回 12B 校验字节。
            val canValidate = entry.uncompSize in 1..UNCOMP_VALIDATE_LIMIT &&
                (!entry.useTimeCheckByte || entry.method == METHOD_DEFLATED)
            if (canValidate) {
                val full = readFully(dataStart, entry.compSize.toInt())
                for (i in 12 until full.size) full[i] = zc.dec(full[i])
                val needCrc = !entry.useTimeCheckByte // CD CRC 可信时才比对
                if (entry.method == METHOD_STORED) {
                    val crc32 = java.util.zip.CRC32()
                    crc32.update(full, 12, full.size - 12)
                    return crc32.value == entry.crc
                }
                // DEFLATED：一次解压。解压失败（DataFormatException/截断）= 解密数据非合法
                // deflate 流 = 密码错误；CD CRC 可信则再比对 CRC32（1/2^32）
                val crc32 = java.util.zip.CRC32()
                val inf = Inflater(true)
                try {
                    inf.setInput(full, 12, full.size - 12)
                    val buf = ByteArray(64 * 1024)
                    while (!inf.finished()) {
                        val n = try {
                            inf.inflate(buf)
                        } catch (e: DataFormatException) {
                            0 // 解密失败产物不是合法 deflate 流 → 密码错误
                        }
                        if (n == 0) return false
                        crc32.update(buf, 0, n)
                    }
                } finally {
                    inf.end()
                }
                return !needCrc || crc32.value == entry.crc
            }
            true
        }
    }

    // ---------------------------------------------------------------- ArchiveHandle

    override fun <T> useEntries(block: (Sequence<ArchiveEntry>) -> T): T =
        block(entries.values.asSequence().map { ArchiveEntry(it.name, it.isFile, isEncrypted = it.encrypted) })

    override fun getInputStream(entryName: String): InputStream? {
        val e = entries[entryName] ?: return null

        val dataStart = locateData(e)
        val raw = readFully(dataStart, e.compSize.toInt())

        val payload: ByteArray = when {
            e.aesStrength != 0 -> decryptAes(e, raw)
            e.encrypted -> decryptZipCrypto(e, raw)
            else -> raw
        }
        return when (e.method) {
            METHOD_STORED -> ByteArrayInputStream(payload)
            METHOD_DEFLATED -> InflaterInputStream(ByteArrayInputStream(payload), Inflater(true), 64 * 1024)
            else -> throw IOException("method=${e.method}") // 构造期已过滤，防御
        }
    }

    override fun close() {
        source.close()
    }

    // ---------------------------------------------------------------- 解密

    /** ZipCrypto（传统 PKWARE）：密钥派生 + 校验字节验证 + 异或流解密，返回压缩数据明文。 */
    private fun decryptZipCrypto(e: CdEntry, raw: ByteArray): ByteArray {
        if (raw.size < 12) throw IOException("条目数据过短（${e.name}）")
        val zc = ZipCryptoCipher(passwordBytes!!)
        for (i in raw.indices) raw[i] = zc.dec(raw[i])
        if ((raw[11].toInt() and 0xFF) != e.checkByte) {
            // 密码错（构造期校验漏网的 1/256）：抛密码异常而非普通 IO 错，语义正确，
            // 渲染层识别后直接弹密码框（submitArchivePassword 兜底当前章整章重载）
            logcat(LogPriority.WARN) {
                "[RemoteZip] 读页期 ZipCrypto 校验不符 name=${e.name} method=${e.method} " +
                    "bit3=${e.useTimeCheckByte} checkByte=${e.checkByte} " +
                    "plain=${raw[11].toInt() and 0xFF} compSize=${e.compSize}"
            }
            throw ArchivePasswordException(wrongPassword = true)
        }
        return raw.copyOfRange(12, raw.size)
    }

    /**
     * WinZip AES（AE-1/AE-2）：布局 salt | passVer(2) | 密文 | auth(10)。
     * PBKDF2-HmacSHA1(1000 轮) 派生 AES key + HMAC key + passVer；
     * HMAC-SHA1 覆盖**密文**，前 10 字节与 auth 比对；CTR 计数器为前 8 字节小端、从 1 起。
     */
    private fun decryptAes(e: CdEntry, raw: ByteArray): ByteArray {
        val keyLen = aesKeyLen(e.aesStrength)
        val saltLen = aesSaltLen(e.aesStrength)
        if (raw.size < saltLen + 2 + 10) throw IOException("AES 条目数据过短（${e.name}）")
        val salt = raw.copyOfRange(0, saltLen)
        val storedVer = raw[saltLen].toInt() to raw[saltLen + 1].toInt()
        val cipherLen = raw.size - saltLen - 2 - 10
        val ciphertext = raw.copyOfRange(saltLen + 2, saltLen + 2 + cipherLen)
        val auth = raw.copyOfRange(raw.size - 10, raw.size)

        val derived = pbkdf2Sha1(passwordBytes!!, salt, keyLen)
        if ((derived[2 * keyLen].toInt() and 0xFF) != (storedVer.first and 0xFF) ||
            (derived[2 * keyLen + 1].toInt() and 0xFF) != (storedVer.second and 0xFF)
        ) {
            logcat(LogPriority.WARN) {
                "[RemoteZip] 读页期 AES passVer 不符 name=${e.name} strength=${e.aesStrength} " +
                    "stored=(${storedVer.first and 0xFF},${storedVer.second and 0xFF}) " +
                    "derived=(${derived[2 * keyLen].toInt() and 0xFF},${derived[2 * keyLen + 1].toInt() and 0xFF})"
            }
            throw ArchivePasswordException(wrongPassword = true)
        }

        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(derived.copyOfRange(keyLen, 2 * keyLen), "HmacSHA1"))
        mac.update(ciphertext)
        if (!mac.doFinal().copyOfRange(0, 10).contentEquals(auth)) {
            throw IOException("AES 认证码不符（数据损坏）：${e.name}")
        }

        // AES-CTR：16B 计数器块 = 64 位小端计数（从 1 起）+ 8 个 0 字节；AES/ECB 加密计数器块得密钥流
        val aes = Cipher.getInstance("AES/ECB/NoPadding")
        aes.init(Cipher.ENCRYPT_MODE, SecretKeySpec(derived.copyOfRange(0, keyLen), "AES"))
        var counter = 1L
        var i = 0
        val block = ByteArray(16)
        while (i < ciphertext.size) {
            var c = counter
            for (j in 0..7) {
                block[j] = (c and 0xFF).toByte()
                c = c ushr 8
            }
            for (j in 8..15) block[j] = 0
            val keystream = aes.doFinal(block)
            val n = minOf(16, ciphertext.size - i)
            for (j in 0 until n) ciphertext[i + j] = (ciphertext[i + j].toInt() xor keystream[j].toInt()).toByte()
            i += n
            counter++
        }
        return ciphertext
    }

    // ---------------------------------------------------------------- 工具

    /** 解析 local header 得数据区起点（30 字节定长 + name + extra，长度以 local header 为准）。 */
    private fun locateData(e: CdEntry): Long {
        val lh = readFully(e.localOffset, 30)
        if (lh.u32(0).toLong() != LFH_SIG.toLong()) {
            throw IOException("local header 签名不符 @${e.localOffset}（${e.name}）")
        }
        val nameLen = lh.u16(26)
        val extraLen = lh.u16(28)
        return e.localOffset + 30 + nameLen + extraLen
    }

    /** PBKDF2-HmacSHA1（1000 轮），返回长度 2*keyLen+2：[0,keyLen)=AES key，[keyLen,2*keyLen)=HMAC key，末 2 字节=passVer。 */
    private fun pbkdf2Sha1(password: ByteArray, salt: ByteArray, keyLen: Int): ByteArray {
        // CbzCrypto 密码是原始字节；PBEKeySpec 按 UTF-8 处理 char[]，用 UTF-8 往返保证与 libarchive 的
        // strlen(passphrase) 原始字节一致
        val chars = String(password, Charsets.UTF_8).toCharArray()
        val spec = PBEKeySpec(chars, salt, 1000, (2 * keyLen + 2) * 8)
        chars.fill('\u0000')
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded
    }

    private fun aesKeyLen(strength: Int): Int = when (strength) {
        1 -> 16
        2 -> 24
        3 -> 32
        else -> throw RemoteZipUnsupportedException("未知 AES 强度 $strength")
    }

    private fun aesSaltLen(strength: Int): Int = 4 * (strength + 1) // 1→8, 2→12, 3→16

    /** 从 source 循环读满 [length] 字节（source 单次可能只返回一个块）。 */
    private fun readFully(offset: Long, length: Int): ByteArray {
        val out = ByteArray(length)
        var pos = 0
        while (pos < length) {
            val chunk = source.read(offset + pos, length - pos)
            if (chunk.isEmpty()) throw IOException("读取越界/EOF @${offset + pos}（请求 $length，已得 $pos）")
            System.arraycopy(chunk, 0, out, pos, chunk.size)
            pos += chunk.size
        }
        return out
    }

    /** 在 buf 中找 4 字节签名；fromEnd=true 时从后往前找第一个（EOCD 允许 zip 注释跟在后面）。 */
    private fun findSignature(buf: ByteArray, sig: Int, fromEnd: Boolean): Int? {
        if (fromEnd) {
            for (i in buf.size - 22 downTo 0) {
                if (buf.u32(i).toLong() == sig.toLong()) return i
            }
        } else {
            for (i in 0..buf.size - 4) {
                if (buf.u32(i).toLong() == sig.toLong()) return i
            }
        }
        return null
    }

    private fun ByteArray.u16(off: Int): Int =
        (this[off].toInt() and 0xFF) or ((this[off + 1].toInt() and 0xFF) shl 8)

    private fun ByteArray.u32(off: Int): Int =
        u16(off) or (u16(off + 2) shl 16)

    private fun ByteArray.u64(off: Int): Long =
        (u32(off).toLong() and 0xFFFFFFFFL) or ((u32(off + 4).toLong() and 0xFFFFFFFFL) shl 32)

    /**
     * 传统 PKWARE 加密流。密钥初始化：key0=0x12345678, key1=0x23456789, key2=0x34567890，
     * 逐密码字节 updateKeys；解密：p = c ^ decryptByte() 后用**明文** updateKeys（与 libarchive 一致）。
     */
    private class ZipCryptoCipher(password: ByteArray) {
        private var key0 = 0x12345678L
        private var key1 = 0x23456789L
        private var key2 = 0x34567890L

        init {
            for (b in password) update(b.toInt() and 0xFF)
        }

        private fun update(b: Int) {
            key0 = (CRC_TABLE[((key0 xor b.toLong()) and 0xFF).toInt()].toLong() xor (key0 ushr 8)) and 0xFFFFFFFFL
            // 与 libarchive trad_enc_update_keys 一致：(key1 + key0低字节) 整体乘 134775813 后 +1
            key1 = ((key1 + (key0 and 0xFF)) * 134775813L + 1) and 0xFFFFFFFFL
            // CRC 表索引用 key2 与 key1 高字节（此前两操作数写反，密钥派生全错、正确密码必失败）
            key2 = (CRC_TABLE[((key2 xor (key1 ushr 24)) and 0xFF).toInt()].toLong() xor (key2 ushr 8)) and 0xFFFFFFFFL
        }

        fun dec(cipherByte: Byte): Byte {
            // decrypt_byte()：temp=(key2|2)&0xFFFF，((temp*(temp^1))>>8)&0xFF —— C 为 32 位回绕乘法，
            // Kotlin 需用 Long 乘后取低 32 位，避免 Int 溢出
            val temp = ((key2 and 0xFFFF) or 2).toInt()
            val prod = (temp.toLong() * (temp xor 1).toLong()) and 0xFFFFFFFFL
            val p = cipherByte.toInt() xor (((prod ushr 8) and 0xFF).toInt())
            update(p and 0xFF)
            return p.toByte()
        }

        companion object {
            // CRC-32 表（poly 0xEDB88320）
            private val CRC_TABLE = IntArray(256).also { table ->
                for (i in 0..255) {
                    var c = i
                    repeat(8) {
                        c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1
                    }
                    table[i] = c
                }
            }
        }
    }

    private companion object {
        const val TAIL_SIZE = 64 * 1024
        // 构造期 ZipCrypto 整条验证的未压缩大小上限（超过则退回 12B 校验字节，1/256 漏网可接受）
        const val UNCOMP_VALIDATE_LIMIT = 32L * 1024 * 1024
        const val METHOD_STORED = 0
        const val METHOD_DEFLATED = 8

        const val EOCD_SIG = 0x06054b50        // PK\005\006
        const val ZIP64_LOC_SIG = 0x07064b50   // PK\006\007
        const val ZIP64_EOCD_SIG = 0x06064b50  // PK\006\006
        const val CDH_SIG = 0x02014b50         // PK\001\002（central directory header）
        const val LFH_SIG = 0x04034b50         // PK\003\004（local file header）
    }
}
// SY <--

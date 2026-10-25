package com.RobinNotBad.BiliClient.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.Locale

/**
 * 评论区翻译 gRPC 层的纯逻辑单测。
 *
 * 这条链路真正难验证的东西全在「字节形态」上：字段号写错一个、varint 编码成有符号、
 * base64 补位少了 `=`、locale 头字段多带/少带，服务端都**不会报错**，只会静默返回空译文
 * 或 12002——在 UI 上表现为「点了翻译没反应」，和网络抖动、评论区关闭一模一样，事后几乎无法定位。
 *
 * 所以这里把文档实测出来的请求帧（文档 §6.4）、三个 locale 头实测值（§4.2）、
 * 按 §3 字段表手搓的响应 protobuf 全部钉死。
 *
 * 全部用例只调用纯函数：不联网、不碰 Android 类，可在纯 JVM 单测里跑
 * （这也是 locale 头的 base64 必须自己实现的原因——android.util.Base64 在单测里是空壳）。
 */
class ReplyTranslateApiTest {

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /** 把无空格的 hex 串转成字节数组；写错一半长度直接崩，避免测试自己造出脏数据。 */
    private fun hex(text: String): ByteArray {
        val clean = text.replace(" ", "").replace("\n", "").lowercase()
        require(clean.length % 2 == 0) { "hex 串长度必须是偶数：$text" }
        return ByteArray(clean.length / 2) { i ->
            val high = Character.digit(clean[i * 2], 16)
            val low = Character.digit(clean[i * 2 + 1], 16)
            require(high >= 0 && low >= 0) { "hex 串里有非法字符：$text" }
            ((high shl 4) or low).toByte()
        }
    }

    /** 字节数组转小写 hex，失败信息里能直接和文档/抓包逐字节对照。 */
    private fun ByteArray.toHex(): String = joinToString("") { b ->
        val value = b.toInt() and 0xFF
        value.toString(16).padStart(2, '0')
    }

    // ------------------------------------------------------------------
    // 1. 请求编码：type=1, oid=80433022, rpids=[3760801399]
    // ------------------------------------------------------------------

    @Test
    fun encodeTranslateRequest_matchesVerifiedFrame() {
        // 这 14 字节是文档 §6.4 用 Node（translate_reply.mjs）与 Python 两套实现实测跑通的请求体：
        //   08 01                field1 type=1
        //   10 fe 9e ad 26       field2 oid=80433022
        //   1a 05 f7 8c a5 81 0e field3 rpids packed=[3760801399]
        // 任一字节不同，服务端就会解成别的 type/oid/rpid 或直接回 12002。
        val body = ReplyTranslateApi.encodeTranslateRequest(1, 80433022L, longArrayOf(3760801399L))

        assertEquals("请求体必须是 14 字节", 14, body.size)
        assertEquals(
            "请求体字节必须与文档 §6.4 实测帧完全一致",
            "080110fe9ead261a05f78ca5810e",
            body.toHex()
        )
    }

    @Test
    fun encodeTranslateRequest_packsMultipleRpids() {
        // repeated int64 必须用 proto3 默认的 packed 编码：tag=0x1A + 长度 + 连续 varint。
        // 逐个写 tag 服务端也能解析，但那不是文档实测的字节形态；
        // 这条用例同时验证「长度前缀是 rpid 总字节数」。
        val body = ReplyTranslateApi.encodeTranslateRequest(1, 1L, longArrayOf(1L, 2L))

        assertEquals("type=1 | oid=1 | packed 长度 2 | rpid 1、2", "080110011a020102", body.toHex())
    }

    @Test
    fun encodeTranslateRequest_writesNegativeInt64AsUnsigned() {
        // protobuf 的 int64 按无符号 64 位编码：负数用补码写满 10 字节。
        // 实际请求里 rpid/oid/type 都是正数，但这里钉死"无符号处理"，
        // 防止有人把 writeVarint 改成有符号 while 循环（那样负数会写出 1 字节 0x00，wire 数据直接错位）。
        val body = ReplyTranslateApi.encodeTranslateRequest(1, 1L, longArrayOf(-1L))
        val expected = "080110011a0a" + "ff".repeat(9) + "01"

        assertEquals("-1 应编码为 9 个 0xFF + 0x01（10 字节 varint）", expected, body.toHex())
    }

    // ------------------------------------------------------------------
    // 2. 参数校验：非法参数必须在本地就拒绝
    // ------------------------------------------------------------------

    @Test
    fun encodeTranslateRequest_rejectsBadType() {
        // type=0 是 SubjectType.NONE：发出去的服务端必定回 12002「当前页面评论功能已关闭」，
        // 与"评论区真的关了"无法区分，所以必须在编码前抛 IllegalArgumentException。
        assertThrows("type=0 必须被拒绝", IllegalArgumentException::class.java) {
            ReplyTranslateApi.encodeTranslateRequest(0, 1L, longArrayOf(1L))
        }
        assertThrows("负 type 必须被拒绝", IllegalArgumentException::class.java) {
            ReplyTranslateApi.encodeTranslateRequest(-5, 1L, longArrayOf(1L))
        }
    }

    @Test
    fun encodeTranslateRequest_rejectsEmptyRpids() {
        assertThrows("null rpids 必须被拒绝", IllegalArgumentException::class.java) {
            ReplyTranslateApi.encodeTranslateRequest(1, 1L, null)
        }
        assertThrows("空 rpids 必须被拒绝", IllegalArgumentException::class.java) {
            ReplyTranslateApi.encodeTranslateRequest(1, 1L, longArrayOf())
        }
    }

    @Test
    fun encodeTranslateRequest_rejectsTooManyRpids() {
        // 文档 §5.4 建议单次 ≤20：批量越大越容易触发风控，服务端也没验证过更大批量。
        // 超限必须本地拒绝，不能悄悄截断（截断会让调用方以为全翻了）。
        val tooMany = LongArray(ReplyTranslateApi.MAX_BATCH_RPIDS + 1) { it + 1L }
        val error = assertThrows(
            "超过上限必须抛 IllegalArgumentException",
            IllegalArgumentException::class.java
        ) { ReplyTranslateApi.encodeTranslateRequest(1, 1L, tooMany) }
        assertTrue(
            "提示里要带上限值，方便调用方改批量：${error.message}",
            error.message!!.contains(ReplyTranslateApi.MAX_BATCH_RPIDS.toString())
        )
    }

    @Test
    fun encodeTranslateRequest_acceptsExactlyMaxBatch() {
        // 上限本身必须合法，否则"最多 20 条"就成了"最多 19 条"
        val body = ReplyTranslateApi.encodeTranslateRequest(
            1, 1L, LongArray(ReplyTranslateApi.MAX_BATCH_RPIDS) { it + 1L })
        assertTrue("恰好 MAX_BATCH_RPIDS 条应当允许编码", body.isNotEmpty())
    }

    // ------------------------------------------------------------------
    // 3. gRPC 帧头
    // ------------------------------------------------------------------

    @Test
    fun wrapGrpcFrame_addsFiveBytePrefix() {
        val payload = hex("080110fe9ead261a05f78ca5810e")
        val frame = ReplyTranslateApi.wrapGrpcFrame(payload)

        assertEquals("gRPC 帧 = 5 字节前缀 + 14 字节请求体 = 19 字节", 19, frame.size)
        assertEquals(
            "完整帧（文档 §6.4 的 000000000e080110fe9ead261a05f78ca5810e）",
            "000000000e080110fe9ead261a05f78ca5810e",
            frame.toHex()
        )
        assertEquals("compressed-flag 必须是 0（不压缩）", 0, frame[0].toInt())
        assertEquals("长度前缀必须是大端 14（00 00 00 0E）", "0000000e", frame.copyOfRange(1, 5).toHex())
        assertEquals("payload 必须原样跟在 5 字节前缀之后", "080110fe9ead261a05f78ca5810e", frame.copyOfRange(5, 19).toHex())
    }

    @Test
    fun wrapGrpcFrame_emptyPayloadIsJustThePrefix() {
        // 虽然正常请求不会为空，但长度前缀的大端写法要能正确处理 0
        val frame = ReplyTranslateApi.wrapGrpcFrame(ByteArray(0))
        assertEquals("空消息也是 5 字节帧", 5, frame.size)
        assertEquals("0 长度前缀 + 空 body", "0000000000", frame.toHex())
    }

    // ------------------------------------------------------------------
    // 4. x-bili-locale-bin：三个文档实测值
    // ------------------------------------------------------------------

    @Test
    fun buildLocaleHeader_matchesMeasuredValues() {
        // 这三个 base64 是文档 §4.2 本机实跑值（Locale{c_locale{language:..}}）：
        //   en → 0a040a02656e → CgQKAmVu
        //   ja → 0a040a026a61 → CgQKAmph
        //   zh → 0a040a027a68 → CgQKAnpo
        // 服务端只认这个头（-bin 头按 gRPC 规范传 base64）；发错就静默无译文，不会报错。
        assertEquals("en 的实测值", "CgQKAmVu", ReplyTranslateApi.buildLocaleHeader(Locale.ENGLISH))
        assertEquals("ja 的实测值", "CgQKAmph", ReplyTranslateApi.buildLocaleHeader(Locale.JAPANESE))
        assertEquals("zh 的实测值", "CgQKAnpo", ReplyTranslateApi.buildLocaleHeader(Locale.SIMPLIFIED_CHINESE))
    }

    @Test
    fun buildLocaleHeader_onlyUsesLanguage() {
        // 只发最小头：region/script 都不带。文档 §4.3 实测三种写法都能翻，
        // 字段越少越不容易受服务端策略变化影响；en-US 的 language 是 en，所以结果与 en 相同。
        assertEquals(
            "en-US 只应取 language=en，与 CgQKAmVu 一致",
            "CgQKAmVu",
            ReplyTranslateApi.buildLocaleHeader(Locale.forLanguageTag("en-US"))
        )
        assertEquals(
            "zh-Hans-CN 只应取 language=zh（不把 script/region 写进头）",
            "CgQKAnpo",
            ReplyTranslateApi.buildLocaleHeader(Locale.forLanguageTag("zh-Hans-CN"))
        )
        assertEquals(
            "ROOT 的 language 是空串，必须回退 en —— 否则 base64 是空串，等于没发头",
            "CgQKAmVu",
            ReplyTranslateApi.buildLocaleHeader(Locale.ROOT)
        )
        assertEquals("null 也按 en 处理", "CgQKAmVu", ReplyTranslateApi.buildLocaleHeader(null))
    }

    @Test
    fun buildLocaleHeader_handlesNonTwoLetterLanguage() {
        // 主流程只会走到 2 字母语言（en/ja/zh），但长度字段必须按 protobuf 规则通用处理：
        // fil 的编码是 0a 05 | 0a 03 66 69 6c（Locale 长度 5、LocaleIds 长度 3）。
        // base64 末尾补两个 '='，顺带验证补位逻辑。
        assertEquals(
            "3 字母 language 的长度字段与 base64 补位都要正确",
            "CgUKA2ZpbA==",
            ReplyTranslateApi.buildLocaleHeader(Locale.forLanguageTag("fil"))
        )
    }

    // ------------------------------------------------------------------
    // 5. 响应解析：文档 §3 字段表
    // ------------------------------------------------------------------

    @Test
    fun parseTranslateResponse_singleTranslatedReply() {
        //   0a 0b                  顶层 field1（translated_replies map）长 11
        //     08 7b                entry field1 key=123（rpid）
        //     12 07                entry field2 value 长 7（ReplyInfo）
        //       8a 01 04           ReplyInfo field17（translated_content）长 4
        //         0a 02 68 69      Content field1（message）长 2 = "hi"
        val result = ReplyTranslateApi.parseTranslateResponse(hex("0a0b087b12078a01040a026869"))

        assertEquals("只应解析出一条译文", 1, result.size)
        assertEquals("key 是 rpid，value 是 translated_content.message", "hi", result[123L])
    }

    @Test
    fun parseTranslateResponse_keepsOnlyEntriesWithTranslation() {
        // 服务端会为请求里每个 rpid 返回 ReplyInfo，但只有翻译成功的才有 translated_content（field17）。
        // 不可译（UNSUPPORTED）的条目只有别的字段——这里第二个 entry 只有 id（field2），
        // 必须被跳过，否则 UI 会把空字符串当译文展示。
        //
        // 同时追加四种顶层未知字段：field9 varint(0x48 01)、field2 fixed32(0x15 ...)、
        // field3 fixed64(0x19 ...)、field5 length-delimited(0x2a ...)。
        // 服务端随时会给响应加字段，解析器必须按 wire type 跳过而不是崩。
        val payload = hex(
            "0a0b087b12078a01040a026869" +   // entry1：rpid=123，有译文 "hi"
                "0a0808c803120310c803" +     // entry2：rpid=456，ReplyInfo 里只有 id，没有 field17
                "4801" +                      // 顶层未知 field9 varint
                "1501020304" +                // 顶层未知 field2 fixed32（wire type 5）
                "190102030405060708" +        // 顶层未知 field3 fixed64（wire type 1）
                "2a03010203"                  // 顶层未知 field5 length-delimited（wire type 2）
        )
        val result = ReplyTranslateApi.parseTranslateResponse(payload)

        assertEquals("两条 entry 里只有 123 有译文", 1, result.size)
        assertEquals("hi", result[123L])
        assertNull("没译文的 rpid 不能出现在结果里，交给调用方保持原文", result[456L])
    }

    @Test
    fun parseTranslateResponse_preservesServerOrder() {
        // 返回 LinkedHashMap：UI 若按 map 顺序逐条回填译文，顺序必须与服务端 entry 顺序一致，
        // 便于把日志和抓包对照。HashMap 会打乱顺序，这条用例防止有人把实现换成 HashMap。
        val payload = hex(
            "0a0b087b12078a01040a026869" +   // entry1：rpid=123 → "hi"
                "0a0c08c80312078a01040a02796f"  // entry2：rpid=456 → "yo"
        )
        val result = ReplyTranslateApi.parseTranslateResponse(payload)

        assertEquals("两条都要解析出来", 2, result.size)
        assertEquals("顺序必须与服务端 entry 顺序一致", listOf(123L, 456L), result.keys.toList())
        assertEquals("hi", result[123L])
        assertEquals("yo", result[456L])
    }

    @Test
    fun parseTranslateResponse_skipsEmptyTranslation() {
        // translated_content 结构存在但 message 是空串（"有结构没文本"）：
        // 对 UI 来说等于没翻译，必须跳过而不是返回 ""。
        val result = ReplyTranslateApi.parseTranslateResponse(hex("0a09087b12058a01020a00"))

        assertTrue("空译文不应产生条目", result.isEmpty())
    }

    @Test
    fun parseTranslateResponse_emptyAndNullReturnEmptyMap() {
        // 空 body 是正常情况（例如不可译/评论不存在时服务端可能不回帧），不是错误。
        // null 也按空处理，调用方少判一次空。
        assertTrue("空数组返回空 map", ReplyTranslateApi.parseTranslateResponse(ByteArray(0)).isEmpty())
        assertTrue("null 返回空 map", ReplyTranslateApi.parseTranslateResponse(null).isEmpty())
    }

    // ------------------------------------------------------------------
    // 6. 响应解析：坏数据必须显式失败
    // ------------------------------------------------------------------

    @Test
    fun parseTranslateResponse_truncatedPayloadFails() {
        // 顶层声明 map entry 长 12 字节，实际只剩 3 字节。必须抛 IOException：
        // 静默返回半截数据会让 UI 拿到不完整译文却以为成功。
        val error = assertThrows(
            "声明长度超过剩余字节必须抛 IOException",
            IOException::class.java
        ) { ReplyTranslateApi.parseTranslateResponse(hex("0a0c087b12")) }
        assertTrue("异常消息要说明是数据截断：${error.message}", error.message!!.contains("截断"))
    }

    @Test
    fun parseTranslateResponse_truncatedVarintFails() {
        // 0x80 表示"后续还有字节"，但数据到此为止：这是截断，不能当 0 处理。
        val error = assertThrows(
            "varint 中途结束必须抛 IOException",
            IOException::class.java
        ) { ReplyTranslateApi.parseTranslateResponse(hex("80")) }
        assertTrue("异常消息要说明是数据截断：${error.message}", error.message!!.contains("截断"))
    }

    @Test
    fun parseTranslateResponse_unsupportedWireTypeFails() {
        // 0x0b = field1 + wire type 3（start group，proto3 已废弃）。
        // 不支持就必须报错：硬猜偏移继续读会让后续字节全部错位，可能解析出完全不存在的 rpid/译文。
        val error = assertThrows(
            "不支持的 wire type 必须抛 IOException",
            IOException::class.java
        ) { ReplyTranslateApi.parseTranslateResponse(hex("0b")) }
        assertTrue("异常消息要带上不支持的 wire type：${error.message}", error.message!!.contains("wire type"))
    }

    // ------------------------------------------------------------------
    // 7. 网络方法的参数校验（不联网）
    // ------------------------------------------------------------------

    @Test
    fun translateReplies_rejectsBadArgumentsBeforeSending() {
        // 网络方法也要在发请求前做同一套校验：这里只走校验分支、不会真的联网，
        // 所以能用纯 JVM 单测钉住。否则非法 type 会真发一次请求换来 12002，
        // 既浪费一次风控额度，也把"参数写错"伪装成"评论区关闭"。
        assertThrows("type=0 必须在发请求前拒绝", IllegalArgumentException::class.java) {
            ReplyTranslateApi.translateReplies(0, 1L, longArrayOf(1L), "CgQKAmVu")
        }
        assertThrows("超过批量上限必须在发请求前拒绝", IllegalArgumentException::class.java) {
            ReplyTranslateApi.translateReplies(
                1, 1L, LongArray(ReplyTranslateApi.MAX_BATCH_RPIDS + 1) { it + 1L }, "CgQKAmVu")
        }
    }
}

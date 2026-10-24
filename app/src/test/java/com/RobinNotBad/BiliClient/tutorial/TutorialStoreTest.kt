package com.RobinNotBad.BiliClient.tutorial

import android.app.Activity
import com.RobinNotBad.BiliClient.util.FakeSharedPreferences
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [TutorialStore] 的键规则与迁移逻辑守卫。
 *
 * 这些用例守的是审计 P1-1 那个故障：教程管理页按旧 tag 拼键、教程系统按 id 读键，
 * 两边对不上导致「通过」无效、「清除」也清不掉。这里把三件事钉死：
 * 1. 键永远是 `tutorial_ver_<id>`（[TutorialStore.keyOf]），没有第二套拼法；
 * 2. 旧键迁移只能跑一次，且不会把新系统的状态扇出到别的教程；
 * 3. 清除进度之后 `isRead` 回到 false，但 `Tutorials.byId` 仍能取到该教程（管理页「重看」有东西可打开）。
 */
class TutorialStoreTest {

    private lateinit var prefs: FakeSharedPreferences

    @Before
    fun setUp() {
        prefs = FakeSharedPreferences()
        SharedPreferencesUtil.sharedPreferences = prefs
    }

    @After
    fun tearDown() {
        SharedPreferencesUtil.sharedPreferences = null
    }

    /** 造一个不依赖 UI 的教程对象；target 用 [Activity]，本用例只关心 id/version。 */
    private fun tut(id: String, version: Int): Tutorial = tutorial(
        id = id,
        title = id,
        version = version,
        target = Activity::class.java,
        kind = TutorialKind.NOTICE,
    )

    private fun putVer(key: String, version: Int) {
        prefs.edit().putInt(key, version).apply()
    }

    // ==================== 键规则 ====================

    @Test
    fun keyOf_usesVersionPrefix() {
        assertEquals("tutorial_ver_video_main", TutorialStore.keyOf("video_main"))
        assertEquals("tutorial_ver_theme_notice", TutorialStore.keyOf("theme_notice"))
    }

    @Test
    fun readVersion_defaultsToMinusOne() {
        assertEquals(-1, TutorialStore.readVersion("local"))
        assertFalse(TutorialStore.isRead(tutorial("local", "缓存与下载", 2, Activity::class.java, TutorialKind.NOTICE)))
    }

    @Test
    fun markRead_and_isRead_roundTrip() {
        val t = tut("recommend", 1)
        assertFalse(TutorialStore.isRead(t))

        TutorialStore.markRead(t)
        assertTrue(TutorialStore.isRead(t))
        assertEquals(1, TutorialStore.readVersion("recommend"))

        // 版本升级后重新算未读，这是「内容变了要重新弹」的依据。
        assertFalse(TutorialStore.isRead(tut("recommend", 2)))
        assertEquals(1, TutorialStore.readVersion("recommend"))
    }

    @Test
    fun clear_removesProgress() {
        val t = tut("short_video", 4)
        TutorialStore.markRead(t)
        assertTrue(TutorialStore.isRead(t))

        TutorialStore.clear("short_video")

        assertEquals(-1, TutorialStore.readVersion("short_video"))
        assertFalse(TutorialStore.isRead(t))
        assertFalse(prefs.contains(TutorialStore.keyOf("short_video")))
    }

    @Test
    fun clear_removesLegacyHistoryKeys() {
        // 旧管理页会写 tutorial_history_<tag>；新系统不再读，但清除进度时要顺手清掉，免得留垃圾。
        putVer("tutorial_history_video_main", 1)
        putVer("tutorial_history_video", 1)

        TutorialStore.clear("video_main")

        assertFalse(prefs.contains("tutorial_history_video_main"))
        // video 是映射到 video_main 的旧 tag（见 LEGACY_KEY_MAP），它的残留键也要清。
        assertFalse(prefs.contains("tutorial_history_video"))
    }

    // ==================== 旧键迁移 ====================

    @Test
    fun migrate_mapsShiftedLegacyTagsToRealIds() {
        // 旧实现把「搜索」写进 tutorial_ver_message、「消息」写进 tutorial_ver_dynamic、
        // 「动态」写进 tutorial_ver_dynamic_info，整体错开一位。
        putVer("tutorial_ver_video", 1)
        putVer("tutorial_ver_message", 1)
        putVer("tutorial_ver_dynamic", 1)
        putVer("tutorial_ver_dynamic_info", 1)

        assertTrue(TutorialStore.migrateLegacyKeys())

        // 旧 video → 新 video_main：只有这条和 message→search 是「写进不同字符串的键」，能看出迁移。
        assertEquals(1, TutorialStore.readVersion("video_main"))
        assertEquals(1, TutorialStore.readVersion("search"))

        // 另外两条（dynamic→message、dynamic_info→dynamic）的目标键与旧 tag 同字符串，
        // 迁移前后读到的都是同一个值——这正是「新旧键共用键空间」的现场证据。
        assertEquals(1, TutorialStore.readVersion("message"))
        assertEquals(1, TutorialStore.readVersion("dynamic"))
        assertEquals(1, TutorialStore.readVersion("dynamic_info"))
    }

    @Test
    fun migrate_isOneShot() {
        putVer("tutorial_ver_video", 1)
        assertTrue(TutorialStore.migrateLegacyKeys())

        // 迁移之后再出现旧键（新系统自己也会写 tutorial_ver_dynamic 这类同字符串键），
        // 第二次迁移必须什么都不做，否则会把新系统状态扇出成别的教程已读。
        putVer("tutorial_ver_video", 3)
        assertFalse(TutorialStore.migrateLegacyKeys())
        assertEquals(1, TutorialStore.readVersion("video_main"))
    }

    @Test
    fun migrate_skipsWhenNewSystemAlreadyUsed() {
        // theme_notice 只有新系统会写：读到它就说明这台机器早就在新系统上了，
        // 此时同名的旧键其实已经是新系统的键，不能当旧键迁移。
        putVer("tutorial_ver_theme_notice", 1)
        putVer("tutorial_ver_message", 1)

        assertFalse(TutorialStore.migrateLegacyKeys())

        assertEquals(-1, TutorialStore.readVersion("search"))
        assertEquals(1, TutorialStore.readVersion("message"))
    }

    @Test
    fun migrate_doesNotDowngradeNewerRecord() {
        putVer("tutorial_ver_search", 5)
        putVer("tutorial_ver_message", 2)

        assertTrue(TutorialStore.migrateLegacyKeys())

        assertEquals(5, TutorialStore.readVersion("search"))
    }

    // ==================== 页面滑动提示 ====================

    @Test
    fun hint_keysMatchLegacyPagerFormat() {
        // 键格式必须与 TutorialHelper.showPagerTutorial 写的完全一致，否则重置会失效。
        assertEquals("tutorial_pager_VideoInfoActivity", TutorialStore.hintKeyOf("VideoInfoActivity"))
    }

    @Test
    fun hint_shownAndReset_roundTrip() {
        val page = "SearchActivity"
        // 历史语义：键不存在 = 提示还没看过；值 false = 看过。
        assertFalse(TutorialStore.isHintShown(page))

        TutorialStore.markHintShown(page)
        assertTrue(TutorialStore.isHintShown(page))
        // 存档格式与旧实现一致：值 false 表示看过。
        assertFalse(prefs.getBoolean(TutorialStore.hintKeyOf(page), true))

        TutorialStore.clearHint(page)
        assertFalse(TutorialStore.isHintShown(page))
        assertFalse(prefs.contains(TutorialStore.hintKeyOf(page)))
    }

    @Test
    fun hintPages_areDistinctAndKnown() {
        assertEquals(5, TutorialStore.HINT_PAGES.size)
        assertEquals(TutorialStore.HINT_PAGES.size, TutorialStore.HINT_PAGES.toSet().size)
        assertTrue(TutorialStore.HINT_PAGES.contains("SearchActivity"))
        assertTrue(TutorialStore.HINT_PAGES.contains("DynamicInfoActivity"))
    }

    @Test
    fun clearAllHints_clearsEveryKnownPage() {
        for (page in TutorialStore.HINT_PAGES) TutorialStore.markHintShown(page)

        TutorialStore.clearAllHints()

        for (page in TutorialStore.HINT_PAGES) {
            assertFalse(TutorialStore.isHintShown(page))
            assertFalse(prefs.contains(TutorialStore.hintKeyOf(page)))
        }
    }

    // ==================== 注册表与「重看」 ====================

    @Test
    fun registry_byId_roundTripsEveryTutorial() {
        assertEquals(11, Tutorials.all.size)
        for (t in Tutorials.all) {
            assertSame(t, Tutorials.byId(t.id))
        }
        assertNull(Tutorials.byId("no_such_tutorial"))
    }

    @Test
    fun registry_idsAreUnique() {
        val ids = Tutorials.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun registry_everyTutorialHasDescription() {
        // 管理页的描述直接取 descriptionRes；漏填就只是少一行文案，但新加教程时容易忘，故在这里兜。
        for (t in Tutorials.all) {
            assertNotNull("教程 ${t.id} 缺少 descriptionRes", t.descriptionRes)
        }
    }

    @Test
    fun registry_idsCollidingWithLegacyTags_areKnown() {
        // 这些 id 的存储键与旧 tag 同字符串，是「迁移必须一次性」的原因；
        // 新加教程若用了别的旧 tag（如 video），会悄悄继承老用户的旧进度，这里主动爆出来。
        val legacyTags = setOf(
            "recommend", "video", "space", "search", "message",
            "dynamic", "dynamic_info", "article", "short_video",
        )
        val collisions = Tutorials.all.map { it.id }.filter { it in legacyTags }.toSet()
        assertEquals(
            setOf(
                "recommend", "space", "search", "message",
                "dynamic", "dynamic_info", "article", "short_video",
            ),
            collisions
        )
    }

    @Test
    fun resetThenReplay_stateIsConsistent() {
        // 管理页「清除进度」→ 教程重新弹；「重看」→ 用 byId 取回来再交给 TutorialPagerActivity。
        val tutorial = Tutorials.byId("video_main")
        assertNotNull(tutorial)
        tutorial!!

        TutorialStore.markRead(tutorial)
        assertTrue(TutorialStore.isRead(tutorial))

        TutorialStore.clear(tutorial.id)
        assertFalse(TutorialStore.isRead(tutorial))

        val replay = Tutorials.byId(tutorial.id)
        assertSame(tutorial, replay)
        // 重看只是把内容再打开一遍，进度仍然是清除后的状态。
        assertEquals(-1, TutorialStore.readVersion(replay!!.id))
    }
}

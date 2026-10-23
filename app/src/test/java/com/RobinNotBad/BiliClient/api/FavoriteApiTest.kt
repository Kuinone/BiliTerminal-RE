package com.RobinNotBad.BiliClient.api

import com.RobinNotBad.BiliClient.model.VideoCard
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FavoriteApiTest {

    @Test
    fun parseFavoriteState_validResponse_fillsAllLists() {
        val json = """{"count":2,"list":[
            {"id":44233921,"fid":442339,"title":"默认收藏夹","fav_state":1,"media_count":85,"attr":0},
            {"id":936347621,"fid":9363476,"title":"自建收藏夹","fav_state":0,"media_count":2,"attr":22}
        ]}"""
        val folderList = ArrayList<String>()
        val fidList = ArrayList<Long>()
        val mediaIdList = ArrayList<Long>()
        val stateList = ArrayList<Boolean>()
        val countList = ArrayList<Int>()
        val maxCountList = ArrayList<Int>()

        FavoriteApi.parseFavoriteState(
            JSONObject(json), folderList, fidList, mediaIdList,
            stateList, countList, maxCountList
        )

        assertEquals("应解析出2条", 2, folderList.size)
        assertEquals("默认收藏夹", folderList[0])
        assertEquals("自建收藏夹", folderList[1])
        assertEquals("fid", 442339L, fidList[0])
        assertEquals("fid", 9363476L, fidList[1])
        assertEquals("media_id 取 id 字段", 44233921L, mediaIdList[0])
        assertEquals("media_id 取 id 字段", 936347621L, mediaIdList[1])
        assertTrue("fav_state=1 已收藏", stateList[0])
        assertFalse("fav_state=0 未收藏", stateList[1])
        assertEquals("media_count", 85, countList[0])
        assertEquals("默认收藏夹上限", 50000, maxCountList[0])
        assertEquals("自建收藏夹上限", 1000, maxCountList[1])
    }

    /** v3 接口不再返回 max_count，默认收藏夹只能靠 attr 的 bit1 判断（bit0 只管公开/私密） */
    @Test
    fun parseFavoriteState_defaultFolderDetectedByAttrNotIndex() {
        val json = """{"list":[
            {"id":2,"fid":3,"title":"自建","fav_state":0,"media_count":1,"attr":22},
            {"id":1,"fid":2,"title":"默认","fav_state":1,"media_count":999,"attr":0}
        ]}"""
        val folderList = ArrayList<String>()
        val fidList = ArrayList<Long>()
        val mediaIdList = ArrayList<Long>()
        val stateList = ArrayList<Boolean>()
        val countList = ArrayList<Int>()
        val maxCountList = ArrayList<Int>()

        FavoriteApi.parseFavoriteState(
            JSONObject(json), folderList, fidList, mediaIdList,
            stateList, countList, maxCountList
        )

        assertEquals("先出现的是自建收藏夹", 1000, maxCountList[0])
        assertEquals("默认收藏夹不靠下标判断", 50000, maxCountList[1])
    }

    @Test
    fun parseFavoriteState_mediaCountMissing_defaultsZero() {
        val json = """{"list":[{"id":9,"fid":1,"title":"a","fav_state":0,"attr":0}]}"""
        val folderList = ArrayList<String>()
        val fidList = ArrayList<Long>()
        val mediaIdList = ArrayList<Long>()
        val stateList = ArrayList<Boolean>()
        val countList = ArrayList<Int>()
        val maxCountList = ArrayList<Int>()

        FavoriteApi.parseFavoriteState(
            JSONObject(json), folderList, fidList, mediaIdList,
            stateList, countList, maxCountList
        )

        assertEquals("media_count 缺失兜底0", 0, countList[0])
        assertEquals("attr=0 即默认收藏夹", 50000, maxCountList[0])
        assertEquals("id 字段即 media_id", 9L, mediaIdList[0])
    }

    @Test
    fun parseFavoriteState_nullOrNoList_leavesListsEmpty() {
        val folderList = ArrayList<String>()
        val fidList = ArrayList<Long>()
        val mediaIdList = ArrayList<Long>()
        val stateList = ArrayList<Boolean>()
        val countList = ArrayList<Int>()
        val maxCountList = ArrayList<Int>()

        FavoriteApi.parseFavoriteState(
            null, folderList, fidList, mediaIdList, stateList, countList, maxCountList
        )
        assertTrue("data=null 不崩溃", folderList.isEmpty())
        assertTrue("data=null 不塞 media_id", mediaIdList.isEmpty())

        val noList = JSONObject("""{"count":0}""")
        FavoriteApi.parseFavoriteState(
            noList, folderList, fidList, mediaIdList, stateList, countList, maxCountList
        )
        assertTrue("无list字段不崩溃", folderList.isEmpty())
        assertTrue("无list字段不塞 media_id", mediaIdList.isEmpty())
    }

    /**
     * attr 位定义（bilibili-API/docs/fav/info.md）：bit0=1 私有、bit1=1 非默认。
     * 真实值：默认夹 0 / 自建夹 22（bit0=0 公开 + bit1=1 非默认 + 其它位），所以只看 bit1。
     */
    @Test
    fun isDefaultFolder_checksAttrBit1() {
        assertTrue("attr=0 是默认收藏夹", FavoriteApi.isDefaultFolder(0))
        assertFalse("attr=2 的 bit1 已置位，不是默认收藏夹", FavoriteApi.isDefaultFolder(2))
        assertFalse("attr=22（线上自建夹真实值）不是默认收藏夹", FavoriteApi.isDefaultFolder(22))
        assertFalse("attr=3 私密自建，bit1 已置位", FavoriteApi.isDefaultFolder(3))
    }

    @Test
    fun maxCountOf_followsConvention() {
        assertEquals(50000, FavoriteApi.maxCountOf(true))
        assertEquals(1000, FavoriteApi.maxCountOf(false))
    }

    /** 单测里别调 VideoCard 的 android 相关成员，只 new + 塞 aid */
    private fun card(aid: Long): VideoCard {
        val card = VideoCard()
        card.aid = aid
        card.title = "t$aid"
        return card
    }

    @Test
    fun buildResources_joinsAidAndType() {
        val cards = listOf(card(1L), card(2L))
        assertEquals("1:2,2:2", FavoriteApi.buildResources(cards))
    }

    @Test
    fun buildResources_skipsNullAndBadAid() {
        @Suppress("UNCHECKED_CAST")
        val cards = listOf<VideoCard?>(card(0L), null, card(-5L), card(3L)) as List<VideoCard>
        assertEquals("跳过 aid<=0 与 null 后只剩一条", "3:2", FavoriteApi.buildResources(cards))
    }

    @Test
    fun buildResources_nullOrEmpty_returnsEmpty() {
        assertEquals("", FavoriteApi.buildResources(null))
        assertEquals("", FavoriteApi.buildResources(emptyList()))
    }

    @Test
    fun resourceErrorMsg_mapsKnownCodes() {
        assertEquals("成功无文案", "", FavoriteApi.resourceErrorMsg(0))
        assertEquals("还没有登录喵~", FavoriteApi.resourceErrorMsg(-101))
        assertEquals("登录凭证已失效，请重新登录", FavoriteApi.resourceErrorMsg(-111))
        assertEquals("请求出错了，请稍后再试", FavoriteApi.resourceErrorMsg(-400))
        assertEquals("内容不存在，可能已经被删除了", FavoriteApi.resourceErrorMsg(11010))
        assertEquals("未知码带原码", "操作失败（错误码 7）", FavoriteApi.resourceErrorMsg(7))
    }
}

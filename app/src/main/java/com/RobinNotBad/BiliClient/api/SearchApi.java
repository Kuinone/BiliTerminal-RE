package com.RobinNotBad.BiliClient.api;

import com.RobinNotBad.BiliClient.model.ArticleCard;
import com.RobinNotBad.BiliClient.model.UserInfo;
import com.RobinNotBad.BiliClient.model.VideoCard;
import com.RobinNotBad.BiliClient.util.NetWorkUtil;
import com.RobinNotBad.BiliClient.util.StringUtil;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

//搜索API 自己写的
//逐渐感觉拆json是个很爽的事（
//2023-07-14

public class SearchApi {

    /**
     * seid 是 B 站为一次搜索会话下发的分页标记，翻页时要原样带回。
     * <p>
     * 早先它是两个全局静态字段（seid + search_keyword）：同时打开两个搜索页时，
     * 后发起的那一页会把关键词和 seid 顶掉，前一页再翻页就会带着别人的关键词/seid 去请求（结果串页，
     * 而且 seid 与关键词不匹配时 B 站可能直接返回空列表）。现在按关键词隔离，
     * 并用访问序 LRU 限制条目数，避免用户搜过多少词就常驻多少条。
     */
    private static final int MAX_SEID_ENTRIES = 16;
    private static final LinkedHashMap<String, String> SEID_BY_KEYWORD =
            new LinkedHashMap<String, String>(MAX_SEID_ENTRIES, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > MAX_SEID_ENTRIES;
                }
            };

    private static synchronized String getSeid(String keyword) {
        String seid = SEID_BY_KEYWORD.get(keyword);
        return seid == null ? "" : seid;
    }

    private static synchronized void putSeid(String keyword, String seid) {
        if (seid != null && !seid.isEmpty()) SEID_BY_KEYWORD.put(keyword, seid);
    }

    public static JSONArray search(String keyword, int page) throws IOException, JSONException {
        String url = "https://api.bilibili.com/x/web-interface/wbi/search/all/v2?";
        url += "page=" + page +
                "&keyword=" + URLEncoder.encode(keyword, "UTF-8") + "&seid=" + getSeid(keyword);

        JSONObject all = NetWorkUtil.getJson(ConfInfoApi.signWBI(url)); // 得到一整个json

        if (all.isNull("data"))
            return null;
        JSONObject data = all.getJSONObject("data"); // 搜索列表中的data项又是一个json，把它提出来

        // optString 而非 getString：个别风控/降级响应里没有 seid 字段，getString 会抛 JSONException
        // 把整页搜索结果一起带走
        putSeid(keyword, data.optString("seid", ""));

        throwIfRiskControl(data);

        if (data.has("result") && !data.isNull("result"))
            return data.getJSONArray("result"); // 其实这还不是我们要的结果，下面的函数对它进行再次拆解 这里做了判空
        else
            return null;
    }

    public static Object searchType(String keyword, int page, String type) throws IOException, JSONException {
        return searchType(keyword, page, type, null);
    }

    /**
     * 分类搜索（带排序）。
     * <p>
     * 排序参数只在这条接口上有——聚合搜索（{@link #search}）的 {@code all/v2} 只接受 keyword，
     * 所以视频分类一旦选了「综合」以外的排序就必须改走这里。
     *
     * @param order 结果排序方式，null 或空表示用服务端默认（综合 totalrank）。视频与专栏可用
     *              totalrank / click / pubdate / dm / stow / scores（专栏另有 attention）；
     *              取值定义见 {@code bilibili-API/docs/search/search_request.md}「分类搜索」一节。
     */
    public static Object searchType(String keyword, int page, String type, String order)
            throws IOException, JSONException {
        String url = "https://api.bilibili.com/x/web-interface/wbi/search/type?";
        url += "page=" + page +
                "&keyword=" + URLEncoder.encode(keyword, "UTF-8") + "&search_type=" + type + "&seid=" + getSeid(keyword);
        if (order != null && !order.isEmpty()) url += "&order=" + order;

        JSONObject all = NetWorkUtil.getJson(ConfInfoApi.signWBI(url)); // 得到一整个json

        if (all.isNull("data"))
            return null;
        JSONObject data = all.getJSONObject("data"); // 搜索列表中的data项又是一个json，把它提出来

        putSeid(keyword, data.optString("seid", ""));

        throwIfRiskControl(data);

        if (data.has("result") && !data.isNull("result"))
            return data.get("result"); // 其实这还不是我们要的结果，下面的函数对它进行再次拆解 这里做了判空
        else
            return null;
    }

    /**
     * 搜索接口的**风控降级响应**要当成错误抛出去，不能当成"没有结果"。
     * <p>
     * 形态（26.10.24 实测抓到）：`{"code":0,"message":"OK","data":{"v_voucher":"voucher_…"}}`——
     * `code` 是 0，但 `data` 里**既没有 `result` 也没有 `seid`**，只有一个 `v_voucher`。
     * 以前这里返回 null，页面只拿到"空列表"，用户看到的就是「有时候搜什么都不出来」，
     * 还以为是排序参数拼错了。现在抛 {@link ApiErrorText#searchRejected()} 让页面把原因说出来。
     * <p>
     * 判据只认 `v_voucher`：真正"没有匹配结果"的搜索会返回带 `result` 的响应（数组可能为空），
     * 不能因为 `result` 为空就报风控。
     */
    private static void throwIfRiskControl(JSONObject data) throws IOException {
        if (data.has("result") && !data.isNull("result")) return;
        if (data.has("v_voucher")) throw new IOException(ApiErrorText.searchRejected());
    }

    public static void getVideosFromSearchResult(JSONArray input, ArrayList<VideoCard> videoCardList, boolean first)
            throws JSONException {
        for (int i = 0; i < input.length(); i++) { // 遍历所有的分类，找到视频那一项
            JSONObject typecard = input.getJSONObject(i);
            String type = typecard.getString("result_type");
            if (type.equals("video")) {
                JSONArray data = typecard.getJSONArray("data"); // 把这个列表提出来，接着拆
                for (int j = 0; j < data.length(); j++) {
                    parseVideoEntry(data.getJSONObject(j), videoCardList);
                }
            } else if (type.equals("media_bangumi") && first) {
                // 聚合搜索里的番剧条目与 search_type=media_bangumi 的 data.result 同构，
                // 不再保留第二套解析：旧实现用 getString 取字段（缺 index_show 时整页抛 JSONException）、
                // 封面也没补 scheme。两处统一走 getBangumiFromSearchResult，容错与封面规则只维护一份。
                JSONArray data = typecard.getJSONArray("data");
                getBangumiFromSearchResult(data, videoCardList);
            }
        }
    }

    /**
     * 解析分类搜索（{@code search_type=video}）的视频结果。
     * <p>
     * 与 {@link #getVideosFromSearchResult} 的区别只在输入形状：分类搜索返回的 {@code data.result}
     * 直接就是视频条目数组，外面没有 {@code result_type} 那一层，也不会混入番剧。
     * 条目字段与聚合搜索里的视频条目同构，故共用 {@link #parseVideoEntry}。
     */
    public static void getVideosFromTypeResult(JSONArray input, ArrayList<VideoCard> videoCardList)
            throws JSONException {
        if (input == null) return;
        for (int i = 0; i < input.length(); i++) {
            JSONObject card = input.optJSONObject(i);
            if (card != null) parseVideoEntry(card, videoCardList);
        }
    }

    /**
     * 单条视频搜索结果 → VideoCard。聚合搜索与分类搜索共用这一份，改字段只改这里。
     */
    private static void parseVideoEntry(JSONObject card, ArrayList<VideoCard> videoCardList) throws JSONException {
        if (!card.optString("type", "").equals("video"))
            return; // 警惕虚假视频卡片（阿B为什么要把直播间放进视频结果里）

        String title = card.getString("title");
        title = title.replace("<em class=\"keyword\">", "").replace("</em>", "");
        // 标题里的红字，知道怎么显示但因为懒所以直接删（//显示方式可以用imagespan，参照表情包那部分程序（EmoteUtil），期待后人补齐（
        title = StringUtil.htmlToString(title);

        String bvid = card.getString("bvid");
        long aid = card.getLong("aid");
        String cover = httpsUrl(card.optString("pic", ""));
        String upName = card.getString("author");

        long play = card.getLong("play");
        String playTimesStr = StringUtil.toWan(play) + "观看";

        videoCardList.add(new VideoCard(title, upName, playTimesStr, cover, aid, bvid, "video"));
    }

    /**
     * 搜索结果里的图片都是协议相对地址（{@code //i0.hdslb.com/...}），不补 scheme 的话
     * Glide 不认、{@code GlideUtil} 也不会加尺寸后缀，封面必然加载失败。
     * <p>
     * 统一补 <b>https</b>（与 {@link #getBangumiFromSearchResult} 同一规则）：本应用只走 https，
     * 明文白名单只用于兜底，不该在客户端主动拼 http 地址。
     */
    private static String httpsUrl(String url) {
        if (url == null) return "";
        return url.startsWith("//") ? "https:" + url : url;
    }

    /**
     * 番剧条目解析：聚合搜索与单独搜索共用这一份。
     * <p>
     * 单独搜索（search_type=media_bangumi）时 input 就是 data.result 本身（一个番剧条目数组），
     * 外面没有再套一层 result_type；{@link #getVideosFromSearchResult} 的 media_bangumi 分支
     * 则把 type 卡里的 data 数组传进来，两者条目同构，故复用同一套容错与封面规则。
     * <p>
     * 番剧条目里没有 aid/bvid，借用 VideoCard 的已有字段承载：
     * aid=media_id（剧集 mdid，番剧详情页正是用它拉取信息）、bvid=season_id（ssid，仅作稳定 id 用）、
     * type 固定 "media_bangumi"（VideoCardHolder 已按该 type 跳番剧详情页）。
     *
     * @param input           search_type=media_bangumi 返回的 data.result
     * @param videoCardList   结果追加到这里
     */
    public static void getBangumiFromSearchResult(JSONArray input, ArrayList<VideoCard> videoCardList)
            throws JSONException {
        if (input == null) return;

        for (int i = 0; i < input.length(); i++) {
            JSONObject card = input.optJSONObject(i);
            if (card == null) continue;

            String title = card.optString("title", "");
            title = title.replace("<em class=\"keyword\">", "").replace("</em>", "");
            title = StringUtil.htmlToString(title);

            // 搜索结果里的封面是协议相对地址（//i0.hdslb.com/...），而 GlideUtil 对不以 http 开头的
            // url 会原样返回、不加尺寸后缀，Glide 本身也不认无 scheme 的地址 —— 不补 scheme 封面必然失败
            String cover = card.optString("cover", "");
            if (cover.startsWith("//")) cover = "https:" + cover;

            String areas = card.optString("areas", "");          // 地区，卡片第二行
            String indexShow = card.optString("index_show", ""); // 更新进度（如"全14话"），卡片第三行
            // index_show 是聚合搜索（all/v2）才稳定给出的字段，单独的 search_type=media_bangumi
            // 接口文档里没有它，实测可能为空；为空时退用 styles（风格，如"原创/科幻/推理"），
            // 免得卡片第三行整行消失（VideoCardHolder 对空 view 是直接隐藏）
            if (indexShow.isEmpty()) indexShow = card.optString("styles", "");
            long mediaId = card.optLong("media_id", 0);
            String seasonId = card.optString("season_id", "");

            videoCardList.add(new VideoCard(title, areas, indexShow, cover, mediaId, seasonId, "media_bangumi"));
        }
    }

    public static void getUsersFromSearchResult(JSONArray input, List<UserInfo> userInfoList) throws JSONException {
        for (int i = 0; i < input.length(); i++) {
            JSONObject card = input.getJSONObject(i); // 获得用户卡片

            long mid = card.getLong("mid");
            String name = card.getString("uname");
            String avatar = httpsUrl(card.getString("upic"));
            String sign = card.getString("usign");
            int fans = card.getInt("fans");
            int level = card.getInt("level");

            userInfoList.add(new UserInfo(mid, name, avatar, sign, fans, 0, level, false, "", 0, "", 0));
        }
    }

    public static void getArticlesFromSearchResult(JSONArray input, ArrayList<ArticleCard> articleCardList)
            throws JSONException {
        for (int i = 0; i < input.length(); i++) {
            ArticleCard articleCard = new ArticleCard();
            JSONObject card = input.getJSONObject(i); // 获得专栏卡片

            articleCard.id = card.getLong("id");
            if (card.getJSONArray("image_urls").length() > 0)
                articleCard.cover = httpsUrl(card.getJSONArray("image_urls").getString(0));
            else
                articleCard.cover = "";
            articleCard.upName = card.getString("category_name");
            articleCard.title = StringUtil.htmlReString(card.getString("title"));
            articleCard.view = StringUtil.toWan(card.getInt("view")) + "阅读";

            articleCardList.add(articleCard);
        }
    }

    /**
     * 获取搜索建议
     *
     * @param keyword 搜索关键词
     * @return 返回搜索建议列表，最多10条
     * @throws IOException   网络请求异常
     * @throws JSONException JSON解析异常
     */
    public static ArrayList<String> getSearchSuggestions(String keyword) throws IOException, JSONException {
        ArrayList<String> suggestions = new ArrayList<>();

        if (keyword == null || keyword.trim().isEmpty()) {
            return suggestions;
        }

        String url = "https://s.search.bilibili.com/main/suggest?term=" + URLEncoder.encode(keyword, "UTF-8");

        JSONObject response = NetWorkUtil.getJson(url);

        if (response.getInt("code") == 0 && response.has("result")) {
            JSONObject result = response.getJSONObject("result");
            if (result.has("tag") && !result.isNull("tag")) {
                JSONArray tagArray = result.getJSONArray("tag");
                for (int i = 0; i < tagArray.length(); i++) {
                    JSONObject tagObj = tagArray.getJSONObject(i);
                    String value = tagObj.getString("value");
                    suggestions.add(value);
                }
            }
        }

        return suggestions;
    }

    public static String getDefaultSearchContent() throws IOException, JSONException {
        String url = "https://api.bilibili.com/x/web-interface/wbi/search/default";
        JSONObject response = NetWorkUtil.getJson(ConfInfoApi.signWBI(url));
        if (response.getInt("code") == 0 && response.has("data")) {
            JSONObject data = response.getJSONObject("data");
            if (data.has("show_name") && !data.isNull("show_name")) {
                return data.getString("show_name");
            }
        }
        return null;
    }
}

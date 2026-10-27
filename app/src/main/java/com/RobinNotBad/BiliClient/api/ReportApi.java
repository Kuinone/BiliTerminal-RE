package com.RobinNotBad.BiliClient.api;

import com.RobinNotBad.BiliClient.util.NetWorkUtil;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 举报（评论）。
 *
 * <h3>接口来源</h3>
 * 全部取自 B 站官方 Android 客户端 9.16.1 的静态分析（apktool + jadx）＋ 它真正使用的 H5 页面
 * （26.10.27 逐条核对，不是猜的）：
 * <ul>
 *   <li>{@code POST https://api.bilibili.com/x/v2/reply/report}
 *       —— {@code com.bilibili.app.comm.comment2.model.BiliCommentApiService#report}，
 *       参数 {@code access_key / oid / type / rpid / reason / content}。</li>
 *   <li>{@code GET https://api.bilibili.com/x/v2/reply/report/metadata}
 *       —— 举报理由表**由服务端下发**，见下面的「理由表」一节。</li>
 * </ul>
 *
 * <h3>理由表：为什么不是 0~17</h3>
 * 本类第一版照 {@code bilibili-API/docs/comment/action.md} 的旧快照写成 {@code 0..8}，
 * 26.10.27 重新核对官方实现后推翻——**快照那张表是错的**（它是 0..17 连续编号，官方现行不是）：
 * <ul>
 *   <li>官方客户端**没有原生的评论举报界面**：它把举报交给 H5 页面
 *       （{@code ConfigManager.get("comment.report_url", "https://www.bilibili.com/h5/comment/report")}，
 *       见 {@code RouterReducer$handleToUserReport$1} 与 {@code comment2/comments/viewmodel/d}）。
 *       所以 dex 里既没有理由码表、也没有任何 {@code comment_global_string_*} 的资源 id 引用
 *       （33 个 dex 全量扫过：资源名字面量只出现在一张历史名单数组里，id 字节引用为 0）。</li>
 *   <li>那个 H5 页面（{@code comment-h5} bundle）里：先 {@code GET /x/v2/reply/report/metadata}
 *       取服务端理由表，**失败才用一张静态兜底表**。下面 {@link #FALLBACK_REASONS} 就是逐项抄来的
 *       兜底表原文（中文取自页面 i18n，码值取自同一处的 {@code value}）。</li>
 *   <li>所以官方现行是 **18 个非连续码**：0,1,2,3,4,5,7,8,9,10,12,15,17,18,19,20,22,23。
 *       **没有 6（政治）、11、13、14、16、21**；8 的文案是「视频不相关」而不是「内容不相关」。</li>
 * </ul>
 * 本类因此改成：**优先用服务端 {@code metadata}（与官方同一套），失败回落静态兜底表**，
 * 不再自己维护一份可能过期的码表。
 *
 * <h3>正文规则</h3>
 * 「其他」（以及服务端标了 {@code content_required} 的理由）必须填正文，长度 **2~200** 字符
 * ——数字取自 H5 自己的校验 {@code if (otherContent && otherContent.length < 2)} 与
 * 文案 {@code reportOtherLengthTip}「自定义举报内容长度为2-200字符」。
 * （本类第一版写 3，来源是 APK 里那条更老的客户端字符串「举报理由不能少于3个字」，与现行实现不符。）
 *
 * <h3>为什么只有评论，没有视频</h3>
 * 视频举报的**提交**接口（{@code POST app.bilibili.com/x/v2/view/report/add}）其实也能用
 * （匿名实测只回 {@code -101 账号未登录}），但**拉理由列表**的
 * {@code GET app.bilibili.com/x/v2/view/report} 走的是 {@code DefaultRequestInterceptor}，
 * 要求 {@code appkey} + {@code ts} + {@code sign}，而 {@code appsec} 在官方包的 native 里，
 * 第三方算不出来（实测：不带 appkey 回 {@code -400 请求错误}，带假签名回
 * {@code -3 签名错误}）。理由 {@code type}/{@code reason} 是服务端下发的字符串，
 * 本地硬编码可能与官方分类错位，所以视频举报暂不做。
 */
public class ReportApi {

    /** 「其他」理由码：静态兜底表里唯一需要正文的一项。 */
    public static final int REASON_OTHER = 0;

    /** 正文下限，见类注释「正文规则」。 */
    public static final int CONTENT_MIN_LENGTH = 2;

    /** 正文上限，见类注释「正文规则」（H5 文案写的是 2-200）。 */
    public static final int CONTENT_MAX_LENGTH = 200;

    /**
     * 一条可选的举报理由。
     *
     * <p>{@code contentRequired} 来自服务端的 {@code content_required} 字段；静态兜底表里只有
     * 「其他」为 true（与 H5 的 {@code 0 === reasonChoosed} 判断一致）。前端**不能**自己假设
     * 「只有 0 要正文」——服务端随时可以把某一项标成必填。
     */
    public static final class Reason {
        public final int code;
        public final String label;
        public final boolean contentRequired;

        public Reason(int code, String label, boolean contentRequired) {
            this.code = code;
            this.label = label;
            this.contentRequired = contentRequired;
        }

        @Override
        public String toString() {
            return code + ":" + label + (contentRequired ? "(需正文)" : "");
        }
    }

    /**
     * 官方 H5 举报页的静态兜底表（{@code comment-h5} chunk 919 里的 {@code _(...)} 函数），
     * **顺序即页面上的展示顺序**（按「违反法律法规 / 谣言类不实信息 / 侵犯个人权益 / 有害社区环境」分组）。
     * 服务端 {@code metadata} 可用时不会用到它。
     */
    private static final Reason[] FALLBACK_REASONS = {
            new Reason(9, "违法违规", false),
            new Reason(2, "色情", false),
            new Reason(10, "低俗", false),
            new Reason(12, "赌博诈骗", false),
            new Reason(23, "违法信息外链", false),
            new Reason(19, "涉政谣言", false),
            new Reason(22, "虚假不实信息", false),
            new Reason(20, "涉社会事件谣言", false),
            new Reason(7, "人身攻击", false),
            new Reason(15, "侵犯隐私", false),
            new Reason(1, "垃圾广告", false),
            new Reason(4, "引战", false),
            new Reason(5, "剧透", false),
            new Reason(3, "刷屏", false),
            new Reason(8, "视频不相关", false),
            new Reason(18, "违规抽奖", false),
            new Reason(17, "青少年不良信息", false),
            new Reason(0, "其他", true),
    };

    /** 服务端下发、当前进程内缓存（举报理由一天也不会变，没必要每次开弹窗都拉一遍）。 */
    private static volatile List<Reason> cachedReasons;

    /** 静态兜底表；返回不可变副本，防止调用方改到内部状态。 */
    public static List<Reason> defaultReasons() {
        List<Reason> list = new ArrayList<>(FALLBACK_REASONS.length);
        Collections.addAll(list, FALLBACK_REASONS);
        return Collections.unmodifiableList(list);
    }

    /** 已缓存的服务端理由表；没有就返回 {@code null}。 */
    public static List<Reason> cachedReasons() {
        return cachedReasons;
    }

    /** 缓存命中就用服务端的，否则用静态兜底表——弹窗打开时同步调用它，不能有网络等待。 */
    public static List<Reason> cachedOrDefaultReasons() {
        List<Reason> cached = cachedReasons;
        return cached != null && !cached.isEmpty() ? cached : defaultReasons();
    }

    /** 仅供测试与「退出登录」这类场景清缓存。 */
    public static void clearReasonCache() {
        cachedReasons = null;
    }

    private static boolean isTruthy(JSONObject obj, String key) {
        Object v = obj.opt(key);
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Number) return ((Number) v).intValue() != 0;
        if (v instanceof String) {
            String s = ((String) v).trim();
            return "1".equals(s) || "true".equalsIgnoreCase(s);
        }
        return false;
    }

    /**
     * 解析 {@code /x/v2/reply/report/metadata} 的响应。抽成纯函数是为了让这块能被 JVM 单测钉死
     * ——解析写错了不会崩，只会把举报提交到错误的分类。
     *
     * <p>两种形态官方都在用（见 H5 的 {@code getRemoteReportGroup}）：
     * ① 新的扁平 {@code data.reason_list[{tag_id, tag_name, content_required}]}；
     * ② 旧的 {@code data.report_groups[{name, report_options[{value, label}]}]}。
     * 两者都拿不到可用项时返回 {@code null}，由调用方回落静态兜底表。
     */
    public static List<Reason> parseReasons(String json) throws JSONException {
        if (json == null) return null;
        JSONObject root = new JSONObject(json);
        if (root.optInt("code", -1) != 0) return null;
        JSONObject data = root.optJSONObject("data");
        if (data == null) return null;

        List<Reason> out = new ArrayList<>();
        JSONArray flat = data.optJSONArray("reason_list");
        if (flat != null) {
            for (int i = 0; i < flat.length(); i++) {
                JSONObject item = flat.optJSONObject(i);
                if (item == null) continue;
                int code = item.optInt("tag_id", Integer.MIN_VALUE);
                String label = item.optString("tag_name", "").trim();
                if (code == Integer.MIN_VALUE || label.isEmpty()) continue;
                out.add(new Reason(code, label, isTruthy(item, "content_required")));
            }
        }
        if (out.isEmpty()) {
            JSONArray groups = data.optJSONArray("report_groups");
            if (groups != null) {
                for (int i = 0; i < groups.length(); i++) {
                    JSONObject group = groups.optJSONObject(i);
                    if (group == null) continue;
                    JSONArray options = group.optJSONArray("report_options");
                    if (options == null) continue;
                    for (int j = 0; j < options.length(); j++) {
                        JSONObject option = options.optJSONObject(j);
                        if (option == null) continue;
                        int code = option.optInt("value", Integer.MIN_VALUE);
                        String label = option.optString("label", "").trim();
                        if (code == Integer.MIN_VALUE || label.isEmpty()) continue;
                        out.add(new Reason(code, label, isTruthy(option, "content_required")));
                    }
                }
            }
        }
        return out.isEmpty() ? null : Collections.unmodifiableList(out);
    }

    /**
     * 拉服务端理由表并写缓存。**需要登录**（匿名实测回 {@code -101 账号未登录}）。
     *
     * <p>失败不抛给调用方处理——返回值就是「本次能不能用上」：{@code null} 表示照旧用静态兜底表。
     * 失败原因（网络异常 / code != 0 / 解析不出东西）都吞掉，理由表拿不到不该打断举报流程。
     */
    public static List<Reason> fetchReasons(long oid, int type) {
        try {
            String url = "https://api.bilibili.com/x/v2/reply/report/metadata?oid=" + oid + "&type=" + type;
            List<Reason> reasons = parseReasons(NetWorkUtil.getJson(url).toString());
            if (reasons != null) cachedReasons = reasons;
            return reasons;
        } catch (Exception ignored) {
            return null;
        }
    }

    /** 这条理由要不要正文。静态兜底表里只有「其他」为 true。 */
    public static boolean needsContent(Reason reason) {
        return reason != null && reason.contentRequired;
    }

    /**
     * 正文够不够。不要正文的理由一律放行（正文会被忽略，不该因为空串拦住提交）；
     * 要正文的按 {@link #CONTENT_MIN_LENGTH}~{@link #CONTENT_MAX_LENGTH} 卡，trim 后计算。
     */
    public static boolean isContentAcceptable(Reason reason, String content) {
        if (!needsContent(reason)) return true;
        String text = content == null ? "" : content.trim();
        return text.length() >= CONTENT_MIN_LENGTH && text.length() <= CONTENT_MAX_LENGTH;
    }

    /**
     * 组装请求体（**不含 csrf**，csrf 由 {@link #reportReply} 补）。
     *
     * <p>抽成纯函数是为了让「参数名/正文只在需要时带上」这几件事能被 JVM 单测钉死——这类拼串
     * 改错了不会崩，只会静默把举报提交成别的分类。
     */
    public static NetWorkUtil.FormData buildReportForm(long oid, int type, long rpid,
                                                      Reason reason, String content) {
        NetWorkUtil.FormData form = new NetWorkUtil.FormData()
                .put("type", type)
                .put("oid", oid)
                .put("rpid", rpid)
                .put("reason", reason == null ? REASON_OTHER : reason.code);
        // 官方只在需要正文的那一档才带 content（H5：`if (0 === reason) { ...; w.content = otherContent }`）。
        // 其它理由也带上只会让服务端多一个无用字段，且不该被正文左右。
        if (needsContent(reason)) {
            form.put("content", content == null ? "" : content.trim());
        }
        return form;
    }

    /**
     * 举报一条评论。
     *
     * @param oid        评论区 oid（视频 aid / 动态 id 等，随 {@code type} 变）
     * @param type       评论区类型代码（视频=1，见 {@code bilibili-API/docs/comment/readme.md}）
     * @param rpid       目标评论 rpid
     * @param reason     理由，来自 {@link #cachedOrDefaultReasons()} / {@link #fetchReasons}
     * @param content    需要正文时的内容；其它理由忽略
     * @return 服务端 code，0 表示成功；文案见 {@link #errorMsg(int)}
     */
    public static int reportReply(long oid, int type, long rpid, Reason reason, String content)
            throws IOException, JSONException {
        String url = "https://api.bilibili.com/x/v2/reply/report";
        String reqBody = buildReportForm(oid, type, rpid, reason, content)
                .put("csrf", NetWorkUtil.currentCsrf())
                .toString();
        JSONObject result = new JSONObject(Objects.requireNonNull(
                NetWorkUtil.post(url, reqBody, NetWorkUtil.webHeaders).body()).string());
        return result.getInt("code");
    }

    /**
     * 返回码 → 人话。空串表示成功。
     *
     * <p>码表来源：`bilibili-API/docs/comment/action.md`「举报评论」的 json 回复段
     * （-101 / -102 / -111 / -400 / -403 / -404 / -500 / -509 / 12002 / 12006 / 12008 /
     * 12009 / 12019 / 12077）＋ 官方客户端 9.16.1 的提示文案（`CD时间未到不能举报`
     * 对应下面 12026）。
     *
     * <p>**这些码是「文档 + 客户端文案」推导出来的，没有逐条在真机上撞过**：举报链路实测只
     * 拿到过 -352（模拟器未登录，风控先于业务校验）。所以每条都给了具体文案而不是留白——
     * 万一线上码值与文档有出入，用户至少看到一句能读懂的话，而不是「操作失败（错误码 …）」。
     * 最常见的两条是 12008（已经举报过）与 12077（正文长度不合规）。
     */
    public static String errorMsg(int code) {
        switch (code) {
            case 0:
                return "";
            case -101:
                return ApiErrorText.loginRequired();
            case -102:
                return "账号已被封停，无法举报";
            case -111:
                return ApiErrorText.tokenExpired();
            case -352:
            case -412:
                return ApiErrorText.riskRejected();
            case -400:
                return ApiErrorText.requestIllegal();
            case -403:
                return "权限不足，无法举报这条评论";
            case -404:
                return "评论不存在";
            case -500:
                return "服务器出错了，请稍后再试";
            case -509:
                return "操作过于频繁，请稍后再试";
            case 12002:
                return "评论区已关闭";
            case 12006:
                return "没有这条评论";
            case 12008:
                return "你已经举报过这条评论了";
            case 12009:
                return "评论类型不合法，这条评论无法举报";
            case 12019:
                return "举报太频繁了，请稍后再试";
            case 12026:
                // 客户端原文「CD时间未到不能举报」——与 12019 都属冷却拒绝，措辞上区分开，
                // 免得看日志时两个码给同一句话
                return "刚举报过，冷却中，请稍后再试";
            case 12077:
                return "举报理由过长或过短";
            default:
                return ApiErrorText.opFailed(code);
        }
    }
}

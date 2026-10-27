package com.RobinNotBad.BiliClient.api;

import com.RobinNotBad.BiliClient.util.NetWorkUtil;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Objects;

/**
 * 举报（评论）。
 *
 * <h3>接口来源</h3>
 * 全部取自 B 站官方 Android 客户端 9.16.1 的静态分析（apktool + jadx），不是猜的：
 * <ul>
 *   <li>{@code POST https://api.bilibili.com/x/v2/reply/report}
 *       —— {@code com.bilibili.app.comm.comment2.model.BiliCommentApiService#report}，
 *       逆向出的参数是 {@code access_key / oid / type / rpid / reason / content}。</li>
 *   <li>理由码表对得上客户端自己的文案（{@code comment_global_string_270} 垃圾广告、
 *       {@code _222} 色情、{@code _17} 刷屏、{@code _109} 引战、{@code _209} 剧透、
 *       {@code _247} 人身攻击），完整 0~8 见 {@code bilibili-API/docs/comment/action.md}。</li>
 *   <li>{@code 举报理由不能少于3个字}（{@code comment_global_string_171}）与
 *       {@code CD时间未到不能举报}（{@code _178}）也是客户端原文，对应下面的长度校验与 12026 文案。</li>
 * </ul>
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

    /** 评论举报的「其他」理由码：只有它需要用户填写 {@code content}。 */
    public static final int REASON_OTHER = 0;

    /**
     * 「其他」理由的正文下限。客户端原文见类注释
     * （{@code 举报理由不能少于3个字}），低于它服务端会拒。
     */
    public static final int OTHER_CONTENT_MIN_LENGTH = 3;

    /** 「其他」理由的正文上限，防止把整个评论区粘进去。 */
    public static final int OTHER_CONTENT_MAX_LENGTH = 200;

    /**
     * 理由码 → 文案。顺序与 {@code bilibili-API/docs/comment/action.md}「举报类型」表一致，
     * 客户端文案也逐条对得上。**下标不是码值**，取值一律走 {@link #commentReasonCodes()}，
     * 别拿数组下标当 {@code reason} 传。
     */
    private static final int[] COMMENT_REASON_CODES = {0, 1, 2, 3, 4, 5, 6, 7, 8};

    private static final String[] COMMENT_REASON_LABELS = {
            "其他", "垃圾广告", "色情", "刷屏", "引战", "剧透", "政治", "人身攻击", "内容不相关"
    };

    /** 供 UI 构造选择列表用；返回副本，调用方改不到内部状态。 */
    public static int[] commentReasonCodes() {
        return COMMENT_REASON_CODES.clone();
    }

    public static String[] commentReasonLabels() {
        return COMMENT_REASON_LABELS.clone();
    }

    /**
     * 码值 → 文案。未知码返回 {@link ApiErrorText#opFailed} 风格的兜底串，
     * 而不是空串——弹窗里出现一行空白比出现「未知理由(9)」更难排查。
     */
    public static String commentReasonLabel(int code) {
        for (int i = 0; i < COMMENT_REASON_CODES.length; i++) {
            if (COMMENT_REASON_CODES[i] == code) return COMMENT_REASON_LABELS[i];
        }
        return "未知理由（" + code + "）";
    }

    /** 只有「其他」需要填正文。 */
    public static boolean needsContent(int reasonCode) {
        return reasonCode == REASON_OTHER;
    }

    /**
     * 正文够不够。「其他」必须 ≥ {@link #OTHER_CONTENT_MIN_LENGTH} 个字符（按 trim 后算，
     * 与客户端「不能少于3个字」一致）；其它理由不看正文，一律放行。
     */
    public static boolean isContentAcceptable(int reasonCode, String content) {
        if (!needsContent(reasonCode)) return true;
        String text = content == null ? "" : content.trim();
        return text.length() >= OTHER_CONTENT_MIN_LENGTH
                && text.length() <= OTHER_CONTENT_MAX_LENGTH;
    }

    /**
     * 组装请求体（**不含 csrf**，csrf 由 {@link #reportReply} 补）。
     *
     * <p>抽成纯函数是为了让「参数名/码值/正文只在 reason=0 时带上」这几件事能被 JVM 单测钉死
     * ——这类拼串改错了不会崩，只会静默把举报提交到错误的分类。
     */
    public static NetWorkUtil.FormData buildReportForm(long oid, int type, long rpid,
                                                      int reasonCode, String content) {
        NetWorkUtil.FormData form = new NetWorkUtil.FormData()
                .put("type", type)
                .put("oid", oid)
                .put("rpid", rpid)
                .put("reason", reasonCode);
        // 文档：content 仅在 reason=0 时有效。其它理由也带上只会让服务端多一个无用字段，
        // 且「其他」之外的理由不该被正文左右，所以干脆不带。
        if (needsContent(reasonCode)) {
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
     * @param reasonCode 理由码，取值见 {@link #commentReasonCodes()}
     * @param content    「其他」理由的正文；其它理由忽略
     * @return 服务端 code，0 表示成功；文案见 {@link #errorMsg(int)}
     */
    public static int reportReply(long oid, int type, long rpid, int reasonCode, String content)
            throws IOException, JSONException {
        String url = "https://api.bilibili.com/x/v2/reply/report";
        String reqBody = buildReportForm(oid, type, rpid, reasonCode, content)
                .put("csrf", NetWorkUtil.currentCsrf())
                .toString();
        JSONObject result = new JSONObject(Objects.requireNonNull(
                NetWorkUtil.post(url, reqBody, NetWorkUtil.webHeaders).body()).string());
        return result.getInt("code");
    }

    /**
     * 返回码 → 人话。空串表示成功。
     *
     * <p>只列举报链路上真会遇到的码：{@code -352} 是风控（匿名/短时间连点最常见），
     * {@code 12026} 是客户端文案 {@code CD时间未到不能举报} 对应的冷却拒绝。
     */
    public static String errorMsg(int code) {
        switch (code) {
            case 0:
                return "";
            case -101:
                return ApiErrorText.loginRequired();
            case -111:
                return ApiErrorText.tokenExpired();
            case -352:
            case -412:
                return ApiErrorText.riskRejected();
            case -400:
                return ApiErrorText.requestIllegal();
            case -404:
                return "评论不存在";
            case -509:
                return "操作过于频繁，请稍后再试";
            case 12002:
                return "评论区已关闭";
            case 12006:
                return "没有这条评论";
            case 12026:
                return "举报太频繁了，请稍后再试";
            default:
                return ApiErrorText.opFailed(code);
        }
    }
}

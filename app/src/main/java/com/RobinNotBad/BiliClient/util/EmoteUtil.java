package com.RobinNotBad.BiliClient.util;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.style.ImageSpan;

import com.RobinNotBad.BiliClient.BiliTerminal;
import com.RobinNotBad.BiliClient.model.Emote;
import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestManager;
import com.bumptech.glide.request.FutureTarget;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;


//表情包工具，用于将文本中的表情包替换为对应图片，部分代码来自catGPT
//2023-07-23

public class EmoteUtil {
    public static SpannableStringBuilder textReplaceEmote(String text, JSONArray emote, float scale, Context context) throws JSONException, ExecutionException, InterruptedException {
        SpannableStringBuilder result = new SpannableStringBuilder(text);
        if (emote != null && emote.length() > 0) {
            for (int i = 0; i < emote.length(); i++) {    //遍历每一个表情包
                JSONObject key = emote.getJSONObject(i);

                String name = key.getString("name");
                String emoteUrl = key.getString("url");
                int size = key.getInt("size");  //B站十分贴心的帮你把表情包大小都写好了，快说谢谢蜀黍

                replaceSingle(result, name, emoteUrl, size, scale, context);
            }
        }
        return result;
    }

    /**
     * 26.10.27 性能：原实现逐个 {@code submit().get()}——一条评论里 5 个表情就是 5 次**串行**的
     * 阻塞请求，而 {@code Reply} 构造时就调本方法，一页 20 条评论 + 楼中楼的表情全部串起来等，
     * 手表弱网下评论区首屏要转很久才出来。现在先把本条文本里**真正出现**的表情一次性全部提交给 Glide
     * （并发下载），再按同一个截止时间逐个取结果；超时的表情保留原文字（如「[doge]」），不再无限等。
     */
    public static SpannableStringBuilder textReplaceEmote(String text, ArrayList<Emote> emotes, float scale, Context context, CharSequence source) {
        SpannableStringBuilder result = (source instanceof SpannableStringBuilder)
                ? (SpannableStringBuilder) source
                : new SpannableStringBuilder(text);

        if (emotes == null || emotes.isEmpty() || context == null) return result;

        String origText = result.toString();
        ArrayList<Emote> present = new ArrayList<>();
        ArrayList<FutureTarget<Drawable>> futures = new ArrayList<>();
        for (Emote key : emotes) {
            // 先判文本里有没有再发请求：没出现的表情不该触发任何下载
            if (key == null || TextUtils.isEmpty(key.name) || TextUtils.isEmpty(key.url)) continue;
            if (!origText.contains(key.name)) continue;
            present.add(key);
            futures.add(Glide.with(context).asDrawable().load(key.url).submit());
        }

        long deadline = SystemClock.elapsedRealtime() + PARSE_TIMEOUT_MS;
        for (int i = 0; i < present.size(); i++) {
            Emote key = present.get(i);
            Drawable drawable = awaitDrawable(futures.get(i), deadline);
            if (drawable == null) continue;
            applySpans(result, origText, key.name, drawable, key.size, scale);
        }
        return result;
    }

    /** 单条文本解析时等表情的总时长上限（毫秒）。整页已预取时这里通常是内存缓存命中、瞬间返回。 */
    private static final long PARSE_TIMEOUT_MS = 5000;

    /** 整页预取的总时长上限（毫秒）。 */
    private static final long PREFETCH_TIMEOUT_MS = 10000;

    /**
     * 把一页内容里用到的全部表情**并发**预取进 Glide 缓存，随后逐条解析时就是缓存命中。
     *
     * <p>只在后台线程调用（会阻塞，最多 {@link #PREFETCH_TIMEOUT_MS}）。已完成的请求立即 clear，
     * 让位图从「活动资源」退回内存 LRU，交给随后的解析复用；超时未完成的**不** clear——clear 会取消下载，
     * 而随后解析同一张图时 Glide 会合并到这个仍在进行的请求上。
     */
    public static void prefetch(Collection<String> urls) {
        Context context = BiliTerminal.context;
        if (context == null || urls == null || urls.isEmpty()) return;
        try {
            RequestManager manager = Glide.with(context);
            ArrayList<FutureTarget<Drawable>> futures = new ArrayList<>(urls.size());
            for (String url : urls) {
                if (!TextUtils.isEmpty(url)) futures.add(manager.asDrawable().load(url).submit());
            }
            long begin = SystemClock.elapsedRealtime();
            long deadline = begin + PREFETCH_TIMEOUT_MS;
            int ok = 0;
            for (FutureTarget<Drawable> future : futures) {
                if (awaitDrawable(future, deadline) != null) {
                    manager.clear(future);
                    ok++;
                }
            }
            Logu.d("EmoteUtil", "表情预取 " + ok + "/" + futures.size() + " 张，用时 " + (SystemClock.elapsedRealtime() - begin) + "ms");
        } catch (Throwable e) {
            // 预取只是加速，失败了逐条解析时照常加载
            Logu.w("EmoteUtil", "表情预取失败: " + e);
        }
    }

    /** 在截止时间前取结果；超时/失败返回 null。截止时间已过也再非阻塞地试一次（已完成的仍能拿到）。 */
    private static Drawable awaitDrawable(FutureTarget<Drawable> future, long deadline) {
        try {
            long remain = Math.max(1, deadline - SystemClock.elapsedRealtime());
            return future.get(remain, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 给文本里所有出现的 name 挂上同一张图（ImageSpan 每处要新建，drawable 可共用，与旧实现一致）。 */
    private static void applySpans(SpannableStringBuilder spannableString, String origText, String name,
                                   Drawable drawable, int size, float scale) {
        int px = (int) (size * ToolsUtil.sp2px(18) * scale);  //参考了隔壁腕上哔哩并进行了改进
        drawable.setBounds(0, 0, px, px);
        int start = origText.indexOf(name);
        while (start >= 0) {
            int end = start + name.length();
            spannableString.setSpan(new ImageSpan(drawable, ImageSpan.ALIGN_BOTTOM), start, end, SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
            start = origText.indexOf(name, end);
        }
    }

    public static SpannableStringBuilder textReplaceEmote(String text, ArrayList<Emote> emotes, float scale, Context context) {
        return textReplaceEmote(text, emotes, scale, context, null);
    }

    public static void replaceSingle(SpannableStringBuilder spannableString, String name, String url, int size, float scale, Context context) {
        try {
            String origText = spannableString.toString();
            // 26.10.27 性能：先判文本里有没有这个表情再下载。私信页传进来的是整个会话的表情表，
            // 原实现对每条消息都把表里**每一张**图同步取一遍，绝大多数根本不在这条消息里。
            if (TextUtils.isEmpty(name) || !origText.contains(name)) return;
            Drawable drawable = awaitDrawable(Glide.with(context).asDrawable().load(url).submit(),
                    SystemClock.elapsedRealtime() + PARSE_TIMEOUT_MS);
            if (drawable == null) return;
            // ImageSpan 不能复用，每处新建（见 applySpans）
            applySpans(spannableString, origText, name, drawable, size, scale);
        } catch (Exception ignored) {
        }
    }

    public static void replaceSingle(SpannableStringBuilder spannableString, String url, int size, int start, int end, float scale) {
        try {
            Drawable drawable = awaitDrawable(Glide.with(BiliTerminal.context).asDrawable().load(url).submit(),
                    SystemClock.elapsedRealtime() + PARSE_TIMEOUT_MS);
            if (drawable == null) return;
            drawable.setBounds(0, 0, (int) (size * ToolsUtil.sp2px(18) * scale), (int) (size * ToolsUtil.sp2px(18) * scale));
            ImageSpan imageSpan = new ImageSpan(drawable, ImageSpan.ALIGN_BOTTOM);
            spannableString.setSpan(imageSpan, start, end, SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
        } catch (Exception ignored) {
        }
    }

}

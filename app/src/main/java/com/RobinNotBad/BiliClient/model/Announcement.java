package com.RobinNotBad.BiliClient.model;

public class Announcement {
    public int id;
    public String ctime;
    public String title;
    public String content;

    /**
     * 公告来源：{@code local} = 本项目自建服务端发布的公告；{@code relay} = 管理员在控制台
     * 逐条审核后、从上游（原版哔哩终端）透传下来的公告。旧版服务端不返回该字段，按
     * {@code local} 处理；正文页据此在顶部标注来源。
     */
    public String source = "local";

    public Announcement() {
    }

    /** 是否为上游透传公告。 */
    public boolean isRelayed() {
        return "relay".equals(source);
    }
}

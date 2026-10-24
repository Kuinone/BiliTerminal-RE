package com.RobinNotBad.BiliClient.model;

public class FavoriteFolder {
    public long id;
    public long mediaId;
    public String name;
    public String cover;
    public int videoCount;
    public int maxCount;
    public boolean isDefault;
    /**
     * 简介（备注）。列表接口（created/list）不下发该字段，只有
     * {@code x/v3/fav/folder/info} 会返回，编辑页用它预填。
     */
    public String intro;
    /**
     * 0=公开，1=私密。v3 接口没有独立的 privacy 字段，由 {@code attr} 的
     * bit0 解析（见 FavoriteApi#isPrivateFolder 与 bilibili-API/docs/fav/info.md:185-191）。
     */
    public int privacy;

    public FavoriteFolder() {
    }
}

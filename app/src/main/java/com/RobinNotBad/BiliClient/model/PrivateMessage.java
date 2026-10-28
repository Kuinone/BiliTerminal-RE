package com.RobinNotBad.BiliClient.model;

import org.json.JSONArray;
import org.json.JSONObject;

public class PrivateMessage {

    public JSONObject content = new JSONObject();

    public JSONArray content_array = new JSONArray();
    public int type = 0;
    public long timestamp = 0;
    public long uid = 0;
    public String name = "";
    public long msgId = 0;
    public long msgSeqno = 0;
    public int msg_source = 0;
    /** 0 正常；1 被撤回（接口仍会回原文）；2 被系统撤回；50 图片失效。见 private_msg.md 的 msg_status。 */
    public int msgStatus = 0;

    public static final int STATUS_NORMAL = 0;
    public static final int STATUS_RETRACTED = 1;

    public static final int TYPE_TEXT = 1;
    public static final int TYPE_VIDEO = 7;
    public static final int TYPE_PIC = 2;
    public static final int TYPE_RETRACT = 5;
    public static final int TYPE_FACE = 6;
    public static final int TYPE_NOMAL_CARD = 10;
    public static final int TYPE_PIC_CARD = 13;
    public static final int TYPE_TEXT_WITH_VIDEO = 16;
    public static final int TYPE_SYSTEM = 18;

    public PrivateMessage(long uid, int type, JSONObject content, long timestamp, String name, long msgId, long msgSeqno) {
        this.uid = uid;
        this.type = type;
        this.content = content;
        this.timestamp = timestamp;
        this.name = name;
        this.msgId = msgId;
        this.msgSeqno = msgSeqno;
    }

    public PrivateMessage() {
    }
}

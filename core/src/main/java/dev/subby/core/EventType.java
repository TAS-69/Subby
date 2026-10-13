package dev.subby.core;

import java.util.*;

public enum EventType {
    SUB("sub", "New subscription"), RESUB("resub", "Shared resubscription"), GIFT("gift", "Gift recipient"),
    GIFT_BATCH("gift_batch", "Gift batch"), BITS("bits", "Bits"), REWARD("reward", "Channel point reward"),
    RAID("raid", "Incoming raid"), FOLLOW("follow", "New follower"), HYPE_BEGIN("hype_begin", "Hype Train starts"),
    HYPE_PROGRESS("hype_progress", "Hype Train progresses"), HYPE_END("hype_end", "Hype Train ends"),
    POLL("poll", "Poll result"), PREDICTION("prediction", "Prediction result"), MODERATOR_ADD("moderator_add", "Moderator added"),
    MODERATOR_REMOVE("moderator_remove", "Moderator removed"), VIP_ADD("vip_add", "VIP added"), VIP_REMOVE("vip_remove", "VIP removed"),
    FIRST_CHAT("first_chat", "First observed chat"), BAN("ban", "Twitch user banned"), UNBAN("unban", "Twitch user unbanned");
    public final String id, label;
    EventType(String id, String label) { this.id = id; this.label = label; }
    public boolean subscription() { return this == SUB || this == RESUB || this == GIFT; }
    public static boolean valid(String id) { return Arrays.stream(values()).anyMatch(e -> e.id.equals(id)); }
}

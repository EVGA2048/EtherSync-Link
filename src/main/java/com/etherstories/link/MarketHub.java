package com.etherstories.link;

/** 一份已登记的市场服务（配置项 + 最近一次探测）。 */
public final class MarketHub {
    public final String id;
    public final String url;
    public final String token;
    public volatile String name;
    public volatile boolean online;
    public volatile String lastError = "";
    public volatile long lastOk;

    public MarketHub(String id, String name, String url, String token) {
        this.id = id;
        this.name = name == null || name.isBlank() ? id : name.trim();
        this.url = url == null ? "" : url.trim().replaceAll("/+$", "");
        this.token = token == null ? "" : token.trim();
    }

    public String displayName() {
        return name == null || name.isBlank() ? id : name;
    }
}

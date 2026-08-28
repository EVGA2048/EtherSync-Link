package com.etherstories.link;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/** 连一台 EtherLink 市场 HTTP 服务。 */
final class MarketClient {
    private final MarketHub hub;
    private final HttpClient http;
    private final long offlineMs;

    MarketClient(MarketHub hub, long offlineMs) {
        this.hub = hub;
        this.offlineMs = Math.max(5_000L, offlineMs);
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
    }

    MarketHub hub() { return hub; }

    JsonObject info() throws Exception {
        JsonObject o = get("/v1/info", false);
        if (o.has("name")) hub.name = o.get("name").getAsString();
        markOk();
        return o;
    }

    void heartbeat(String code, String name, String blurb, String color, String icon) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("code", code);
        body.addProperty("name", name);
        body.addProperty("blurb", blurb == null ? "" : blurb);
        body.addProperty("color", color);
        body.addProperty("icon", icon);
        JsonObject o = post("/v1/heartbeat", body);
        if (o.has("name")) hub.name = o.get("name").getAsString();
        markOk();
    }

    List<Models.ServerRow> servers() throws Exception {
        JsonObject o = get("/v1/servers", true);
        markOk();
        List<Models.ServerRow> out = new ArrayList<>();
        JsonArray arr = o.has("servers") ? o.getAsJsonArray("servers") : new JsonArray();
        long clock = System.currentTimeMillis();
        for (JsonElement e : arr) {
            JsonObject s = e.getAsJsonObject();
            out.add(new Models.ServerRow(
                    str(s, "code"),
                    str(s, "name"),
                    str(s, "short"),
                    str(s, "blurb"),
                    str(s, "color").isBlank() ? "LIGHT_BLUE" : str(s, "color"),
                    str(s, "icon").isBlank() ? "TERRACOTTA" : str(s, "icon"),
                    num(s, "heartbeat"),
                    s.has("clock") ? num(s, "clock") : clock));
        }
        return out;
    }

    List<Models.Listing> listings(String server, String query, UUID seller, int offset, int limit) throws Exception {
        StringBuilder q = new StringBuilder("/v1/listings?offset=").append(offset).append("&limit=").append(limit);
        if (server != null && !server.isBlank()) q.append("&server=").append(enc(server));
        if (query != null && !query.isBlank()) q.append("&q=").append(enc(query));
        if (seller != null) q.append("&seller=").append(enc(seller.toString()));
        JsonObject o = get(q.toString(), true);
        markOk();
        List<Models.Listing> out = new ArrayList<>();
        JsonArray arr = o.has("listings") ? o.getAsJsonArray("listings") : new JsonArray();
        for (JsonElement e : arr) out.add(readListing(e.getAsJsonObject()));
        return out;
    }

    Models.Listing listing(long id) throws Exception {
        try {
            JsonObject o = get("/v1/listings/" + id, true);
            markOk();
            return o.has("listing") ? readListing(o.getAsJsonObject("listing")) : null;
        } catch (HttpStatus e) {
            if (e.code == 404) return null;
            markFail(e.getMessage());
            throw e;
        }
    }

    Models.Listing insert(UUID seller, String sellerName, String server, String itemKey, String itemName,
                          int amount, double price, String b64, String nestedKeys) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("seller_uuid", seller.toString());
        body.addProperty("seller_name", sellerName);
        body.addProperty("server_code", server);
        body.addProperty("item_key", itemKey);
        body.addProperty("item_name", itemName);
        body.addProperty("amount", amount);
        body.addProperty("price", price);
        body.addProperty("blob_b64", b64 == null ? "" : b64);
        body.addProperty("nested_keys", nestedKeys == null ? "" : nestedKeys);
        JsonObject o = post("/v1/listings", body);
        markOk();
        return o.has("listing") ? readListing(o.getAsJsonObject("listing")) : null;
    }

    boolean delete(long id) throws Exception {
        try {
            JsonObject o = send("DELETE", "/v1/listings/" + id, null, true);
            markOk();
            return o.has("ok") && o.get("ok").getAsBoolean();
        } catch (HttpStatus e) {
            if (e.code == 404) return false;
            markFail(e.getMessage());
            throw e;
        }
    }

    Models.Listing claim(long id) throws Exception {
        try {
            JsonObject o = post("/v1/listings/" + id + "/claim", new JsonObject());
            markOk();
            return o.has("listing") ? readListing(o.getAsJsonObject("listing")) : null;
        } catch (HttpStatus e) {
            if (e.code == 409 || e.code == 404) return null;
            markFail(e.getMessage());
            throw e;
        }
    }

    void setPrice(long id, double price) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("price", price);
        send("PATCH", "/v1/listings/" + id, body, true);
        markOk();
    }

    int deleteOf(String server, UUID seller) throws Exception {
        JsonObject o = send("DELETE",
                "/v1/listings?server=" + enc(server) + "&seller=" + enc(seller.toString()),
                null, true);
        markOk();
        return o.has("deleted") ? o.get("deleted").getAsInt() : 0;
    }

    private void markOk() {
        hub.online = true;
        hub.lastOk = System.currentTimeMillis();
        hub.lastError = "";
    }

    void markFail(String err) {
        hub.online = false;
        hub.lastError = err == null ? "" : err;
    }

    boolean stale() {
        return hub.lastOk <= 0 || System.currentTimeMillis() - hub.lastOk > offlineMs;
    }

    private JsonObject get(String path, boolean auth) throws Exception {
        return send("GET", path, null, auth);
    }

    private JsonObject post(String path, JsonObject body) throws Exception {
        return send("POST", path, body, true);
    }

    private JsonObject send(String method, String path, JsonObject body, boolean auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(hub.url + path))
                .timeout(Duration.ofSeconds(5));
        if (auth) b.header("Authorization", "Bearer " + hub.token);
        b.header("Accept", "application/json");
        if (body != null) {
            b.header("Content-Type", "application/json; charset=utf-8");
            b.method(method, HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
        } else if ("GET".equals(method)) {
            b.GET();
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        String raw = resp.body() == null ? "{}" : resp.body();
        JsonObject o;
        try {
            o = JsonParser.parseString(raw).getAsJsonObject();
        } catch (Exception e) {
            throw new Exception("市场返回无法解析");
        }
        if (resp.statusCode() >= 400) {
            String err = o.has("error") ? o.get("error").getAsString() : ("HTTP " + resp.statusCode());
            markFail(err);
            throw new HttpStatus(resp.statusCode(), err);
        }
        return o;
    }

    private static Models.Listing readListing(JsonObject o) {
        String b64 = str(o, "blob_b64");
        byte[] blob = b64.isBlank() ? null : Base64.getDecoder().decode(b64);
        return new Models.Listing(
                num(o, "id"),
                UUID.fromString(str(o, "seller_uuid")),
                str(o, "seller_name"),
                str(o, "server_code"),
                str(o, "item_key"),
                str(o, "item_name"),
                (int) num(o, "amount"),
                o.has("price") && !o.get("price").isJsonNull() ? o.get("price").getAsDouble() : 0,
                num(o, "created"),
                blob,
                str(o, "nested_keys"));
    }

    private static String str(JsonObject o, String k) {
        if (!o.has(k) || o.get(k).isJsonNull()) return "";
        return o.get(k).getAsString();
    }

    private static long num(JsonObject o, String k) {
        if (!o.has(k) || o.get(k).isJsonNull()) return 0;
        try {
            return o.get(k).getAsLong();
        } catch (Exception e) {
            return (long) o.get(k).getAsDouble();
        }
    }

    private static String enc(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    static final class HttpStatus extends Exception {
        final int code;
        HttpStatus(int code, String msg) {
            super(msg);
            this.code = code;
        }
    }
}

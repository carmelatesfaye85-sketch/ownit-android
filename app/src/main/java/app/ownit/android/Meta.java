package app.ownit.android;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Looks up a shared video's title, creator and picture, so saved videos are easy to recognise. */
final class Meta {
    private static final String UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36";

    private Meta() {}

    /** Returns JSON: {url, title, author, thumb}. Missing details are left out. Never throws. */
    static String lookup(String shared) {
        JSONObject out = new JSONObject();
        try {
            String url = shared;
            String host = hostOf(url);
            // Short share links (vm.tiktok.com/...) point to the real video page: follow them first.
            if (host.equals("vm.tiktok.com") || host.equals("vt.tiktok.com") || host.endsWith("tiktok.com") && url.contains("/t/")) {
                String finalUrl = resolve(url);
                if (finalUrl != null && hostOf(finalUrl).endsWith("tiktok.com")) {
                    int q = finalUrl.indexOf('?');
                    url = q > 0 ? finalUrl.substring(0, q) : finalUrl;
                    out.put("url", url);
                    host = hostOf(url);
                }
            }
            String oembed = null;
            if (host.endsWith("tiktok.com")) oembed = "https://www.tiktok.com/oembed?url=" + URLEncoder.encode(url, "UTF-8");
            else if (host.endsWith("youtube.com") || host.equals("youtu.be")) oembed = "https://www.youtube.com/oembed?format=json&url=" + URLEncoder.encode(url, "UTF-8");
            if (oembed != null) {
                String body = get(oembed);
                if (body != null && body.trim().startsWith("{")) {
                    JSONObject j = new JSONObject(body);
                    put(out, "title", j.optString("title", ""));
                    put(out, "author", j.optString("author_name", ""));
                    put(out, "thumb", j.optString("thumbnail_url", ""));
                }
            }
            if (!out.has("title")) {
                String html = get(url);
                if (html != null) {
                    put(out, "title", og(html, "og:title"));
                    put(out, "thumb", og(html, "og:image"));
                }
            }
        } catch (Exception ignored) { }
        return out.toString();
    }

    private static void put(JSONObject o, String k, String v) throws Exception {
        if (v != null && !v.trim().isEmpty() && !o.has(k)) o.put(k, v.trim());
    }

    private static String hostOf(String url) {
        try {
            String h = new URL(url).getHost();
            return h == null ? "" : h.toLowerCase().replaceFirst("^www\\.", "");
        } catch (Exception e) {
            return "";
        }
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(6000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept-Language", "en");
        return c;
    }

    /** Follows redirects and returns the final address. */
    private static String resolve(String url) {
        HttpURLConnection c = null;
        try {
            c = open(url);
            c.getResponseCode();
            return c.getURL().toString();
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String get(String url) {
        HttpURLConnection c = null;
        try {
            c = open(url);
            if (c.getResponseCode() >= 400) return null;
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int n, total = 0;
                while ((n = in.read(chunk)) > 0 && total < 400_000) { buf.write(chunk, 0, n); total += n; }
                return buf.toString("UTF-8");
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String og(String html, String prop) {
        Matcher m = Pattern.compile("<meta[^>]+property=[\"']" + Pattern.quote(prop) + "[\"'][^>]*content=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(html);
        if (m.find()) return m.group(1).replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'");
        return "";
    }
}

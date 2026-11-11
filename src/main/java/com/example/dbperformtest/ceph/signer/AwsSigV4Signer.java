package com.example.dbperformtest.ceph.signer;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

public final class AwsSigV4Signer {

    private static final DateTimeFormatter DATE_TIME_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");
    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd");

    private final String accessKey;
    private final String secretKey;
    private final String region;
    private final String service;

    public AwsSigV4Signer(String accessKey, String secretKey, String region, String service) {
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.region    = region;
        this.service   = service;
    }

    /**
     * Signs a request and returns all headers needed to send it,
     * including Authorization, x-amz-date, x-amz-content-sha256, host,
     * plus any extra headers the caller passed in.
     */
    public Map<String, String> sign(String method,
                                    String host,
                                    String path,
                                    String queryString,
                                    Map<String, String> extraHeaders,
                                    byte[] body) {
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        String dateTime = now.format(DATE_TIME_FMT);
        String date     = now.format(DATE_FMT);

        byte[] payload     = body != null ? body : new byte[0];
        String payloadHash = sha256Hex(payload);

        // Collect headers to sign — TreeMap keeps them sorted case-insensitively
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        headers.put("host",                  host);
        headers.put("x-amz-date",            dateTime);
        headers.put("x-amz-content-sha256",  payloadHash);
        if (extraHeaders != null) {
            extraHeaders.forEach((k, v) -> headers.put(k.toLowerCase(), v));
        }

        // Build canonical headers and signed-headers list
        StringBuilder canonicalHeaders  = new StringBuilder();
        StringBuilder signedHeadersList = new StringBuilder();
        List<String> sortedKeys = new ArrayList<>(headers.keySet());
        Collections.sort(sortedKeys);
        for (String key : sortedKeys) {
            canonicalHeaders.append(key).append(':').append(headers.get(key).trim()).append('\n');
            if (signedHeadersList.length() > 0) signedHeadersList.append(';');
            signedHeadersList.append(key);
        }
        String signedHeaders = signedHeadersList.toString();

        String canonicalRequest =
                method + '\n' +
                encodePath(path) + '\n' +
                canonicalQueryString(queryString) + '\n' +
                canonicalHeaders + '\n' +
                signedHeaders + '\n' +
                payloadHash;

        String credentialScope = date + '/' + region + '/' + service + "/aws4_request";
        String stringToSign    =
                "AWS4-HMAC-SHA256\n" +
                dateTime + '\n' +
                credentialScope + '\n' +
                sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));

        byte[] kDate    = hmac(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), date);
        byte[] kRegion  = hmac(kDate,    region);
        byte[] kService = hmac(kRegion,  service);
        byte[] kSigning = hmac(kService, "aws4_request");
        String signature = hex(hmac(kSigning, stringToSign));

        String authorization =
                "AWS4-HMAC-SHA256 Credential=" + accessKey + '/' + credentialScope +
                ", SignedHeaders=" + signedHeaders +
                ", Signature=" + signature;

        Map<String, String> result = new LinkedHashMap<>(headers);
        result.put("Authorization", authorization);
        return result;
    }

    // Sig V4 §3.3: sort params by encoded key then value; params with no value → key=
    private static String canonicalQueryString(String queryString) {
        if (queryString == null || queryString.isEmpty()) return "";
        Map<String, List<String>> params = new TreeMap<>();
        for (String pair : queryString.split("&")) {
            int eq = pair.indexOf('=');
            String k = uriEncode(eq >= 0 ? pair.substring(0, eq) : pair);
            String v = eq >= 0 ? uriEncode(pair.substring(eq + 1)) : "";
            params.computeIfAbsent(k, x -> new ArrayList<>()).add(v);
        }
        StringBuilder sb = new StringBuilder();
        params.forEach((k, vals) -> {
            Collections.sort(vals);
            for (String v : vals) {
                if (sb.length() > 0) sb.append('&');
                sb.append(k).append('=').append(v);
            }
        });
        return sb.toString();
    }

    private static String uriEncode(String value) {
        if (value == null || value.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : value.toCharArray()) {
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~') {
                sb.append(c);
            } else {
                byte[] bytes = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                for (byte b : bytes) sb.append(String.format("%%%02X", b));
            }
        }
        return sb.toString();
    }

    // S3 path-style: encode everything except /
    private static String encodePath(String path) {
        if (path == null || path.isEmpty()) return "/";
        StringBuilder sb = new StringBuilder();
        for (char c : path.toCharArray()) {
            if (c == '/') {
                sb.append('/');
            } else if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~') {
                sb.append(c);
            } else {
                byte[] bytes = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                for (byte b : bytes) sb.append(String.format("%%%02X", b));
            }
        }
        return sb.toString();
    }

    private static String sha256Hex(byte[] data) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}

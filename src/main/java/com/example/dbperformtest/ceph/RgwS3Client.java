package com.example.dbperformtest.ceph;

import com.example.dbperformtest.ceph.parser.RgwXmlParser;
import com.example.dbperformtest.ceph.parser.RgwXmlParser.MultipartUploadInfo;
import com.example.dbperformtest.ceph.parser.RgwXmlParser.ObjectInfo;
import com.example.dbperformtest.ceph.parser.RgwXmlParser.PartInfo;
import com.example.dbperformtest.ceph.signer.AwsSigV4Signer;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * S3-compatible client for Ceph RGW.
 * Uses only JDK 17 built-ins: java.net.http.HttpClient + AWS Signature V4.
 * No external dependencies.
 */
public class RgwS3Client implements AutoCloseable {

    private final String         endpoint;
    private final HttpClient     http;
    private final AwsSigV4Signer signer;

    public RgwS3Client(String endpoint, String accessKey, String secretKey) {
        this.endpoint = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        this.http     = HttpClient.newHttpClient();
        this.signer   = new AwsSigV4Signer(accessKey, secretKey, "us-east-1", "s3");
    }

    // ── Bucket operations ─────────────────────────────────────────────────────

    public List<String> listBuckets() {
        HttpResponse<byte[]> resp = send("GET", "/", null, null, null);
        checkStatus(resp, 200);
        return RgwXmlParser.parseBucketNames(resp.body());
    }

    public void createBucket(String bucket) {
        HttpResponse<byte[]> resp = send("PUT", "/" + bucket, null, null, null);
        // RGW returns 200 on bucket create
        if (resp.statusCode() != 200 && resp.statusCode() != 204) {
            throw new RgwException(resp.statusCode(), "createBucket(" + bucket + ")");
        }
    }

    /** Returns true if bucket exists, false if 404. */
    public boolean headBucket(String bucket) {
        HttpResponse<byte[]> resp = send("HEAD", "/" + bucket, null, null, null);
        if (resp.statusCode() == 200) return true;
        if (resp.statusCode() == 404) return false;
        throw new RgwException(resp.statusCode(), "headBucket(" + bucket + ")");
    }

    public void deleteBucket(String bucket) {
        HttpResponse<byte[]> resp = send("DELETE", "/" + bucket, null, null, null);
        if (resp.statusCode() != 200 && resp.statusCode() != 204) {
            throw new RgwException(resp.statusCode(), "deleteBucket(" + bucket + ")");
        }
    }

    // ── Object operations ─────────────────────────────────────────────────────

    public List<ObjectInfo> listObjects(String bucket) {
        return listObjects(bucket, null, 1000);
    }

    public List<ObjectInfo> listObjects(String bucket, String prefix, int maxKeys) {
        StringBuilder query = new StringBuilder("list-type=2&max-keys=").append(maxKeys);
        if (prefix != null && !prefix.isEmpty()) query.append("&prefix=").append(prefix);
        HttpResponse<byte[]> resp = send("GET", "/" + bucket, query.toString(), null, null);
        checkStatus(resp, 200);
        return RgwXmlParser.parseObjectList(resp.body());
    }

    public void putObject(String bucket, String key, byte[] data, String contentType) {
        Map<String, String> extra = new LinkedHashMap<>();
        if (contentType != null) extra.put("content-type", contentType);
        HttpResponse<byte[]> resp = send("PUT", "/" + bucket + "/" + key, null, extra, data);
        checkStatus(resp, 200);
    }

    /** Caller is responsible for closing the returned stream. */
    public InputStream getObject(String bucket, String key) {
        try {
            String path = "/" + bucket + "/" + key;
            URI    uri  = URI.create(endpoint + path);
            String host = hostHeader(uri);
            Map<String, String> headers = signer.sign("GET", host, path, null, null, null);
            HttpRequest.Builder req = HttpRequest.newBuilder(uri).GET();
            headers.forEach((k, v) -> { if (!k.equalsIgnoreCase("host")) req.header(k, v); });
            HttpResponse<InputStream> resp =
                    http.send(req.build(), HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() != 200)
                throw new RgwException(resp.statusCode(), "getObject(" + bucket + "/" + key + ")");
            return resp.body();
        } catch (RgwException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public ObjectMetadata headObject(String bucket, String key) {
        HttpResponse<byte[]> resp = send("HEAD", "/" + bucket + "/" + key, null, null, null);
        checkStatus(resp, 200);
        long   contentLength = resp.headers().firstValueAsLong("content-length").orElse(-1L);
        String contentType   = resp.headers().firstValue("content-type").orElse(null);
        String etag          = resp.headers().firstValue("etag")
                                   .map(e -> e.replace("\"", "")).orElse(null);
        return new ObjectMetadata(contentLength, contentType, etag);
    }

    public void deleteObject(String bucket, String key) {
        HttpResponse<byte[]> resp = send("DELETE", "/" + bucket + "/" + key, null, null, null);
        if (resp.statusCode() != 200 && resp.statusCode() != 204) {
            throw new RgwException(resp.statusCode(), "deleteObject(" + bucket + "/" + key + ")");
        }
    }

    // ── Multipart upload ──────────────────────────────────────────────────────

    public String createMultipartUpload(String bucket, String key, String contentType) {
        Map<String, String> extra = new LinkedHashMap<>();
        if (contentType != null) extra.put("content-type", contentType);
        HttpResponse<byte[]> resp = send("POST", "/" + bucket + "/" + key, "uploads", extra, null);
        checkStatus(resp, 200);
        return RgwXmlParser.parseUploadId(resp.body());
    }

    /** Returns the ETag of the uploaded part (without quotes). */
    public String uploadPart(String bucket, String key, String uploadId, int partNumber, byte[] data) {
        String query = "partNumber=" + partNumber + "&uploadId=" + uploadId;
        HttpResponse<byte[]> resp = send("PUT", "/" + bucket + "/" + key, query, null, data);
        checkStatus(resp, 200);
        return resp.headers().firstValue("etag")
                   .map(e -> e.replace("\"", ""))
                   .orElseThrow(() -> new RgwException(200, "uploadPart: no ETag in response"));
    }

    public List<PartInfo> listParts(String bucket, String key, String uploadId) {
        HttpResponse<byte[]> resp = send("GET", "/" + bucket + "/" + key, "uploadId=" + uploadId, null, null);
        checkStatus(resp, 200);
        return RgwXmlParser.parsePartsList(resp.body());
    }

    public void completeMultipartUpload(String bucket, String key, String uploadId,
                                        List<CompletedPart> parts) {
        StringBuilder xml = new StringBuilder("<CompleteMultipartUpload>");
        for (CompletedPart p : parts) {
            xml.append("<Part>")
               .append("<PartNumber>").append(p.partNumber()).append("</PartNumber>")
               .append("<ETag>").append(p.etag()).append("</ETag>")
               .append("</Part>");
        }
        xml.append("</CompleteMultipartUpload>");
        byte[]              body  = xml.toString().getBytes(StandardCharsets.UTF_8);
        Map<String, String> extra = Map.of("content-type", "application/xml");
        HttpResponse<byte[]> resp = send("POST", "/" + bucket + "/" + key,
                                         "uploadId=" + uploadId, extra, body);
        checkStatus(resp, 200);
    }

    public void abortMultipartUpload(String bucket, String key, String uploadId) {
        HttpResponse<byte[]> resp = send("DELETE", "/" + bucket + "/" + key,
                                         "uploadId=" + uploadId, null, null);
        if (resp.statusCode() != 200 && resp.statusCode() != 204) {
            throw new RgwException(resp.statusCode(), "abortMultipartUpload");
        }
    }

    public List<MultipartUploadInfo> listMultipartUploads(String bucket) {
        HttpResponse<byte[]> resp = send("GET", "/" + bucket, "uploads", null, null);
        checkStatus(resp, 200);
        return RgwXmlParser.parseMultipartUploads(resp.body());
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private HttpResponse<byte[]> send(String method, String path, String query,
                                      Map<String, String> extraHeaders, byte[] body) {
        try {
            String url = endpoint + path + (query != null && !query.isEmpty() ? "?" + query : "");
            URI    uri = URI.create(url);
            String host = hostHeader(uri);
            byte[] payload = body != null ? body : new byte[0];

            Map<String, String> headers = signer.sign(method, host, path, query, extraHeaders, payload);

            HttpRequest.BodyPublisher publisher = body != null
                    ? HttpRequest.BodyPublishers.ofByteArray(body)
                    : HttpRequest.BodyPublishers.noBody();

            HttpRequest.Builder req = HttpRequest.newBuilder(uri).method(method, publisher);
            // HttpClient manages "host" itself — setting it throws IllegalArgumentException
            headers.forEach((k, v) -> { if (!k.equalsIgnoreCase("host")) req.header(k, v); });

            return http.send(req.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (RgwException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String hostHeader(URI uri) {
        int    port   = uri.getPort();
        String scheme = uri.getScheme();
        boolean isDefault = (port < 0)
                || ("http".equals(scheme)  && port == 80)
                || ("https".equals(scheme) && port == 443);
        return isDefault ? uri.getHost() : uri.getHost() + ":" + port;
    }

    private static void checkStatus(HttpResponse<?> resp, int expected) {
        if (resp.statusCode() != expected) {
            throw new RgwException(resp.statusCode(),
                    "Expected HTTP " + expected + ", got " + resp.statusCode());
        }
    }

    @Override
    public void close() {}

    // ── Value types ───────────────────────────────────────────────────────────

    public record ObjectMetadata(long contentLength, String contentType, String etag) {}

    public record CompletedPart(int partNumber, String etag) {}

    public static final class RgwException extends RuntimeException {
        private final int statusCode;

        public RgwException(int statusCode, String message) {
            super(message + " [HTTP " + statusCode + "]");
            this.statusCode = statusCode;
        }

        public int statusCode() { return statusCode; }
    }
}

package com.example.dbperformtest.ceph;

import com.example.dbperformtest.ceph.args.*;
import com.example.dbperformtest.ceph.messages.*;
import com.example.dbperformtest.ceph.parser.RgwXmlParser;
import com.example.dbperformtest.ceph.parser.RgwXmlParser.ListPage;
import com.example.dbperformtest.ceph.signer.AwsSigV4Signer;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * S3-compatible client for Ceph RadosGW modelled after the MinIO Java SDK.
 * Uses only JDK 17 built-ins — no external dependencies.
 *
 * <pre>{@code
 * RgwClient client = RgwClient.builder()
 *     .endpoint("http://localhost:80")
 *     .credentials("access-key", "secret-key")
 *     .build();
 * }</pre>
 */
public final class RgwClient implements AutoCloseable {

    private final String         endpoint;
    private final HttpClient     http;
    private final AwsSigV4Signer signer;

    private RgwClient(Builder b) {
        this.endpoint = b.endpoint.endsWith("/") ? b.endpoint.substring(0, b.endpoint.length() - 1) : b.endpoint;
        this.http     = HttpClient.newHttpClient();
        this.signer   = new AwsSigV4Signer(b.accessKey, b.secretKey, "us-east-1", "s3");
    }

    // ── Builder ───────────────────────────────────────────────────────────────

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String endpoint;
        private String accessKey;
        private String secretKey;

        public Builder endpoint(String v)                          { this.endpoint   = v; return this; }
        public Builder credentials(String accessKey, String secretKey) {
            this.accessKey = accessKey;
            this.secretKey = secretKey;
            return this;
        }

        public RgwClient build() {
            Objects.requireNonNull(endpoint,  "endpoint");
            Objects.requireNonNull(accessKey, "accessKey");
            Objects.requireNonNull(secretKey, "secretKey");
            return new RgwClient(this);
        }
    }

    // ── Bucket operations ─────────────────────────────────────────────────────

    public List<BucketItem> listBuckets() throws Exception {
        HttpResponse<byte[]> resp = send("GET", "/", null, null, null);
        checkStatus(resp, 200);
        return RgwXmlParser.parseBuckets(resp.body());
    }

    public void makeBucket(MakeBucketArgs args) throws Exception {
        HttpResponse<byte[]> resp = send("PUT", "/" + args.bucket(), null, null, null);
        if (resp.statusCode() != 200 && resp.statusCode() != 204)
            throw new RgwException(resp.statusCode(), "makeBucket(" + args.bucket() + ")");
    }

    public boolean bucketExists(BucketExistsArgs args) throws Exception {
        HttpResponse<byte[]> resp = send("HEAD", "/" + args.bucket(), null, null, null);
        if (resp.statusCode() == 200) return true;
        if (resp.statusCode() == 404) return false;
        throw new RgwException(resp.statusCode(), "bucketExists(" + args.bucket() + ")");
    }

    public void removeBucket(RemoveBucketArgs args) throws Exception {
        HttpResponse<byte[]> resp = send("DELETE", "/" + args.bucket(), null, null, null);
        if (resp.statusCode() != 200 && resp.statusCode() != 204)
            throw new RgwException(resp.statusCode(), "removeBucket(" + args.bucket() + ")");
    }

    // ── Object operations ─────────────────────────────────────────────────────

    /**
     * Returns a lazy {@link Iterable} that pages through objects on demand.
     * Non-recursive listing uses delimiter "/" to emulate directories.
     */
    public Iterable<Result<Item>> listObjects(ListObjectsArgs args) {
        return () -> new Iterator<>() {
            private final Queue<Result<Item>> buffer = new ArrayDeque<>();
            private String  continuationToken = null;
            private boolean done              = false;

            @Override
            public boolean hasNext() {
                if (!buffer.isEmpty()) return true;
                if (done) return false;
                fetchPage();
                return !buffer.isEmpty();
            }

            @Override
            public Result<Item> next() {
                if (!hasNext()) throw new NoSuchElementException();
                return buffer.poll();
            }

            private void fetchPage() {
                try {
                    StringBuilder query = new StringBuilder("list-type=2&max-keys=").append(args.maxKeys());
                    if (args.prefix()    != null) query.append("&prefix=").append(args.prefix());
                    if (!args.recursive())        query.append("&delimiter=/");
                    if (continuationToken != null) query.append("&continuation-token=").append(continuationToken);

                    HttpResponse<byte[]> resp = send("GET", "/" + args.bucket(), query.toString(), null, null);
                    checkStatus(resp, 200);
                    ListPage page = RgwXmlParser.parseListPage(resp.body());
                    page.items().forEach(item -> buffer.add(new Result<>(item)));
                    continuationToken = page.nextContinuationToken();
                    done = !page.isTruncated();
                } catch (Exception e) {
                    buffer.add(new Result<>(e));
                    done = true;
                }
            }
        };
    }

    /**
     * Uploads an object. Automatically uses multipart when
     * {@code partSize >= MIN_PART_SIZE} and data exceeds {@code partSize}.
     */
    public ObjectWriteResponse putObject(PutObjectArgs args) throws Exception {
        byte[] data = args.objectSize() > 0
                ? args.stream().readNBytes((int) args.objectSize())
                : args.stream().readAllBytes();

        boolean useMultipart = args.partSize() >= PutObjectArgs.MIN_PART_SIZE
                && data.length > args.partSize();

        if (useMultipart) {
            return putMultipart(args.bucket(), args.object(), data,
                                args.contentType(), (int) args.partSize());
        }

        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("content-type", args.contentType());
        HttpResponse<byte[]> resp = send("PUT", "/" + args.bucket() + "/" + args.object(),
                                         null, extra, data);
        checkStatus(resp, 200);
        String etag = resp.headers().firstValue("etag")
                          .map(e -> e.replace("\"", "")).orElse(null);
        return new ObjectWriteResponse(args.bucket(), args.object(), etag);
    }

    /** Caller is responsible for closing the returned stream. */
    public GetObjectResponse getObject(GetObjectArgs args) throws Exception {
        String path = "/" + args.bucket() + "/" + args.object();
        URI    uri  = URI.create(endpoint + path);
        String host = hostHeader(uri);
        Map<String, String> headers = signer.sign("GET", host, path, null, null, null);

        HttpRequest.Builder req = HttpRequest.newBuilder(uri).GET();
        headers.forEach((k, v) -> { if (!k.equalsIgnoreCase("host")) req.header(k, v); });

        HttpResponse<InputStream> resp = http.send(req.build(), HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() != 200)
            throw new RgwException(resp.statusCode(), "getObject(" + args.bucket() + "/" + args.object() + ")");

        long   contentLength = resp.headers().firstValueAsLong("content-length").orElse(-1L);
        String contentType   = resp.headers().firstValue("content-type").orElse(null);
        return new GetObjectResponse(resp.body(), args.bucket(), args.object(), contentType, contentLength);
    }

    public StatObjectResponse statObject(StatObjectArgs args) throws Exception {
        HttpResponse<byte[]> resp = send("HEAD", "/" + args.bucket() + "/" + args.object(), null, null, null);
        checkStatus(resp, 200);
        long   size         = resp.headers().firstValueAsLong("content-length").orElse(-1L);
        String contentType  = resp.headers().firstValue("content-type").orElse(null);
        String etag         = resp.headers().firstValue("etag").map(e -> e.replace("\"", "")).orElse(null);
        String lastModified = resp.headers().firstValue("last-modified").orElse(null);
        return new StatObjectResponse(args.bucket(), args.object(), size, contentType, etag,
                lastModified != null ? java.time.ZonedDateTime.parse(
                        lastModified, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME) : null);
    }

    public void removeObject(RemoveObjectArgs args) throws Exception {
        HttpResponse<byte[]> resp = send("DELETE", "/" + args.bucket() + "/" + args.object(), null, null, null);
        if (resp.statusCode() != 200 && resp.statusCode() != 204)
            throw new RgwException(resp.statusCode(), "removeObject(" + args.bucket() + "/" + args.object() + ")");
    }

    // ── Multipart (internal) ──────────────────────────────────────────────────

    private ObjectWriteResponse putMultipart(String bucket, String key, byte[] data,
                                              String contentType, int partSize) throws Exception {
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("content-type", contentType);
        HttpResponse<byte[]> init = send("POST", "/" + bucket + "/" + key, "uploads", extra, null);
        checkStatus(init, 200);
        String uploadId = RgwXmlParser.parseUploadId(init.body());

        List<CompletedPart> completed = new ArrayList<>();
        int offset = 0, partNum = 1;
        while (offset < data.length) {
            int end  = (int) Math.min((long) offset + partSize, data.length);
            byte[] chunk = Arrays.copyOfRange(data, offset, end);
            String query = "partNumber=" + partNum + "&uploadId=" + uploadId;
            HttpResponse<byte[]> up = send("PUT", "/" + bucket + "/" + key, query, null, chunk);
            checkStatus(up, 200);
            String etag = up.headers().firstValue("etag")
                            .map(e -> e.replace("\"", ""))
                            .orElseThrow(() -> new RgwException(200, "uploadPart: no ETag"));
            completed.add(new CompletedPart(partNum, etag));
            offset = end;
            partNum++;
        }

        StringBuilder xml = new StringBuilder("<CompleteMultipartUpload>");
        for (CompletedPart p : completed)
            xml.append("<Part><PartNumber>").append(p.partNumber())
               .append("</PartNumber><ETag>").append(p.etag()).append("</ETag></Part>");
        xml.append("</CompleteMultipartUpload>");

        byte[] body = xml.toString().getBytes(StandardCharsets.UTF_8);
        HttpResponse<byte[]> complete = send("POST", "/" + bucket + "/" + key,
                "uploadId=" + uploadId, Map.of("content-type", "application/xml"), body);
        checkStatus(complete, 200);
        return new ObjectWriteResponse(bucket, key, null);
    }

    // ── Internal HTTP ─────────────────────────────────────────────────────────

    private HttpResponse<byte[]> send(String method, String path, String query,
                                      Map<String, String> extraHeaders, byte[] body) throws Exception {
        String url  = endpoint + path + (query != null && !query.isEmpty() ? "?" + query : "");
        URI    uri  = URI.create(url);
        String host = hostHeader(uri);
        byte[] payload = body != null ? body : new byte[0];

        Map<String, String> headers = signer.sign(method, host, path, query, extraHeaders, payload);

        HttpRequest.BodyPublisher publisher = body != null
                ? HttpRequest.BodyPublishers.ofByteArray(body)
                : HttpRequest.BodyPublishers.noBody();

        HttpRequest.Builder req = HttpRequest.newBuilder(uri).method(method, publisher);
        headers.forEach((k, v) -> { if (!k.equalsIgnoreCase("host")) req.header(k, v); });

        return http.send(req.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private static String hostHeader(URI uri) {
        int    port   = uri.getPort();
        String scheme = uri.getScheme();
        boolean isDefault = port < 0
                || ("http".equals(scheme)  && port == 80)
                || ("https".equals(scheme) && port == 443);
        return isDefault ? uri.getHost() : uri.getHost() + ":" + port;
    }

    private static void checkStatus(HttpResponse<?> resp, int expected) {
        if (resp.statusCode() != expected)
            throw new RgwException(resp.statusCode(), "Expected HTTP " + expected + ", got " + resp.statusCode());
    }

    @Override public void close() {}

    // ── Value types ───────────────────────────────────────────────────────────

    private record CompletedPart(int partNumber, String etag) {}

    public static final class RgwException extends RuntimeException {
        private final int statusCode;
        public RgwException(int statusCode, String message) {
            super(message + " [HTTP " + statusCode + "]");
            this.statusCode = statusCode;
        }
        public int statusCode() { return statusCode; }
    }
}

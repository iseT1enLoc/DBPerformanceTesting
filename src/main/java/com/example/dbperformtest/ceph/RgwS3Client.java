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
 * S3-compatible HTTP client for Ceph RadosGW (RGW).
 *
 * <p>All requests are signed with AWS Signature Version 4 via {@link AwsSigV4Signer}.
 * The client uses only JDK 17 built-ins ({@code java.net.http.HttpClient}) — no
 * third-party HTTP or AWS libraries are required.
 *
 * <p>Implements {@link AutoCloseable} so it can be used in try-with-resources, though
 * the underlying {@code HttpClient} requires no explicit teardown in JDK 17.
 */
public class RgwS3Client implements AutoCloseable {

    /** Base URL of the RGW endpoint, trailing slash stripped for safe concatenation. */
    private final String         endpoint;

    /** Shared JDK HttpClient — thread-safe and reused across all requests. */
    private final HttpClient     http;

    /** Stateless signer that computes AWS SigV4 Authorization headers per request. */
    private final AwsSigV4Signer signer;

    /**
     * Creates a client targeting the given RGW endpoint.
     *
     * @param endpoint   full URL of the RGW gateway, e.g. {@code http://ceph-rgw:7480}
     * @param accessKey  S3 access key ID
     * @param secretKey  S3 secret access key
     */
    public RgwS3Client(String endpoint, String accessKey, String secretKey) {
        // Normalize endpoint: strip trailing slash so path concatenation is always "endpoint + /path"
        this.endpoint = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        this.http     = HttpClient.newHttpClient();
        // RGW treats every bucket as if it were in "us-east-1", regardless of actual region
        this.signer   = new AwsSigV4Signer(accessKey, secretKey, "us-east-1", "s3");
    }

    // ── Bucket operations ─────────────────────────────────────────────────────

    /**
     * Lists all buckets owned by the authenticated user.
     * Sends {@code GET /} and parses the XML {@code <ListAllMyBucketsResult>} response.
     */
    public List<String> listBuckets() {
        HttpResponse<byte[]> resp = send("GET", "/", null, null, null);
        checkStatus(resp, 200);
        return RgwXmlParser.parseBucketNames(resp.body());
    }

    /**
     * Creates a new bucket.
     * RGW returns 200 (not 201) on success, so both 200 and 204 are accepted.
     */
    public void createBucket(String bucket) {
        HttpResponse<byte[]> resp = send("PUT", "/" + bucket, null, null, null);
        // RGW returns 200 on bucket create
        if (resp.statusCode() != 200 && resp.statusCode() != 204) {
            throw new RgwException(resp.statusCode(), "createBucket(" + bucket + ")");
        }
    }

    /**
     * Checks whether a bucket exists without fetching its contents (HEAD request).
     *
     * @return {@code true} if the bucket exists and is accessible; {@code false} if 404
     * @throws RgwException for any status other than 200 or 404
     */
    public boolean headBucket(String bucket) {
        HttpResponse<byte[]> resp = send("HEAD", "/" + bucket, null, null, null);
        if (resp.statusCode() == 200) return true;
        if (resp.statusCode() == 404) return false;
        throw new RgwException(resp.statusCode(), "headBucket(" + bucket + ")");
    }

    /**
     * Deletes an empty bucket. The bucket must have no objects; RGW returns 409 otherwise.
     */
    public void deleteBucket(String bucket) {
        HttpResponse<byte[]> resp = send("DELETE", "/" + bucket, null, null, null);
        if (resp.statusCode() != 200 && resp.statusCode() != 204) {
            throw new RgwException(resp.statusCode(), "deleteBucket(" + bucket + ")");
        }
    }

    // ── Object operations ─────────────────────────────────────────────────────

    /**
     * Lists up to 1 000 objects in a bucket (no prefix filter).
     * Convenience overload of {@link #listObjects(String, String, int)}.
     */
    public List<ObjectInfo> listObjects(String bucket) {
        return listObjects(bucket, null, 1000);
    }

    /**
     * Lists objects in a bucket using the S3 List Objects V2 API.
     *
     * @param bucket  bucket name
     * @param prefix  key prefix filter, or {@code null} to list everything
     * @param maxKeys maximum number of keys to return (S3 hard-cap: 1 000)
     */
    public List<ObjectInfo> listObjects(String bucket, String prefix, int maxKeys) {
        // list-type=2 selects the V2 API, which uses continuation tokens instead of markers
        StringBuilder query = new StringBuilder("list-type=2&max-keys=").append(maxKeys);
        if (prefix != null && !prefix.isEmpty()) query.append("&prefix=").append(prefix);
        HttpResponse<byte[]> resp = send("GET", "/" + bucket, query.toString(), null, null);
        checkStatus(resp, 200);
        return RgwXmlParser.parseObjectList(resp.body());
    }

    /**
     * Uploads an object as a single PUT request (suitable for objects up to ~5 GB).
     * For larger objects use the multipart upload API.
     *
     * @param contentType MIME type stored as object metadata; may be {@code null}
     */
    public void putObject(String bucket, String key, byte[] data, String contentType) {
        Map<String, String> extra = new LinkedHashMap<>();
        if (contentType != null) extra.put("content-type", contentType);
        HttpResponse<byte[]> resp = send("PUT", "/" + bucket + "/" + key, null, extra, data);
        checkStatus(resp, 200);
    }

    /**
     * Downloads an object as a streaming {@link InputStream}.
     *
     * <p>Unlike other methods this one builds the request manually (instead of delegating
     * to {@link #send}) so it can use {@code BodyHandlers.ofInputStream()} and avoid
     * buffering the entire response body in memory.
     *
     * <p><b>Caller is responsible for closing the returned stream.</b>
     */
    public InputStream getObject(String bucket, String key) {
        try {
            String path = "/" + bucket + "/" + key;
            URI    uri  = URI.create(endpoint + path);
            String host = hostHeader(uri);
            // Sign without a body hash — GET requests have no payload
            Map<String, String> headers = signer.sign("GET", host, path, null, null, null);
            HttpRequest.Builder req = HttpRequest.newBuilder(uri).GET();
            // HttpClient manages "host" itself — setting it throws IllegalArgumentException
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

    /**
     * Fetches metadata for an object without downloading its body (HEAD request).
     * Strips surrounding quotes from the ETag value that S3/RGW always includes.
     */
    public ObjectMetadata headObject(String bucket, String key) {
        HttpResponse<byte[]> resp = send("HEAD", "/" + bucket + "/" + key, null, null, null);
        checkStatus(resp, 200);
        long   contentLength = resp.headers().firstValueAsLong("content-length").orElse(-1L);
        String contentType   = resp.headers().firstValue("content-type").orElse(null);
        // ETags are returned with surrounding double-quotes (e.g. "\"abc123\""); strip them
        String etag          = resp.headers().firstValue("etag")
                                   .map(e -> e.replace("\"", "")).orElse(null);
        return new ObjectMetadata(contentLength, contentType, etag);
    }

    /**
     * Deletes a single object. Both 200 and 204 are treated as success because
     * RGW may return either depending on configuration.
     */
    public void deleteObject(String bucket, String key) {
        HttpResponse<byte[]> resp = send("DELETE", "/" + bucket + "/" + key, null, null, null);
        if (resp.statusCode() != 200 && resp.statusCode() != 204) {
            throw new RgwException(resp.statusCode(), "deleteObject(" + bucket + "/" + key + ")");
        }
    }

    // ── Multipart upload ──────────────────────────────────────────────────────

    /**
     * Initiates a multipart upload and returns the server-assigned {@code uploadId}.
     * The upload ID must be supplied to every subsequent {@link #uploadPart} and
     * {@link #completeMultipartUpload} call.
     */
    public String createMultipartUpload(String bucket, String key, String contentType) {
        Map<String, String> extra = new LinkedHashMap<>();
        if (contentType != null) extra.put("content-type", contentType);
        // POST /<bucket>/<key>?uploads initiates the upload; body is empty
        HttpResponse<byte[]> resp = send("POST", "/" + bucket + "/" + key, "uploads", extra, null);
        checkStatus(resp, 200);
        return RgwXmlParser.parseUploadId(resp.body());
    }

    /**
     * Uploads a single part of a multipart upload.
     * Parts are numbered starting from 1; the server assigns an ETag to each part,
     * which must be collected and passed to {@link #completeMultipartUpload}.
     *
     * @return ETag of the uploaded part, with surrounding quotes stripped
     */
    public String uploadPart(String bucket, String key, String uploadId, int partNumber, byte[] data) {
        String query = "partNumber=" + partNumber + "&uploadId=" + uploadId;
        HttpResponse<byte[]> resp = send("PUT", "/" + bucket + "/" + key, query, null, data);
        checkStatus(resp, 200);
        // ETag is in the response header; it must be re-sent verbatim in CompleteMultipartUpload
        return resp.headers().firstValue("etag")
                   .map(e -> e.replace("\"", ""))
                   .orElseThrow(() -> new RgwException(200, "uploadPart: no ETag in response"));
    }

    /**
     * Lists the parts that have been successfully uploaded for an in-progress multipart upload.
     * Useful to resume an interrupted upload by identifying which parts are already present.
     */
    public List<PartInfo> listParts(String bucket, String key, String uploadId) {
        HttpResponse<byte[]> resp = send("GET", "/" + bucket + "/" + key, "uploadId=" + uploadId, null, null);
        checkStatus(resp, 200);
        return RgwXmlParser.parsePartsList(resp.body());
    }

    /**
     * Finalizes a multipart upload by sending the XML manifest of all completed parts.
     * RGW assembles the final object from the parts and deletes the temporary part data.
     * Parts must be listed in ascending {@code partNumber} order in the manifest.
     */
    public void completeMultipartUpload(String bucket, String key, String uploadId,
                                        List<CompletedPart> parts) {
        // Build the XML body that tells RGW which parts to assemble, in order
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

    /**
     * Aborts an in-progress multipart upload and discards all uploaded parts.
     * Should be called when an upload fails to avoid accumulating orphaned part storage.
     */
    public void abortMultipartUpload(String bucket, String key, String uploadId) {
        HttpResponse<byte[]> resp = send("DELETE", "/" + bucket + "/" + key,
                                         "uploadId=" + uploadId, null, null);
        if (resp.statusCode() != 200 && resp.statusCode() != 204) {
            throw new RgwException(resp.statusCode(), "abortMultipartUpload");
        }
    }

    /**
     * Lists all in-progress (not yet completed or aborted) multipart uploads in a bucket.
     * Useful for cleanup jobs that abort stale uploads.
     */
    public List<MultipartUploadInfo> listMultipartUploads(String bucket) {
        // GET /<bucket>?uploads returns in-progress multipart uploads, not regular objects
        HttpResponse<byte[]> resp = send("GET", "/" + bucket, "uploads", null, null);
        checkStatus(resp, 200);
        return RgwXmlParser.parseMultipartUploads(resp.body());
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    /**
     * Core HTTP dispatch method used by every public operation except {@link #getObject}.
     *
     * <ol>
     *   <li>Builds the full URL from endpoint + path + optional query string.</li>
     *   <li>Computes AWS SigV4 headers via {@link AwsSigV4Signer}.</li>
     *   <li>Sends the request synchronously and returns the raw response.</li>
     * </ol>
     *
     * @param method       HTTP verb (GET, PUT, POST, DELETE, HEAD)
     * @param path         URL path starting with {@code /}
     * @param query        query string without the leading {@code ?}, or {@code null}
     * @param extraHeaders additional headers to include in the signature and request
     * @param body         request body bytes, or {@code null} for bodyless requests
     */
    private HttpResponse<byte[]> send(String method, String path, String query,
                                      Map<String, String> extraHeaders, byte[] body) {
        try {
            String url = endpoint + path + (query != null && !query.isEmpty() ? "?" + query : "");
            URI    uri = URI.create(url);
            String host = hostHeader(uri);
            // SigV4 signs over the payload hash; use empty array (not null) for bodyless requests
            byte[] payload = body != null ? body : new byte[0];

            Map<String, String> headers = signer.sign(method, host, path, query, extraHeaders, payload);

            // noBody() is required for GET/HEAD; ofByteArray() sets Content-Length automatically
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

    /**
     * Builds the value for the {@code Host} header required by AWS SigV4.
     * Omits the port when it is the default for the scheme (80 for http, 443 for https)
     * because including a default port would cause a SigV4 signature mismatch.
     */
    private static String hostHeader(URI uri) {
        int    port   = uri.getPort();
        String scheme = uri.getScheme();
        boolean isDefault = (port < 0)
                || ("http".equals(scheme)  && port == 80)
                || ("https".equals(scheme) && port == 443);
        return isDefault ? uri.getHost() : uri.getHost() + ":" + port;
    }

    /**
     * Throws {@link RgwException} if the response status does not match the expected code.
     * Used as a one-liner assertion after every request whose success status is unambiguous.
     */
    private static void checkStatus(HttpResponse<?> resp, int expected) {
        if (resp.statusCode() != expected) {
            throw new RgwException(resp.statusCode(),
                    "Expected HTTP " + expected + ", got " + resp.statusCode());
        }
    }

    /** No resources need explicit release; present to support try-with-resources usage. */
    @Override
    public void close() {}

    // ── Value types ───────────────────────────────────────────────────────────

    /** Immutable metadata snapshot returned by {@link #headObject}. */
    public record ObjectMetadata(long contentLength, String contentType, String etag) {}

    /** A part number + ETag pair used to build the {@link #completeMultipartUpload} manifest. */
    public record CompletedPart(int partNumber, String etag) {}

    /**
     * Unchecked exception thrown when RGW returns an unexpected HTTP status.
     * The status code is preserved so callers can inspect it without parsing the message string.
     */
    public static final class RgwException extends RuntimeException {
        private final int statusCode;

        public RgwException(int statusCode, String message) {
            super(message + " [HTTP " + statusCode + "]");
            this.statusCode = statusCode;
        }

        public int statusCode() { return statusCode; }
    }
}

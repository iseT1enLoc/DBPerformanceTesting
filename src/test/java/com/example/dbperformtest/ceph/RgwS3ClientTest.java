package com.example.dbperformtest.ceph;

import com.example.dbperformtest.ceph.RgwS3Client.CompletedPart;
import com.example.dbperformtest.ceph.RgwS3Client.ObjectMetadata;
import com.example.dbperformtest.ceph.parser.RgwXmlParser.MultipartUploadInfo;
import com.example.dbperformtest.ceph.parser.RgwXmlParser.ObjectInfo;
import com.example.dbperformtest.ceph.parser.RgwXmlParser.PartInfo;
import org.junit.jupiter.api.*;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for RgwS3Client.
 *
 * Run against a local MicroCeph instance (or any Ceph RGW) configured via
 * system properties:
 *   -Dceph.endpoint=http://localhost:7480
 *   -Dceph.access-key=<key>
 *   -Dceph.secret-key=<secret>
 *
 * Defaults match application.properties values for local dev.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RgwS3ClientTest {

    private static final String ENDPOINT   = System.getProperty("ceph.endpoint",    "http://localhost:7480");
    private static final String ACCESS_KEY = System.getProperty("ceph.access-key",  "61KQRQ9BE3K2KL6RR0YM");
    private static final String SECRET_KEY = System.getProperty("ceph.secret-key",  "QuRNALNI2Azys3vKQZ8Se0PKynzEoncAIvPiLzVI");

    private static final String BUCKET  = "rgw-client-test";
    private static final String KEY     = "hello.txt";
    private static final String CONTENT = "Hello from RgwS3Client!";

    private static RgwS3Client s3;

    @BeforeAll
    static void setup() {
        s3 = new RgwS3Client(ENDPOINT, ACCESS_KEY, SECRET_KEY);
        // Ensure bucket exists
        if (!s3.headBucket(BUCKET)) {
            s3.createBucket(BUCKET);
        }
    }

    // @AfterAll
    // static void teardown() {
    //     // Delete all objects then the bucket
    //     try {
    //         s3.listObjects(BUCKET).forEach(o -> s3.deleteObject(BUCKET, o.key()));
    //         s3.deleteBucket(BUCKET);
    //     } catch (Exception ignored) {}
    // }

    // ── Bucket ops ────────────────────────────────────────────────────────────

    @Test @Order(1)
    void listBuckets_containsTestBucket() {
        List<String> buckets = s3.listBuckets();
        assertThat(buckets).contains(BUCKET);
    }

    @Test @Order(2)
    void headBucket_existingBucket_returnsTrue() {
        assertThat(s3.headBucket(BUCKET)).isTrue();
    }

    @Test @Order(3)
    void headBucket_nonExistingBucket_returnsFalse() {
        assertThat(s3.headBucket("bucket-that-does-not-exist-xyz")).isFalse();
    }

    // ── Object ops ────────────────────────────────────────────────────────────

    @Test @Order(10)
    void putObject_succeeds() {
        s3.putObject(BUCKET, KEY, CONTENT.getBytes(StandardCharsets.UTF_8), "text/plain");
    }

    @Test @Order(11)
    void listObjects_containsUploadedKey() {
        List<ObjectInfo> objects = s3.listObjects(BUCKET);
        assertThat(objects).anyMatch(o -> o.key().equals(KEY));
    }

    @Test @Order(12)
    void getObject_returnsCorrectContent() throws Exception {
        try (InputStream in = s3.getObject(BUCKET, KEY)) {
            String retrieved = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(retrieved).isEqualTo(CONTENT);
        }
    }

    @Test @Order(13)
    void headObject_returnsCorrectMetadata() {
        ObjectMetadata meta = s3.headObject(BUCKET, KEY);
        assertThat(meta.contentLength()).isEqualTo(CONTENT.getBytes(StandardCharsets.UTF_8).length);
        assertThat(meta.contentType()).contains("text/plain");
    }

    @Test @Order(14)
    void deleteObject_removesKey() {
        s3.deleteObject(BUCKET, KEY);
        List<ObjectInfo> objects = s3.listObjects(BUCKET);
        assertThat(objects).noneMatch(o -> o.key().equals(KEY));
    }

    // ── Multipart upload ──────────────────────────────────────────────────────

    private static final int PART_SIZE = 5 * 1024 * 1024; // 5 MB minimum per S3 spec
    private static final String MP_BUCKET = "rgw-multipart-test";
    private static final String MP_KEY    = "multipart.bin";

    @BeforeAll
    static void setupMultipart() {
        if (!s3.headBucket(MP_BUCKET)) s3.createBucket(MP_BUCKET);
    }

    @AfterAll
    static void teardownMultipart() {
        try {
            s3.listMultipartUploads(MP_BUCKET)
              .forEach(u -> s3.abortMultipartUpload(MP_BUCKET, u.key(), u.uploadId()));
            s3.listObjects(MP_BUCKET).forEach(o -> s3.deleteObject(MP_BUCKET, o.key()));
            s3.deleteBucket(MP_BUCKET);
        } catch (Exception ignored) {}
    }

    @Test @Order(20)
    void completeMultipartUpload_assemblesObject() {
        String uploadId = s3.createMultipartUpload(MP_BUCKET, MP_KEY, "application/octet-stream");
        assertThat(uploadId).isNotBlank();

        byte[] part1 = new byte[PART_SIZE];
        byte[] part2 = "final-part".getBytes(StandardCharsets.UTF_8);
        Arrays.fill(part1, (byte) 'A');

        String etag1 = s3.uploadPart(MP_BUCKET, MP_KEY, uploadId, 1, part1);
        String etag2 = s3.uploadPart(MP_BUCKET, MP_KEY, uploadId, 2, part2);
        assertThat(etag1).isNotBlank();
        assertThat(etag2).isNotBlank();

        List<PartInfo> parts = s3.listParts(MP_BUCKET, MP_KEY, uploadId);
        assertThat(parts).hasSize(2);

        s3.completeMultipartUpload(MP_BUCKET, MP_KEY, uploadId, List.of(
                new CompletedPart(1, etag1),
                new CompletedPart(2, etag2)));

        ObjectMetadata head = s3.headObject(MP_BUCKET, MP_KEY);
        assertThat(head.contentLength()).isEqualTo((long) part1.length + part2.length);
    }

    @Test @Order(21)
    void listMultipartUploads_showsInProgressUpload() {
        String tempKey  = "in-progress.bin";
        String uploadId = s3.createMultipartUpload(MP_BUCKET, tempKey, null);
        try {
            List<MultipartUploadInfo> uploads = s3.listMultipartUploads(MP_BUCKET);
            assertThat(uploads).anyMatch(u -> u.uploadId().equals(uploadId));
        } finally {
            s3.abortMultipartUpload(MP_BUCKET, tempKey, uploadId);
        }
    }

    @Test @Order(22)
    void abortMultipartUpload_removesUpload() {
        String tempKey  = "to-abort.bin";
        String uploadId = s3.createMultipartUpload(MP_BUCKET, tempKey, null);
        s3.abortMultipartUpload(MP_BUCKET, tempKey, uploadId);
        List<MultipartUploadInfo> uploads = s3.listMultipartUploads(MP_BUCKET);
        assertThat(uploads).noneMatch(u -> u.uploadId().equals(uploadId));
    }
}

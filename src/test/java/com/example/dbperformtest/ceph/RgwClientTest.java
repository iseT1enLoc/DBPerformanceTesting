package com.example.dbperformtest.ceph;

import com.example.dbperformtest.ceph.args.*;
import com.example.dbperformtest.ceph.messages.*;
import org.junit.jupiter.api.*;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RgwClientTest {

    private static final String ENDPOINT   = System.getProperty("ceph.endpoint",   "http://localhost:80");
    private static final String ACCESS_KEY = System.getProperty("ceph.access-key", "61KQRQ9BE3K2KL6RR0YM");
    private static final String SECRET_KEY = System.getProperty("ceph.secret-key", "QuRNALNI2Azys3vKQZ8Se0PKynzEoncAIvPiLzVI");

    private static final String BUCKET  = "rgw-minio-style-test";
    private static final String KEY     = "hello.txt";
    private static final String CONTENT = "Hello from RgwClient!";

    private static RgwClient client;

    @BeforeAll
    static void setup() throws Exception {
        client = RgwClient.builder()
                .endpoint(ENDPOINT)
                .credentials(ACCESS_KEY, SECRET_KEY)
                .build();

        if (!client.bucketExists(BucketExistsArgs.builder().bucket(BUCKET).build())) {
            client.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());
        }
    }

    @AfterAll
    static void teardown() throws Exception {
        for (Result<Item> r : client.listObjects(ListObjectsArgs.builder().bucket(BUCKET).build())) {
            client.removeObject(RemoveObjectArgs.builder()
                    .bucket(BUCKET).object(r.get().objectName()).build());
        }
        client.removeBucket(RemoveBucketArgs.builder().bucket(BUCKET).build());
    }

    // ── Bucket ops ────────────────────────────────────────────────────────────

    @Test @Order(1)
    void listBuckets_containsTestBucket() throws Exception {
        List<BucketItem> buckets = client.listBuckets();
        assertThat(buckets).anyMatch(b -> b.name().equals(BUCKET));
    }

    @Test @Order(2)
    void bucketExists_existing_returnsTrue() throws Exception {
        assertThat(client.bucketExists(BucketExistsArgs.builder().bucket(BUCKET).build())).isTrue();
    }

    @Test @Order(3)
    void bucketExists_missing_returnsFalse() throws Exception {
        assertThat(client.bucketExists(BucketExistsArgs.builder().bucket("no-such-bucket-xyz").build())).isFalse();
    }

    // ── Object ops ────────────────────────────────────────────────────────────

    @Test @Order(10)
    void putObject_singleUpload() throws Exception {
        byte[] data = CONTENT.getBytes(StandardCharsets.UTF_8);
        ObjectWriteResponse resp = client.putObject(PutObjectArgs.builder()
                .bucket(BUCKET)
                .object(KEY)
                .stream(new ByteArrayInputStream(data), data.length, -1)
                .contentType("text/plain")
                .build());
        assertThat(resp.etag()).isNotBlank();
    }

    @Test @Order(11)
    void listObjects_containsUploadedKey() throws Exception {
        List<Item> items = new ArrayList<>();
        for (Result<Item> r : client.listObjects(ListObjectsArgs.builder().bucket(BUCKET).build()))
            items.add(r.get());
        assertThat(items).anyMatch(i -> i.objectName().equals(KEY));
    }

    @Test @Order(12)
    void getObject_returnsCorrectContent() throws Exception {
        try (GetObjectResponse resp = client.getObject(
                GetObjectArgs.builder().bucket(BUCKET).object(KEY).build())) {
            String content = new String(resp.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(content).isEqualTo(CONTENT);
            assertThat(resp.contentType()).contains("text/plain");
        }
    }

    @Test @Order(13)
    void statObject_returnsCorrectMetadata() throws Exception {
        StatObjectResponse stat = client.statObject(
                StatObjectArgs.builder().bucket(BUCKET).object(KEY).build());
        assertThat(stat.size()).isEqualTo(CONTENT.getBytes(StandardCharsets.UTF_8).length);
        assertThat(stat.contentType()).contains("text/plain");
        assertThat(stat.etag()).isNotBlank();
    }

    @Test @Order(14)
    void removeObject_deletesKey() throws Exception {
        client.removeObject(RemoveObjectArgs.builder().bucket(BUCKET).object(KEY).build());
        List<Item> items = new ArrayList<>();
        for (Result<Item> r : client.listObjects(ListObjectsArgs.builder().bucket(BUCKET).build()))
            items.add(r.get());
        assertThat(items).noneMatch(i -> i.objectName().equals(KEY));
    }

    // ── Multipart ─────────────────────────────────────────────────────────────

    @Test @Order(20)
    void putObject_multipartUpload_assemblesLargeObject() throws Exception {
        int partSize = 5 * 1024 * 1024; // 5 MB
        byte[] part1 = new byte[partSize];
        byte[] part2 = "tail".getBytes(StandardCharsets.UTF_8);
        Arrays.fill(part1, (byte) 'Z');
        byte[] data = Arrays.copyOf(part1, part1.length + part2.length);
        System.arraycopy(part2, 0, data, part1.length, part2.length);

        client.putObject(PutObjectArgs.builder()
                .bucket(BUCKET)
                .object("large.bin")
                .stream(new ByteArrayInputStream(data), data.length, partSize)
                .contentType("application/octet-stream")
                .build());

        StatObjectResponse stat = client.statObject(
                StatObjectArgs.builder().bucket(BUCKET).object("large.bin").build());
        assertThat(stat.size()).isEqualTo(data.length);

        client.removeObject(RemoveObjectArgs.builder().bucket(BUCKET).object("large.bin").build());
    }
}

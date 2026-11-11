package com.example.dbperformtest.api.client;

import com.example.dbperformtest.api.config.CephMinioConfig;
import io.minio.*;
import io.minio.messages.Bucket;
import io.minio.messages.Item;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = {CephMinioConfig.class})
@TestPropertySource(locations = "classpath:application.properties")
class CephMinioClientIntegrationTest {

    private static final String BUCKET  = "minio-basic-ops-test";
    private static final String KEY     = "sample.txt";
    private static final String CONTENT = "Hello from MicroCeph via MinIO SDK!";

    @Autowired
    private MinioClient minio;

    // ── Setup: runs before each test — ensures bucket + object are present ───

    @BeforeEach
    void setUp() throws Exception {
        if (!minio.bucketExists(BucketExistsArgs.builder().bucket(BUCKET).build())) {
            minio.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());
        }

        byte[] data = CONTENT.getBytes(StandardCharsets.UTF_8);
        minio.putObject(PutObjectArgs.builder()
                .bucket(BUCKET)
                .object(KEY)
                .stream(new ByteArrayInputStream(data), data.length, -1)
                .contentType("text/plain")
                .build());
    }

    // ── Teardown: runs once after all tests — cleans up bucket completely ────

    @AfterAll
    static void tearDown(@Autowired MinioClient minio) throws Exception {
        Iterable<Result<Item>> objects = minio.listObjects(
                ListObjectsArgs.builder().bucket(BUCKET).build());
        for (Result<Item> result : objects) {
            minio.removeObject(RemoveObjectArgs.builder()
                    .bucket(BUCKET)
                    .object(result.get().objectName())
                    .build());
        }
        minio.removeBucket(RemoveBucketArgs.builder().bucket(BUCKET).build());
        System.out.println("[tearDown] Bucket deleted: " + BUCKET);
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    void listBuckets_shouldContainTestBucket() throws Exception {
        List<Bucket> buckets = minio.listBuckets();
        assertThat(buckets).anyMatch(b -> b.name().equals(BUCKET));
    }

    @Test
    void listObjects_shouldContainUploadedKey() throws Exception {
        List<String> keys = new ArrayList<>();
        for (Result<Item> r : minio.listObjects(ListObjectsArgs.builder().bucket(BUCKET).build())) {
            keys.add(r.get().objectName());
        }
        assertThat(keys).contains(KEY);
    }

    @Test
    void getObject_shouldReturnExpectedContent() throws Exception {
        try (GetObjectResponse response = minio.getObject(
                GetObjectArgs.builder().bucket(BUCKET).object(KEY).build())) {

            String retrieved = new String(response.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(retrieved).isEqualTo(CONTENT);
        }
    }

    @Test
    void statObject_shouldReturnCorrectMetadata() throws Exception {
        StatObjectResponse stat = minio.statObject(
                StatObjectArgs.builder().bucket(BUCKET).object(KEY).build());

        assertThat(stat.size()).isEqualTo(CONTENT.getBytes(StandardCharsets.UTF_8).length);
        assertThat(stat.contentType()).contains("text/plain");
    }

    @Test
    void removeObject_shouldDeleteKey() throws Exception {
        minio.removeObject(RemoveObjectArgs.builder().bucket(BUCKET).object(KEY).build());

        List<String> keys = new ArrayList<>();
        for (Result<Item> r : minio.listObjects(ListObjectsArgs.builder().bucket(BUCKET).build())) {
            keys.add(r.get().objectName());
        }
        assertThat(keys).doesNotContain(KEY);
    }
}

package com.example.dbperformtest.api.client;

import com.example.dbperformtest.api.config.CephS3Config;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = {CephS3Config.class})
@TestPropertySource(locations = "classpath:application.properties")
class CephS3ClientIntegrationTest {

    private static final String BUCKET  = "s3-basic-ops-test";
    private static final String KEY     = "sample.txt";
    private static final String CONTENT = "Hello from MicroCeph!";

    @Autowired
    private S3Client s3;

    // ── Setup: runs before each test — ensures bucket + object are present ───

    @BeforeEach
    void setUp() {
        // create bucket if it doesn't exist
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(BUCKET).build());
        } catch (NoSuchBucketException e) {
            s3.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
        }

        // upload a fresh copy of the test object
        s3.putObject(
                PutObjectRequest.builder()
                        .bucket(BUCKET)
                        .key(KEY)
                        .contentType("text/plain")
                        .build(),
                RequestBody.fromBytes(CONTENT.getBytes(StandardCharsets.UTF_8)));
    }

    // ── Teardown: runs once after all tests — cleans up bucket completely ────

    @AfterAll
    static void tearDown(@Autowired S3Client s3) {
        // delete all objects first, then the bucket
        s3.listObjectsV2(ListObjectsV2Request.builder().bucket(BUCKET).build())
                .contents()
                .forEach(obj -> s3.deleteObject(
                        DeleteObjectRequest.builder().bucket(BUCKET).key(obj.key()).build()));

        s3.deleteBucket(DeleteBucketRequest.builder().bucket(BUCKET).build());
        System.out.println("[tearDown] Bucket deleted: " + BUCKET);
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    void listBuckets_shouldContainTestBucket() {
        List<Bucket> buckets = s3.listBuckets().buckets();
        assertThat(buckets).anyMatch(b -> b.name().equals(BUCKET));
    }

    @Test
    void listObjects_shouldContainUploadedKey() {
        List<S3Object> objects = s3.listObjectsV2(
                ListObjectsV2Request.builder().bucket(BUCKET).build()).contents();
        assertThat(objects).anyMatch(o -> o.key().equals(KEY));
    }

    @Test
    void getObject_shouldReturnExpectedContent() throws Exception {
        try (ResponseInputStream<GetObjectResponse> in = s3.getObject(
                GetObjectRequest.builder().bucket(BUCKET).key(KEY).build())) {

            String retrieved = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(retrieved).isEqualTo(CONTENT);
        }
    }

    @Test
    void headObject_shouldReturnCorrectMetadata() {
        HeadObjectResponse meta = s3.headObject(
                HeadObjectRequest.builder().bucket(BUCKET).key(KEY).build());

        assertThat(meta.contentLength()).isEqualTo(CONTENT.getBytes(StandardCharsets.UTF_8).length);
        assertThat(meta.contentType()).contains("text/plain");
    }

    @Test
    void deleteObject_shouldRemoveKey() {
        s3.deleteObject(DeleteObjectRequest.builder().bucket(BUCKET).key(KEY).build());

        List<S3Object> objects = s3.listObjectsV2(
                ListObjectsV2Request.builder().bucket(BUCKET).build()).contents();
        assertThat(objects).noneMatch(o -> o.key().equals(KEY));
    }
}

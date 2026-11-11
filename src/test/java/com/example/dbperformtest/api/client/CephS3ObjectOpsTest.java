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
import software.amazon.awssdk.services.s3.model.Tag;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers Ceph RadosGW object-level S3 operations:
 * https://docs.ceph.com/en/latest/radosgw/s3/objectops/
 *
 * Operations: PUT object, GET object, HEAD object, DELETE object,
 * Copy object, GET/PUT object ACL, PUT/GET/DELETE object tagging.
 */
@SpringBootTest(classes = {CephS3Config.class})
@TestPropertySource(locations = "classpath:application.properties")
class CephS3ObjectOpsTest {

    private static final String BUCKET  = "ceph-object-ops-test";
    private static final String KEY     = "sample.txt";
    private static final String CONTENT = "Hello Ceph RadosGW!";

    @Autowired
    private S3Client s3;

    @BeforeAll
    static void createBucket(@Autowired S3Client s3) {
        s3.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
    }

    @AfterAll
    static void cleanUp(@Autowired S3Client s3) {
        s3.listObjectsV2(ListObjectsV2Request.builder().bucket(BUCKET).build())
                .contents()
                .forEach(o -> s3.deleteObject(
                        DeleteObjectRequest.builder().bucket(BUCKET).key(o.key()).build()));
        s3.deleteBucket(DeleteBucketRequest.builder().bucket(BUCKET).build());
    }

    @BeforeEach
    void putBaseObject() {
        s3.putObject(
                PutObjectRequest.builder()
                        .bucket(BUCKET)
                        .key(KEY)
                        .contentType("text/plain")
                        .build(),
                RequestBody.fromString(CONTENT));
    }

    @AfterEach
    void removeObjects() {
        s3.listObjectsV2(ListObjectsV2Request.builder().bucket(BUCKET).build())
                .contents()
                .forEach(o -> s3.deleteObject(
                        DeleteObjectRequest.builder().bucket(BUCKET).key(o.key()).build()));
    }

    // ── PUT object ───────────────────────────────────────────────────────────

    @Test
    void putObject_succeeds() {
        PutObjectResponse response = s3.putObject(
                PutObjectRequest.builder().bucket(BUCKET).key("extra.txt").build(),
                RequestBody.fromString("extra content"));
        assertThat(response.sdkHttpResponse().isSuccessful()).isTrue();
    }

    // ── GET object ───────────────────────────────────────────────────────────

    @Test
    void getObject_returnsExpectedContent() throws Exception {
        try (ResponseInputStream<GetObjectResponse> in = s3.getObject(
                GetObjectRequest.builder().bucket(BUCKET).key(KEY).build())) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo(CONTENT);
        }
    }

    // ── HEAD object ──────────────────────────────────────────────────────────

    @Test
    void headObject_returnsMetadata() {
        HeadObjectResponse meta = s3.headObject(
                HeadObjectRequest.builder().bucket(BUCKET).key(KEY).build());
        assertThat(meta.contentLength()).isEqualTo(CONTENT.getBytes(StandardCharsets.UTF_8).length);
        assertThat(meta.contentType()).contains("text/plain");
    }

    // ── COPY object ──────────────────────────────────────────────────────────

    @Test
    void copyObject_createsDestinationKey() throws Exception {
        String destKey = "copy-of-sample.txt";
        s3.copyObject(CopyObjectRequest.builder()
                .sourceBucket(BUCKET).sourceKey(KEY)
                .destinationBucket(BUCKET).destinationKey(destKey)
                .build());

        try (ResponseInputStream<GetObjectResponse> in = s3.getObject(
                GetObjectRequest.builder().bucket(BUCKET).key(destKey).build())) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo(CONTENT);
        }
    }

    // ── DELETE object ────────────────────────────────────────────────────────

    @Test
    void deleteObject_removesKey() {
        s3.deleteObject(DeleteObjectRequest.builder().bucket(BUCKET).key(KEY).build());
        List<S3Object> objects = s3.listObjectsV2(
                ListObjectsV2Request.builder().bucket(BUCKET).build()).contents();
        assertThat(objects).noneMatch(o -> o.key().equals(KEY));
    }

    // ── GET object ACL ───────────────────────────────────────────────────────

    @Test
    void getObjectAcl_returnsOwner() {
        GetObjectAclResponse acl = s3.getObjectAcl(
                GetObjectAclRequest.builder().bucket(BUCKET).key(KEY).build());
        assertThat(acl.owner()).isNotNull();
    }

    // ── PUT object ACL ───────────────────────────────────────────────────────

    @Test
    void putObjectAcl_privateCanneAcl() {
        PutObjectAclResponse response = s3.putObjectAcl(PutObjectAclRequest.builder()
                .bucket(BUCKET)
                .key(KEY)
                .acl(ObjectCannedACL.PRIVATE)
                .build());
        assertThat(response.sdkHttpResponse().isSuccessful()).isTrue();
    }

    // ── PUT / GET / DELETE object tagging ────────────────────────────────────

    @Test
    void putObjectTagging_andGet() {
        s3.putObjectTagging(PutObjectTaggingRequest.builder()
                .bucket(BUCKET).key(KEY)
                .tagging(Tagging.builder()
                        .tagSet(Tag.builder().key("type").value("text").build())
                        .build())
                .build());

        GetObjectTaggingResponse tags = s3.getObjectTagging(
                GetObjectTaggingRequest.builder().bucket(BUCKET).key(KEY).build());
        assertThat(tags.tagSet()).anyMatch(t -> t.key().equals("type") && t.value().equals("text"));
    }

    @Test
    void deleteObjectTagging_removesAllTags() {
        // first set a tag, then delete
        s3.putObjectTagging(PutObjectTaggingRequest.builder()
                .bucket(BUCKET).key(KEY)
                .tagging(Tagging.builder()
                        .tagSet(Tag.builder().key("temp").value("true").build())
                        .build())
                .build());

        s3.deleteObjectTagging(DeleteObjectTaggingRequest.builder()
                .bucket(BUCKET).key(KEY).build());

        GetObjectTaggingResponse tags = s3.getObjectTagging(
                GetObjectTaggingRequest.builder().bucket(BUCKET).key(KEY).build());
        assertThat(tags.tagSet()).isEmpty();
    }
}

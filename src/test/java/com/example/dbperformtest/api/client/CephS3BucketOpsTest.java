package com.example.dbperformtest.api.client;

import com.example.dbperformtest.api.config.CephS3Config;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.model.Tag;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers Ceph RadosGW bucket-level S3 operations:
 * https://docs.ceph.com/en/latest/radosgw/s3/bucketops/
 *
 * Operations: PUT bucket, DELETE bucket, GET bucket (list objects),
 * GET bucket location, GET/PUT bucket ACL, GET bucket versions,
 * PUT/GET bucket versioning, PUT/GET/DELETE bucket tagging.
 */
@SpringBootTest(classes = {CephS3Config.class})
@TestPropertySource(locations = "classpath:application.properties")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CephS3BucketOpsTest {

    private static final String BUCKET = "ceph-bucket-ops-test";

    @Autowired
    private S3Client s3;

    @BeforeAll
    static void createBucket(@Autowired S3Client s3) {
        s3.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
    }

    @AfterAll
    static void deleteBucket(@Autowired S3Client s3) {
        // drain all object versions before deleting
        ListObjectVersionsResponse versions = s3.listObjectVersions(
                ListObjectVersionsRequest.builder().bucket(BUCKET).build());
        versions.versions().forEach(v -> s3.deleteObject(
                DeleteObjectRequest.builder().bucket(BUCKET).key(v.key()).versionId(v.versionId()).build()));
        versions.deleteMarkers().forEach(m -> s3.deleteObject(
                DeleteObjectRequest.builder().bucket(BUCKET).key(m.key()).versionId(m.versionId()).build()));

        s3.deleteBucket(DeleteBucketRequest.builder().bucket(BUCKET).build());
    }

    // ── PUT / GET bucket ─────────────────────────────────────────────────────

    @Test
    @Order(1)
    void createBucket_headBucketReturns200() {
        HeadBucketResponse head = s3.headBucket(HeadBucketRequest.builder().bucket(BUCKET).build());
        assertThat(head.sdkHttpResponse().isSuccessful()).isTrue();
    }

    // ── GET bucket location ──────────────────────────────────────────────────

    @Test
    @Order(2)
    void getBucketLocation_returnsLocation() {
        GetBucketLocationResponse location = s3.getBucketLocation(
                GetBucketLocationRequest.builder().bucket(BUCKET).build());
        // Ceph returns "" or "us-east-1" for the default zone
        assertThat(location.sdkHttpResponse().isSuccessful()).isTrue();
        System.out.println("[location] " + location.locationConstraintAsString());
    }

    // ── GET / PUT bucket ACL ─────────────────────────────────────────────────

    @Test
    @Order(3)
    void getBucketAcl_returnsOwner() {
        GetBucketAclResponse acl = s3.getBucketAcl(
                GetBucketAclRequest.builder().bucket(BUCKET).build());
        assertThat(acl.owner()).isNotNull();
        System.out.println("[acl] owner=" + acl.owner().displayName());
    }

    @Test
    @Order(4)
    void putBucketAcl_privateCanneAcl() {
        PutBucketAclResponse response = s3.putBucketAcl(PutBucketAclRequest.builder()
                .bucket(BUCKET)
                .acl(BucketCannedACL.PRIVATE)
                .build());
        assertThat(response.sdkHttpResponse().isSuccessful()).isTrue();
    }

    // ── PUT / GET bucket versioning ──────────────────────────────────────────

    @Test
    @Order(5)
    void enableVersioning_andVerify() {
        s3.putBucketVersioning(PutBucketVersioningRequest.builder()
                .bucket(BUCKET)
                .versioningConfiguration(VersioningConfiguration.builder()
                        .status(BucketVersioningStatus.ENABLED)
                        .build())
                .build());

        GetBucketVersioningResponse versioning = s3.getBucketVersioning(
                GetBucketVersioningRequest.builder().bucket(BUCKET).build());
        assertThat(versioning.status()).isEqualTo(BucketVersioningStatus.ENABLED);
    }

    // ── List object versions ─────────────────────────────────────────────────

    @Test
    @Order(6)
    void listObjectVersions_returnsResponse() {
        ListObjectVersionsResponse response = s3.listObjectVersions(
                ListObjectVersionsRequest.builder().bucket(BUCKET).build());
        assertThat(response.sdkHttpResponse().isSuccessful()).isTrue();
    }

    // ── PUT / GET / DELETE bucket tagging ────────────────────────────────────

    @Test
    @Order(7)
    void putBucketTagging_andGet() {
        s3.putBucketTagging(PutBucketTaggingRequest.builder()
                .bucket(BUCKET)
                .tagging(Tagging.builder()
                        .tagSet(Tag.builder().key("env").value("test").build())
                        .build())
                .build());

        GetBucketTaggingResponse tags = s3.getBucketTagging(
                GetBucketTaggingRequest.builder().bucket(BUCKET).build());
        assertThat(tags.tagSet()).anyMatch(t -> t.key().equals("env") && t.value().equals("test"));
    }

    @Test
    @Order(8)
    void deleteBucketTagging_removesAllTags() {
        s3.deleteBucketTagging(DeleteBucketTaggingRequest.builder().bucket(BUCKET).build());
        assertThat(s3.getBucketTagging(
                GetBucketTaggingRequest.builder().bucket(BUCKET).build()).tagSet()).isEmpty();
    }

    // ── Suspend versioning (clean up for AfterAll) ───────────────────────────

    @Test
    @Order(9)
    void suspendVersioning() {
        s3.putBucketVersioning(PutBucketVersioningRequest.builder()
                .bucket(BUCKET)
                .versioningConfiguration(VersioningConfiguration.builder()
                        .status(BucketVersioningStatus.SUSPENDED)
                        .build())
                .build());

        GetBucketVersioningResponse versioning = s3.getBucketVersioning(
                GetBucketVersioningRequest.builder().bucket(BUCKET).build());
        assertThat(versioning.status()).isEqualTo(BucketVersioningStatus.SUSPENDED);
    }
}

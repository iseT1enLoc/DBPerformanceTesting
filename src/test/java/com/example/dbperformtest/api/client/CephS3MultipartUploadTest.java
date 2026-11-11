package com.example.dbperformtest.api.client;

import com.example.dbperformtest.api.config.CephS3Config;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers Ceph RadosGW multipart upload S3 operations:
 * https://docs.ceph.com/en/latest/radosgw/s3/objectops/
 *
 * Operations: Initiate, Upload Part, List Parts, Complete, Abort,
 * List in-progress uploads (GET bucket?uploads).
 *
 * Note: S3 requires each part (except the last) to be at least 5 MB.
 */
@SpringBootTest(classes = {CephS3Config.class})
@TestPropertySource(locations = "classpath:application.properties")
class CephS3MultipartUploadTest {

    private static final String BUCKET   = "ceph-multipart-test";
    private static final String KEY      = "multipart-object.bin";
    private static final int    PART_SIZE = 5 * 1024 * 1024; // 5 MB minimum per S3 spec

    @Autowired
    private S3Client s3;

    @BeforeAll
    static void createBucket(@Autowired S3Client s3) {
        s3.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
    }

    @AfterAll
    static void cleanUp(@Autowired S3Client s3) {
        // abort any lingering in-progress uploads
        s3.listMultipartUploads(ListMultipartUploadsRequest.builder().bucket(BUCKET).build())
                .uploads()
                .forEach(u -> s3.abortMultipartUpload(AbortMultipartUploadRequest.builder()
                        .bucket(BUCKET).key(u.key()).uploadId(u.uploadId()).build()));

        s3.listObjectsV2(ListObjectsV2Request.builder().bucket(BUCKET).build())
                .contents()
                .forEach(o -> s3.deleteObject(
                        DeleteObjectRequest.builder().bucket(BUCKET).key(o.key()).build()));

        s3.deleteBucket(DeleteBucketRequest.builder().bucket(BUCKET).build());
    }

    // ── Complete multipart upload ─────────────────────────────────────────────

    @Test
    void completeMultipartUpload_assemblesObject() {
        // 1. Initiate
        CreateMultipartUploadResponse init = s3.createMultipartUpload(
                CreateMultipartUploadRequest.builder()
                        .bucket(BUCKET).key(KEY).contentType("application/octet-stream")
                        .build());
        String uploadId = init.uploadId();
        assertThat(uploadId).isNotBlank();

        // 2. Upload two parts (part 1 must be >= 5 MB)
        byte[] part1 = new byte[PART_SIZE];     // 5 MB
        byte[] part2 = "final-part".getBytes(); // last part can be any size
        Arrays.fill(part1, (byte) 'A');

        UploadPartResponse resp1 = s3.uploadPart(
                UploadPartRequest.builder()
                        .bucket(BUCKET).key(KEY).uploadId(uploadId).partNumber(1).build(),
                RequestBody.fromBytes(part1));

        UploadPartResponse resp2 = s3.uploadPart(
                UploadPartRequest.builder()
                        .bucket(BUCKET).key(KEY).uploadId(uploadId).partNumber(2).build(),
                RequestBody.fromBytes(part2));

        // 3. List parts
        ListPartsResponse listed = s3.listParts(
                ListPartsRequest.builder().bucket(BUCKET).key(KEY).uploadId(uploadId).build());
        assertThat(listed.parts()).hasSize(2);

        // 4. Complete
        CompleteMultipartUploadResponse complete = s3.completeMultipartUpload(
                CompleteMultipartUploadRequest.builder()
                        .bucket(BUCKET).key(KEY).uploadId(uploadId)
                        .multipartUpload(CompletedMultipartUpload.builder()
                                .parts(
                                        CompletedPart.builder().partNumber(1).eTag(resp1.eTag()).build(),
                                        CompletedPart.builder().partNumber(2).eTag(resp2.eTag()).build())
                                .build())
                        .build());

        assertThat(complete.sdkHttpResponse().isSuccessful()).isTrue();
        assertThat(complete.key()).isEqualTo(KEY);

        // verify assembled object size
        HeadObjectResponse head = s3.headObject(
                HeadObjectRequest.builder().bucket(BUCKET).key(KEY).build());
        assertThat(head.contentLength()).isEqualTo((long) part1.length + part2.length);
    }

    // ── List in-progress multipart uploads ───────────────────────────────────

    @Test
    void listMultipartUploads_showsInProgressUpload() {
        String tempKey = "in-progress.bin";

        CreateMultipartUploadResponse init = s3.createMultipartUpload(
                CreateMultipartUploadRequest.builder().bucket(BUCKET).key(tempKey).build());
        String uploadId = init.uploadId();

        try {
            ListMultipartUploadsResponse list = s3.listMultipartUploads(
                    ListMultipartUploadsRequest.builder().bucket(BUCKET).build());
            assertThat(list.uploads()).anyMatch(u -> u.uploadId().equals(uploadId));
        } finally {
            s3.abortMultipartUpload(AbortMultipartUploadRequest.builder()
                    .bucket(BUCKET).key(tempKey).uploadId(uploadId).build());
        }
    }

    // ── Abort multipart upload ────────────────────────────────────────────────

    @Test
    void abortMultipartUpload_removesUpload() {
        String tempKey = "to-abort.bin";

        CreateMultipartUploadResponse init = s3.createMultipartUpload(
                CreateMultipartUploadRequest.builder().bucket(BUCKET).key(tempKey).build());
        String uploadId = init.uploadId();

        AbortMultipartUploadResponse abort = s3.abortMultipartUpload(
                AbortMultipartUploadRequest.builder()
                        .bucket(BUCKET).key(tempKey).uploadId(uploadId).build());
        assertThat(abort.sdkHttpResponse().isSuccessful()).isTrue();

        // upload should be gone from the listing
        List<MultipartUpload> uploads = s3.listMultipartUploads(
                ListMultipartUploadsRequest.builder().bucket(BUCKET).build()).uploads();
        assertThat(uploads).noneMatch(u -> u.uploadId().equals(uploadId));
    }
}

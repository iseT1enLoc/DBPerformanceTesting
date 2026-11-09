# Java SDK mapping: AWS S3 vs OpenShift Data Foundation (Ceph RGW)

Compiled 2026-11-10. Java-specific companion to `sdk-mapping-s3-vs-odf-ceph.md`.
Based on your backend at `C:\mydata\SoftwareEngineer\backend` (Spring Boot 4.1.1, Java 21, AWS SDK v2 BOM 2.29.52) plus web research.
Ceph docs fetched describe a development version of Ceph, so check your ODF release.

---

## 1. Summary

- The Java data-plane mapping is **1:1: same `software.amazon.awssdk:s3` library, same `S3Client` methods**. Only the client configuration changes (endpoint, credentials, path-style).
- Your backend is already built for this. `S3Config` has an `aws.endpoint` branch that sets `endpointOverride`, static credentials, and path-style access. Today `application-local.properties` points it at LocalStack (`http://localhost:4566`). ODF is a third value for the same properties.
- Four things in your code or build need attention for ODF (details in section 8):
  1. The SDK BOM is pinned to `2.29.52`, below `2.30.0`, which is where default CRC32 upload checksums began. Upgrading later changes request behaviour against RGW.
  2. `FileService.ensureBucket()` calls `createBucket` if the bucket is missing. With ODF the bucket normally comes from an `ObjectBucketClaim`, and the OBC credentials may not allow creating buckets.
  3. `FileService.list()` reads only the first `ListObjectsV2` page (up to 1000 keys).
  4. The bucket name default `my-bucket` should come from the OBC ConfigMap (`BUCKET_NAME`).

---

## 2. Dependencies (Maven)

| Need | Artifact | Notes |
|---|---|---|
| Sync S3 client (what you use) | `software.amazon.awssdk:s3` | Already in your `pom.xml` via the `software.amazon.awssdk:bom` import |
| Transfer Manager (large files, directory sync, progress) | `software.amazon.awssdk:s3-transfer-manager` | Works on the Java async client with multipart enabled, or on the CRT client |
| CRT client (optional performance) | `software.amazon.awssdk.crt:aws-crt` | Without it the Transfer Manager falls back to the Java async client. I found no source confirming CRT behaviour against RGW |
| IAM / STS clients (only if you call RGW IAM/STS) | `software.amazon.awssdk:iam`, `software.amazon.awssdk:sts` | See section 6 |

Your BOM line: `software.amazon.awssdk:bom:2.29.52`. The AWS docs list SDK features that need newer versions:
- `2.30.0`: default CRC32 checksum on uploads (section 7).
- `2.25.x` or later: multipart on the Java async client.
- `2.31.32`: `LegacyMd5Plugin`.
- `2.48.0`: pre-signed URL download extension on `S3AsyncClient`.

---

## 3. Client configuration

### 3.1 What your `S3Config` does today

```java
var builder = S3Client.builder().region(Region.of(region));
if (!endpoint.isBlank()) {
    builder.endpointOverride(URI.create(endpoint))
           .credentialsProvider(StaticCredentialsProvider.create(
                   AwsBasicCredentials.create(accessKey, secretKey)))
           .serviceConfiguration(S3Configuration.builder()
                   .pathStyleAccessEnabled(true)
                   .build());
}
return builder.build();
```

- Endpoint blank: real AWS, with the default credentials chain.
- Endpoint set: S3-compatible store, static keys, path-style. This is the ODF branch as it stands.

### 3.2 Setting-by-setting mapping

| Setting | AWS S3 | ODF / Ceph RGW | Your property |
|---|---|---|---|
| `endpointOverride` | not set | RGW route or service URL (or NooBaa S3 route) | `aws.endpoint` |
| `region` | real region | any valid region, e.g. `us-east-1`. Ceph's Java page: "this is not used, but the AWS SDK requires it" | `aws.region` |
| `credentialsProvider` | default chain (env, profile, role) | `StaticCredentialsProvider` with RGW user or OBC keys | `aws.access-key`, `aws.secret-key` |
| Path-style | virtual-hosted default | path-style. Since SDK 2.18.x, an endpoint override uses virtual-hosted style unless you force path style | set in code |
| Bucket name | your own | from OBC `BUCKET_NAME` (check the generated name) | `aws.s3.bucket` |

Path-style can be set two ways:
```java
// A. what your code uses (S3Configuration on the builder)
.serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
// B. what the AWS Java docs show
.forcePathStyle(true)
```

### 3.3 Proposed `S3Config` for ODF (not applied, for your review)

```java
@Bean
public S3Client s3Client() {
    var builder = S3Client.builder()
            .region(Region.of(region));                 // any region, ignored by RGW
    if (!endpoint.isBlank()) {                           // ODF (or LocalStack)
        builder.endpointOverride(URI.create(endpoint))
               .credentialsProvider(StaticCredentialsProvider.create(
                       AwsBasicCredentials.create(accessKey, secretKey)))
               .forcePathStyle(true);
        // Add ONLY after upgrading the BOM to >= 2.30.0, and only if uploads fail (section 7):
        // .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
        // .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
    }
    return builder.build();
}
```

Because you are leaving AWS entirely, you may later drop the blank-endpoint (AWS) branch and make `endpoint` required.

### 3.4 Spring profile for ODF (proposed `application-odf.properties`)

An `ObjectBucketClaim` creates a ConfigMap and a Secret named after the OBC. Injected as env vars in the pod:

| OBC key | Source | Spring property |
|---|---|---|
| `BUCKET_HOST`, `BUCKET_PORT` | ConfigMap | `aws.endpoint` |
| `AWS_ACCESS_KEY_ID` | Secret | `aws.access-key` |
| `AWS_SECRET_ACCESS_KEY` | Secret | `aws.secret-key` |
| `BUCKET_NAME` | ConfigMap | `aws.s3.bucket` |

```properties
aws.region=us-east-1
aws.endpoint=https://${BUCKET_HOST}:${BUCKET_PORT}
aws.access-key=${AWS_ACCESS_KEY_ID}
aws.secret-key=${AWS_SECRET_ACCESS_KEY}
aws.s3.bucket=${BUCKET_NAME}
```

Caveats:
- The scheme (`http` vs `https`) and port are not in the OBC keys. Check the RGW service or route in your cluster. For HTTPS with a private CA, import the CA into the JVM trust store.
- Pod wiring uses `envFrom` with `configMapRef` and `secretRef`, both named after the OBC, in the same namespace as the OBC.
- The OBC key names come from Rook docs and third-party guides, so confirm them on your ODF version.

References:
- [Rook: Bucket Claim (OBC)](https://rook.io/docs/rook/latest/Storage-Configuration/Object-Storage-RGW/ceph-object-bucket-claim/)
- [Rook v1.4 OBC doc (key names, envFrom)](https://www.rook.io/docs/rook/v1.4/ceph-object-bucket-claim.html)
- [AWS Java SDK v2: S3 clients, endpoint override, `forcePathStyle`](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/examples-s3.html)
- [AWS Java SDK v2: configuring client endpoints](https://docs.amazonaws.cn/sdk-for-java/latest/developer-guide/endpoint-config.html) (China-region copy of the English page)
- [Ceph: Java examples (SDK 2.17.42)](https://docs.ceph.com/en/latest/radosgw/s3/java/)

---

## 4. Operation mapping (Java v2 `S3Client`, 1:1)

Status legend: **Listed** = named as supported in the fetched Ceph docs. **Partial** = supported with limits. **Test** = not named in the Ceph pages I fetched, so test it. **None** = no RGW equivalent found.
"Used by you" marks operations your `FileService` calls today.

### 4.1 Buckets

| Java v2 method | AWS operation | RGW | Used by you | Notes |
|---|---|---|---|---|
| `createBucket(CreateBucketRequest)` | CreateBucket | Listed | yes (`ensureBucket`) | Different canned ACL set. No anonymous create. With OBC you usually do not call it |
| `headBucket(HeadBucketRequest)` | HeadBucket | Listed | yes | Your code relies on `NoSuchBucketException`. Test that RGW's 404 maps to it |
| `deleteBucket` | DeleteBucket | Listed | | |
| `listBuckets()` | ListBuckets | Listed | | |
| `getBucketLocation` | GetBucketLocation | Listed | | |
| `listObjects` | ListObjects v1 | Listed | | `StorageClass` always `STANDARD` |
| `listObjectsV2` / `listObjectsV2Paginator` | ListObjectsV2 | Test | yes (`list`) | Very common. Test on your RGW |
| `listObjectVersions` | ListObjectVersions | Listed | | |
| `putBucketVersioning` / `getBucketVersioning` | Versioning | Listed | | |
| `putBucketLifecycleConfiguration` / `get...` / `deleteBucketLifecycle` | Lifecycle | Listed | | Transitions only between RGW storage classes or cloud tier |
| `putBucketPolicy` / `getBucketPolicy` / `deleteBucketPolicy` | Policy | Partial | | Subset of actions and conditions, no string interpolation |
| `putBucketAcl` / `getBucketAcl` | ACL | Listed | | Canned: `private`, `public-read`, `public-read-write`, `authenticated-read` |
| `putBucketTagging` / `getBucketTagging` / `deleteBucketTagging` | Tagging | Listed | | |
| `putBucketCors` / `getBucketCors` / `deleteBucketCors` | CORS | Listed | | Per-bucket. Tentacle adds a gateway-wide CORS rule |
| `putBucketEncryption` / `getBucketEncryption` / `deleteBucketEncryption` | Default encryption | Listed | | SSE-S3 (Vault only) or SSE-KMS. Needs HTTPS |
| `putBucketNotificationConfiguration` / `get...` | Notifications | Partial | | Targets are HTTP/S, Kafka, AMQP, not SNS/SQS/Lambda |
| `putBucketLogging` / `getBucketLogging` | Logging | Listed | | `TargetGrants` unsupported |
| `putBucketReplication` / `get...` | Replication | Partial | | "Permitted only across zones". Use multisite or NooBaa replication |
| `putBucketWebsite`, `putBucketRequestPayment`, `putBucketOwnershipControls`, `putPublicAccessBlock` (and get/delete) | various | Listed | | |
| `putObjectLockConfiguration` / `getObjectLockConfiguration` | Object Lock | Listed | | Only if enabled at bucket creation |
| analytics / inventory / metrics / intelligent-tiering `*Configuration` methods | | None | | Not in the Ceph support table |
| `putBucketAccelerateConfiguration` | Transfer Acceleration | None | | Not in the Ceph support table |
| directory-bucket, metadata-table, `renameObject`, annotation methods | AWS-newer | None | | Not in the Ceph support table |

### 4.2 Objects

| Java v2 method | AWS operation | RGW | Used by you | Notes |
|---|---|---|---|---|
| `putObject(PutObjectRequest, RequestBody)` | PutObject | Listed | yes (`upload`) | `x-amz-meta-*` values up to 8 KB |
| `getObject(GetObjectRequest, ResponseTransformer)` / `getObjectAsBytes` | GetObject | Listed | yes (`download`) | |
| `headObject` | HeadObject | Listed | | |
| `deleteObject` | DeleteObject | Listed | | |
| `deleteObjects` | DeleteObjects (batch) | Test | | Not named in the fetched pages |
| `copyObject` | CopyObject | Listed | | |
| `getObjectAttributes` | GetObjectAttributes | Listed | | "Added in Tentacle", older releases lack it |
| `putObjectTagging` / `getObjectTagging` / `deleteObjectTagging` | Object tagging | Listed | | |
| `putObjectAcl` / `getObjectAcl` | Object ACL | Listed | | |
| `putObjectRetention` / `getObjectRetention` | Retention | Listed | | |
| `putObjectLegalHold` / `getObjectLegalHold` | Legal hold | Listed | | |
| `restoreObject` | RestoreObject | Test | | Ceph has Cloud Restore for cloud-tiered objects only |
| `selectObjectContent` | S3 Select | Partial | | Partial feature set, CSV only |
| `getObjectTorrent` | Torrent | None | | |
| `writeGetObjectResponse` | Object Lambda | None | | Lambda is an AWS service |

### 4.3 Multipart (low-level)

| Java v2 method | RGW | Notes |
|---|---|---|
| `createMultipartUpload` | Listed | |
| `uploadPart` | Listed | |
| `uploadPartCopy` | Test | Not named in the fetched pages |
| `completeMultipartUpload` | Listed | |
| `abortMultipartUpload` | Listed | |
| `listParts` | Listed | |
| `listMultipartUploads` | Listed | |

Your code uses none of the multipart methods directly. If you add large-file uploads, prefer `S3TransferManager` (section 5.2).

### 4.4 Code for the operations you use, same on both sides

```java
// PutObject (your FileService.upload)
s3Client.putObject(
    PutObjectRequest.builder().bucket(bucket).key(key).contentType(ct).build(),
    RequestBody.fromBytes(bytes));

// GetObject (your FileService.download)
byte[] data = s3Client.getObjectAsBytes(
    GetObjectRequest.builder().bucket(bucket).key(key).build()).asByteArray();

// ListObjectsV2, paginated (replace your single-page call)
s3Client.listObjectsV2Paginator(b -> b.bucket(bucket).prefix(prefix))
        .contents().forEach(o -> use(o.key()));

// DeleteObject
s3Client.deleteObject(b -> b.bucket(bucket).key(key));

// CopyObject
s3Client.copyObject(b -> b.sourceBucket(src).sourceKey(k1)
                          .destinationBucket(dst).destinationKey(k2));

// Versioning on
s3Client.putBucketVersioning(b -> b.bucket(bucket)
        .versioningConfiguration(v -> v.status(BucketVersioningStatus.ENABLED)));
```
Consumer-builder style (`b -> b.bucket(...)`) is what the AWS Java docs use, so both styles are valid.

---

## 5. Higher-level Java features

### 5.1 Presigned URLs (`S3Presigner`)

AWS examples use `S3Presigner.create()` (default region, credentials). For RGW the presigner needs the same endpoint and path-style as the client:

```java
S3Presigner presigner = S3Presigner.builder()
        .endpointOverride(URI.create(publicEndpoint))
        .region(Region.US_EAST_1)
        .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create(accessKey, secretKey)))
        .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
        .build();

PresignedGetObjectRequest p = presigner.presignGetObject(
        GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(10))
                .getObjectRequest(r -> r.bucket(bucket).key(key))
                .build());
String url = p.url().toExternalForm();
```

Notes:
- The builder options come from the Ceph Java page (endpoint, credentials, 20 minute `presignGetObject`) and third-party guides (R2, Tigris) that pass `S3Configuration` to the presigner. Check the `S3Presigner.Builder` Javadoc for your SDK version.
- **Hostname design point**: the URL embeds the endpoint host. In OpenShift your pod may reach RGW through an internal service name, but a browser needs an externally reachable route. Give the presigner the public route URL and the normal client the internal one if they differ.
- Request fields that map to `x-amz-*` headers (metadata, ACL, SSE, storage class) are not in the URL, so the caller must send them. Otherwise `SignatureDoesNotMatch`. Pass them with `putRawQueryParameter` to avoid that (AWS docs).
- `S3AsyncClient.presignedUrlExtension()` (2.48.0+) does not work with the CRT client.

References:
- [AWS Java SDK v2: pre-signed URLs](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/examples-s3-presign.html)
- [Ceph: Java examples (presigner)](https://docs.ceph.com/en/latest/radosgw/s3/java/)
- [Tigris: AWS Java SDK guide (third-party, presigner with custom endpoint)](https://tigrisdata.com/docs/sdks/s3/aws-java-sdk)

### 5.2 `S3TransferManager` (large files, directories, progress)

```java
S3AsyncClient async = S3AsyncClient.builder()
        .endpointOverride(URI.create(endpoint))
        .region(Region.US_EAST_1)
        .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create(accessKey, secretKey)))
        .forcePathStyle(true)
        .multipartEnabled(true)                  // needed for parallel transfers
        .build();

S3TransferManager tm = S3TransferManager.builder().s3Client(async).build();
tm.uploadFile(UploadFileRequest.builder()
        .putObjectRequest(b -> b.bucket(bucket).key(key))
        .source(Paths.get(file))
        .build()).completionFuture().join();
```

Notes:
- Without the `aws-crt` jar, the Transfer Manager uses the Java async client. With CRT it uses the CRT client. I found no source on CRT behaviour with non-AWS endpoints. Start with the Java async client.
- Transfer Manager methods: `uploadFile`, `downloadFile`, `copy`, `uploadDirectory`, `downloadDirectory`, and presigned downloads (2.48.0+).
- It performs multipart uploads, so RGW multipart support (listed) is what matters. Test with a file above the part size.

References:
- [AWS Java SDK v2: S3 Transfer Manager](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/transfer-manager.html)
- [Transfer Manager v1 to v2 changes](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/migration-transfer-manager.html)

---

## 6. Non-S3 clients (only if you use them)

Your repo currently has only the `s3` artifact, so none of this applies yet. For reference if you add them:

| AWS client | Java v2 | RGW equivalent | Notes |
|---|---|---|---|
| IAM | `IamClient` | RGW IAM API on the same endpoint as S3 | Subset: users, access keys, groups, roles, inline and attached policies, OIDC providers, `GetAccountSummary`. Only account root users by default |
| STS | `StsClient` | RGW STS | `AssumeRole`, `AssumeRoleWithWebIdentity`, `GetCallerIdentity`. Caller needs the `roles` capability |
| SNS / SQS / Lambda | `SnsClient` etc. | RGW bucket notifications to HTTP, Kafka, AMQP | Consume events in your own service |
| KMS | `KmsClient` | Vault, Barbican, or KMIP via RGW server-side config | Server-side, not a client call |
| Ceph-only admin | none | Admin Ops REST API (`/admin/...`) | No AWS SDK client. Needs signed HTTP calls and admin capabilities |

Whether `IamClient` / `StsClient` with `endpointOverride` behave correctly against RGW (region handling, endpoint format) is not covered by the sources. Test it before relying on it.

References:
- [Ceph RGW IAM API](https://docs.ceph.com/en/latest/radosgw/iam/)
- [STS in Ceph](https://docs.ceph.com/en/latest/radosgw/STS/)
- [Ceph Admin Operations](https://docs.ceph.com/en/latest/radosgw/adminops/)

---

## 7. Checksums and SDK version (important for Java)

- Since Java v2 **2.30.0**, uploads get an automatic `CRC32` checksum when you do not set one, and the AWS docs say the SDK also stopped calculating MD5 on required operations.
- Your BOM is `2.29.52`, so **today you are below that behaviour**. It is a reason uploads may work now, and a risk when you bump the BOM.
- Red Hat bugs for RGW show failures with newer SDK checksum / `aws-chunked` behaviour on specific RGW versions (Go v2 and Java/Go uploads when a checksum is requested, SHA1/SHA256 trailing checksums). Fixes shipped in specific Ceph builds.
- If uploads fail after upgrading, the AWS-documented mitigations are:
  ```java
  S3Client.builder()
      .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
      .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
  ```
  or env vars `AWS_REQUEST_CHECKSUM_CALCULATION=WHEN_REQUIRED` and `AWS_RESPONSE_CHECKSUM_VALIDATION=WHEN_REQUIRED`, or JVM properties `aws.requestChecksumCalculation` and `aws.responseChecksumValidation`.
- `LegacyMd5Plugin` (2.31.32+) restores MD5 on operations that require a checksum, aimed at third-party S3-compatible stores.
- Another knob used by other S3-compatible stores: `S3Configuration.chunkedEncodingEnabled(false)` (Cloudflare R2 guide). It is untested on RGW here.
- Net advice: pick the SDK version you will ship, then run an upload, multipart upload and presigned PUT against your ODF RGW with that exact version. Do not assume either way.

References:
- [AWS Java SDK v2: data integrity checksums](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/s3-checksums.html)
- [AWS SDKs and Tools: data integrity protections](https://docs.aws.amazon.com/en_us/sdkref/latest/guide/feature-dataintegrity.html)
- [Red Hat Bug 2256967: Java and Go SDK uploads fail when checksum requested](https://bugzilla.redhat.com/show_bug.cgi?id=2256967)
- [Red Hat Bug 2370002: trailing SHA1/SHA256 checksum returns 400](https://bugzilla.redhat.com/show_bug.cgi?id=2370002)
- [Red Hat Bug 2367319: recent aws-go-sdk-v2](https://bugzilla.redhat.com/show_bug.cgi?id=2367319)

---

## 8. Findings for your current code

| # | File | Finding | Why it matters on ODF | Suggested change |
|---|---|---|---|---|
| 1 | `backend/pom.xml:37` | BOM `2.29.52` (before 2.30.0 checksum default) | A later bump changes upload behaviour on RGW | Decide the target SDK version, then run the upload tests in section 7 |
| 2 | `FileService.ensureBucket()` | Creates the bucket if `headBucket` throws `NoSuchBucketException` | Buckets come from OBCs. OBC credentials may not be allowed to create buckets. Creating one outside the OBC leaves it unmanaged | Remove auto-create for ODF, or gate it behind a property |
| 3 | `FileService.list()` | Single `listObjectsV2` call | Returns at most 1000 keys. Silent truncation | Use `listObjectsV2Paginator` |
| 4 | `FileService` | Bucket default `aws.s3.bucket:my-bucket` | OBC may generate the bucket name | Set from `BUCKET_NAME` (section 3.4) |
| 5 | `FileService.ensureBucket()` | Relies on `headBucket` throwing `NoSuchBucketException` | The mapping from RGW's 404 is untested here | Add an integration test against RGW, or call `headBucket` and handle `S3Exception` with status 404 |
| 6 | `application-local.properties` | Points at LocalStack | LocalStack is not RGW, so behaviours can differ (policies, checksums, listing) | Add `application-odf.properties` and run the same tests against RGW |
| 7 | `S3Config` | Only builds `S3Client` | A presigned-URL feature needs an `S3Presigner` with the same endpoint and path-style | Add a presigner bean when needed (section 5.1) |
| 8 | `S3Config` / properties | Property names use the `aws.` prefix | Cosmetic, but misleading once AWS is gone | Optionally rename to `storage.` or `s3.` |
| 9 | `FileService.upload/download` | Whole file in memory (`file.getBytes()`, `getObjectAsBytes`) | Large files stress heap on any backend | Stream with `RequestBody.fromInputStream(in, length)` / `ResponseTransformer.toInputStream()`, or use Transfer Manager |

I did not change any of your files.

---

## 9. Error handling in Java

- S3 errors surface as `software.amazon.awssdk.services.s3.model.S3Exception` and subclasses (`NoSuchBucketException`, `NoSuchKeyException`, ...). Use `e.statusCode()` and `e.awsErrorDetails().errorCode()`.
- RGW returns S3-style errors. The Ceph object-operation docs show codes such as 404 `NoSuchUpload`, 409 `InvalidBucketstate`, `PositionNotEqualToLength`, `ObjectNotAppendable`, and 403 `AccessDenied` for blocked SSE-C. Handle by status code plus error code, not by the AWS-specific message text.
- Network-level failures (TLS trust, DNS, unreachable route) throw `SdkClientException`. A private CA that is not in the JVM trust store is the most likely first failure on OpenShift.

---

## 10. Legacy SDK v1 and Spring Cloud AWS (if other apps use them)

**AWS SDK for Java v1 (`com.amazonaws:aws-java-sdk-s3`)**
- Maintenance mode since 2024-07-31 and **end of support 2025-12-31**. No further updates. AWS recommends moving to v2 and offers an OpenRewrite-based migration tool.
- Mapping of the main ODF-related settings (from my SDK knowledge, not verified in the search results):

| v1 | v2 |
|---|---|
| `AmazonS3ClientBuilder.standard()` | `S3Client.builder()` |
| `.withEndpointConfiguration(new EndpointConfiguration(url, region))` | `.endpointOverride(URI.create(url)).region(Region.of(region))` |
| `.withPathStyleAccessEnabled(true)` | `.forcePathStyle(true)` (or `S3Configuration.pathStyleAccessEnabled(true)`) |
| `.withCredentials(new AWSStaticCredentialsProvider(new BasicAWSCredentials(k, s)))` | `.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(k, s)))` |
| `s3.putObject(bucket, key, file)` | `s3.putObject(b -> b.bucket(bucket).key(key), RequestBody.fromFile(path))` |
| `TransferManager` (v1) | `S3TransferManager` (v2, different API) |

**Spring Cloud AWS (`spring-cloud-aws-starter-s3`, `S3Template`)**
- Not used in your project. My search found no official reference for the endpoint and path-style property names in version 3.x. A 2.3-era answer mentions `spring.cloud.aws.s3.endpoint`, and a GitHub PR mentions a `path-style-access-enabled` property. Verify in the Spring Cloud AWS 3.x reference or the `S3Properties` source before using. Your plain SDK v2 setup avoids the question.

References:
- [AWS: end of support for SDK for Java v1.x on 2025-12-31](https://aws.amazon.com/blogs/developer/announcing-end-of-support-for-aws-sdk-for-java-v1-x-on-december-31-2025)
- [AWS: migration tool for SDK for Java 2.x (GA)](https://aws.amazon.com/blogs/developer/general-availability-release-of-the-migration-tool-for-the-aws-sdk-for-java-2-x)
- [reflectoring.io: Spring Cloud AWS S3 (third-party)](https://reflectoring.io/integrating-amazon-s3-with-spring-boot-using-spring-cloud-aws/)
- [Spring Cloud AWS PR 872 (path-style for CRT client)](https://github.imc.re/awspring/spring-cloud-aws/pull/872)

---

## 11. Java migration checklist

1. Add `application-odf.properties` (section 3.4) and run with `--spring.profiles.active=odf` against a dev ODF RGW.
2. Decide the SDK version. Test `putObject`, `getObject`, `listObjectsV2` (paginated), multipart via Transfer Manager, and presigned PUT/GET with that exact version.
3. Remove or gate `ensureBucket()` auto-create and take the bucket name from `BUCKET_NAME`.
4. Switch `list()` to the paginator.
5. Confirm the HTTPS endpoint and CA trust: import the cluster CA into the JVM trust store (or the container image).
6. Add an `S3Presigner` bean pointing at the public route if you serve presigned URLs to browsers.
7. Run the integration tests against RGW (not only LocalStack).
8. Keep the AWS branch (blank endpoint) until the cutover, then remove it, along with any `aws.` naming you no longer want.

---

## 12. Source index

Ceph / ODF / Rook
- [Ceph: S3 API feature table](https://docs.ceph.com/en/latest/radosgw/s3/)
- [Ceph: Java examples](https://docs.ceph.com/en/latest/radosgw/s3/java/)
- [Ceph: Bucket operations](https://docs.ceph.com/en/latest/radosgw/s3/bucketops/)
- [Ceph: Object operations](https://docs.ceph.com/en/latest/radosgw/s3/objectops/)
- [Ceph: IAM API](https://docs.ceph.com/en/latest/radosgw/iam/)
- [Ceph: STS](https://docs.ceph.com/en/latest/radosgw/STS/)
- [Ceph: Admin Operations](https://docs.ceph.com/en/latest/radosgw/adminops/)
- [Ceph: Bucket policies](https://docs.ceph.com/en/latest/radosgw/bucketpolicy/)
- [Ceph: Server-side encryption](https://docs.ceph.com/en/latest/radosgw/encryption/)
- [Rook: Bucket Claim (OBC)](https://rook.io/docs/rook/latest/Storage-Configuration/Object-Storage-RGW/ceph-object-bucket-claim/)

AWS Java SDK v2
- [S3 clients, endpoint override, forcePathStyle](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/examples-s3.html)
- [Pre-signed URLs](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/examples-s3-presign.html)
- [Transfer Manager](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/transfer-manager.html)
- [Checksums](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/s3-checksums.html)
- [S3Client Javadoc](https://sdk.amazonaws.com/java/api/latest/software/amazon/awssdk/services/s3/S3Client.html)
- [S3 API operation list](https://docs.aws.amazon.com/AmazonS3/latest/API/API_Operations_Amazon_Simple_Storage_Service.html)

## 13. Gaps and caveats
- Java method names follow the AWS naming convention (operation name in camelCase) and the AWS Java docs examples. I did not check each one against the Javadoc.
- `S3Presigner.Builder` options (`endpointOverride`, `serviceConfiguration`) come from the Ceph page and third-party guides, not from the AWS Javadoc.
- I found no sources on: CRT client with non-AWS endpoints, `IamClient`/`StsClient` against RGW, `headBucket` 404 mapping on RGW, or ODF's default OBC endpoint scheme and port.
- Statuses "Test" mean the operation is not named in the Ceph pages I fetched, not that it is unsupported.
- Ceph pages describe a development version. Your ODF release may differ.

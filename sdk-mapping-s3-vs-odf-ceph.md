# SDK mapping: AWS S3 vs OpenShift Data Foundation (Ceph RGW)

Compiled 2026-11-10 from web searches and page fetches. Companion to `s3-to-odf-references.md`.
Ceph pages fetched describe a development version of Ceph (docs.ceph.com `latest`), so check your ODF/Ceph release.

---

## 0. The main point: the data-plane SDK is the same SDK

ODF exposes object storage through Ceph RGW, which speaks the S3 REST API.
There is no separate "Ceph S3 SDK" you must switch to. You keep the AWS SDK (Java v2, boto3, Go, JS, ...) and change configuration:

| Layer | AWS | ODF / Ceph | Mapping |
|---|---|---|---|
| Data plane (buckets, objects, multipart, presign) | AWS SDK `S3Client` | **Same AWS SDK `S3Client`**, pointed at the RGW (or NooBaa) endpoint | 1:1, identical method names |
| Identity and access (users, keys, roles) | AWS SDK `IamClient` | RGW **IAM API** (same endpoint as S3), or Admin Ops API, `radosgw-admin`, or Kubernetes CRDs | Partial 1:1 (IAM subset), plus Ceph-only APIs |
| Temporary credentials | AWS SDK `StsClient` | RGW **STS** (subset) | Partial |
| Notifications | SNS / SQS / Lambda | RGW bucket notifications to HTTP, Kafka, AMQP | Different targets |
| Provisioning a bucket | `createBucket` call or IaC | `ObjectBucketClaim` (Kubernetes) or `createBucket` call | Kubernetes-native option |

References:
- [Ceph Object Gateway S3 API](https://docs.ceph.com/en/latest/radosgw/s3/)
- [Ceph RGW IAM API](https://docs.ceph.com/en/latest/radosgw/iam/)
- [AWS S3 API operations list](https://docs.aws.amazon.com/AmazonS3/latest/API/API_Operations_Amazon_Simple_Storage_Service.html)

---

## 1. Client configuration mapping

### 1.1 What changes

| Setting | AWS S3 | ODF / Ceph RGW |
|---|---|---|
| Endpoint | Derived from region (`s3.<region>.amazonaws.com`) | Explicit: RGW route/service URL, or NooBaa S3 route |
| Region | Real region, used for signing and routing | Required by the SDK but not used by RGW. Ceph's Java page: `Region.US_EAST_1 // this is not used, but the AWS SDK requires it` |
| Credentials | IAM user keys, roles, instance profile, IRSA | RGW user keys. With OBC they come from a Secret. STS role assumption is possible (subset) |
| Addressing style | Virtual-hosted by default | **Path-style** unless you set up wildcard DNS. Java v2 >= 2.18 uses virtual-hosted style when an endpoint override is set, so `forcePathStyle(true)` is needed |
| Signature | SigV4 | SigV4 (supported by RGW) |
| Checksums | SDK default CRC32 on uploads since Java v2 2.30.0 | RGW has had bugs with newer SDK checksum / aws-chunked behaviour. See section 5 |
| TLS | Always HTTPS, public CA | Your cert. Import the CA into the JVM / Python trust store if it is private |

### 1.2 Java SDK v2 (your backend is Java / Spring Boot)

AWS:
```java
S3Client s3 = S3Client.builder()
        .region(Region.AP_SOUTHEAST_1)
        .build();   // default credentials chain
```

ODF / Ceph RGW:
```java
S3Client s3 = S3Client.builder()
        .endpointOverride(URI.create(endpoint))          // from OBC ConfigMap (BUCKET_HOST/PORT) or your route
        .region(Region.US_EAST_1)                        // required by SDK, ignored by RGW
        .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create(accessKey, secretKey)))
        .forcePathStyle(true)
        // precaution against checksum incompatibility, see section 5
        .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
        .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
        .build();
```

Notes:
- The Ceph Java page (written for SDK 2.17.42) shows `.endpointOverride(new URI("https://endpoint.com"))`, `pathStyleAccessEnabled` on the older `S3Configuration`, and `.region(Region.US_EAST_1)`. Newer SDKs expose `forcePathStyle(true)` on the client builder, as in the AWS Java docs.
- The checksum builder methods exist since 2.30.0.
- Presigned URLs: build an `S3Presigner` with the same endpoint, credentials and path-style setting. The Ceph example builds a 20 minute `presignGetObject`.

References:
- [Ceph: Java examples (AWS SDK 2.17.42)](https://docs.ceph.com/en/latest/radosgw/s3/java/)
- [AWS Java SDK v2: S3 examples](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/examples-s3.html)
- [AWS Java SDK v2: checksums, WHEN_REQUIRED, LegacyMd5Plugin](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/s3-checksums.html)
- [S3Client API reference](https://sdk.amazonaws.com/java/api/latest/software/amazon/awssdk/services/s3/S3Client.html)

### 1.3 Python (boto3)

AWS:
```python
s3 = boto3.client("s3", region_name="ap-southeast-1")
```

ODF / Ceph RGW:
```python
import boto3
from botocore.config import Config

s3 = boto3.client(
    "s3",
    endpoint_url=ENDPOINT,
    aws_access_key_id=ACCESS_KEY,
    aws_secret_access_key=SECRET_KEY,
    config=Config(signature_version="s3v4", s3={"addressing_style": "path"}),
)
```

Notes:
- `addressing_style="path"` is the safer default for RGW without wildcard DNS. The Red Hat Ceph testing chapter shows a client built from `endpoint_url` and credentials alone, so it is not always needed.
- The official Ceph Python page documents the legacy `boto` library, not boto3. boto3 patterns above come from third-party guides and Red Hat docs.
- RGW extension `AllowUnordered` needs a boto3 model extension file under `~/.aws/models/s3/2006-03-01/`.

References:
- [Ceph: Python examples](https://docs.ceph.com/en/latest/radosgw/s3/python/)
- [Red Hat Ceph Storage: Object Gateway guide, Testing](https://docs.redhat.com/en/documentation/red_hat_ceph_storage/9/html/object_gateway_guide/testing)
- [How to use boto3 with Ceph RGW S3 (third-party)](https://oneuptime.com/blog/post/2026-03-31-rook-use-boto3-python-ceph-rgw-s3/view)

### 1.4 Getting the endpoint and credentials from ODF (OBC)

An `ObjectBucketClaim` creates a ConfigMap and a Secret, both named after the OBC, in the OBC's namespace.

| Value | Source | Key |
|---|---|---|
| Bucket name | ConfigMap `<obc-name>` | `BUCKET_NAME` |
| Endpoint host | ConfigMap | `BUCKET_HOST` |
| Endpoint port | ConfigMap | `BUCKET_PORT` |
| Access key | Secret `<obc-name>` | `AWS_ACCESS_KEY_ID` |
| Secret key | Secret | `AWS_SECRET_ACCESS_KEY` |

A pod consumes them with `envFrom` (`configMapRef` and `secretRef`). The consuming pod must be in the same namespace.
Not covered by the sources: whether the endpoint is `http` or `https` and which port to use. Check the ODF route/service in your cluster.
ODF storage classes: `ocs-storagecluster-ceph-rgw` (RGW) and `openshift-storage.noobaa.io` (MCG).

References:
- [Rook: Bucket Claim (OBC)](https://rook.io/docs/rook/latest/Storage-Configuration/Object-Storage-RGW/ceph-object-bucket-claim/)
- [Rook v1.4 OBC doc (shows BUCKET_* and AWS_* keys, envFrom)](https://www.rook.io/docs/rook/v1.4/ceph-object-bucket-claim.html)
- [ODF: Managing hybrid and multicloud resources (latest)](https://docs.redhat.com/en/documentation/red_hat_openshift_data_foundation/latest/html-single/managing_hybrid_and_multicloud_resources/index)

---

## 2. Operation mapping (S3 API operation, SDK method, RGW status)

The method names are identical on both sides, because the same SDK is used.
SDK method names: Java v2 `S3Client` (camelCase) and boto3 `client("s3")` (snake_case).

Status legend:
- Listed: named as supported in the fetched Ceph docs.
- Partial: supported with documented limits.
- Test: not named in the Ceph pages I fetched. Do not assume. Test it against your cluster.
- None: no RGW equivalent found.

### 2.1 Buckets

| AWS S3 operation | Java v2 `S3Client` | boto3 | RGW | Notes |
|---|---|---|---|---|
| CreateBucket | `createBucket` | `create_bucket` | Listed | Different canned ACL set. Re-create of an owned bucket succeeds unless `rgw_bucket_eexist_override`. No anonymous create |
| DeleteBucket | `deleteBucket` | `delete_bucket` | Listed | |
| HeadBucket | `headBucket` | `head_bucket` | Listed | "Get Bucket Info (HEAD)" |
| ListBuckets | `listBuckets` | `list_buckets` | Listed | |
| GetBucketLocation | `getBucketLocation` | `get_bucket_location` | Listed | |
| ListObjects (v1) | `listObjects` | `list_objects` | Listed | `StorageClass` always returns `STANDARD`. Non-standard `allow-unordered` |
| ListObjectsV2 | `listObjectsV2` | `list_objects_v2` | Test | Very commonly used. Verify on your RGW version |
| ListObjectVersions | `listObjectVersions` | `list_object_versions` | Listed | "Bucket Object Versions" |
| Put/GetBucketVersioning | `putBucketVersioning` / `getBucketVersioning` | `put_bucket_versioning` / `get_bucket_versioning` | Listed | |
| Put/Get/DeleteBucketLifecycleConfiguration | `putBucketLifecycleConfiguration` etc. | `put_bucket_lifecycle_configuration` etc. | Listed | Transitions only between RGW storage classes (or cloud tier). See [placement](https://docs.ceph.com/en/latest/radosgw/placement/) |
| Put/Get/DeleteBucketPolicy | `putBucketPolicy` etc. | `put_bucket_policy` etc. | Partial | Subset of actions and conditions, no string interpolation. See section 4 |
| Put/GetBucketAcl | `putBucketAcl` / `getBucketAcl` | `put_bucket_acl` / `get_bucket_acl` | Listed | Canned ACLs: `private`, `public-read`, `public-read-write`, `authenticated-read` |
| Put/Get/DeleteBucketTagging | `putBucketTagging` etc. | `put_bucket_tagging` etc. | Listed | |
| Put/Get/DeleteBucketCors | `putBucketCors` etc. | `put_bucket_cors` etc. | Listed (per-bucket) | Tentacle adds a gateway-wide CORS rule (`rgw_gcors_*`) |
| Put/Get/DeleteBucketEncryption | `putBucketEncryption` etc. | `put_bucket_encryption` etc. | Listed | SSE-S3 (Vault only) or SSE-KMS. Needs HTTPS |
| Put/Get/DeleteBucketWebsite | `putBucketWebsite` etc. | `put_bucket_website` etc. | Listed | |
| Put/GetBucketLogging | `putBucketLogging` / `getBucketLogging` | `put_bucket_logging` / `get_bucket_logging` | Listed | `TargetGrants` unsupported, `PartitionDateSource` ignored (`DeliveryTime`). Extra: flush (`POST /{bucket}?logging`) |
| Put/GetBucketNotificationConfiguration | `putBucketNotificationConfiguration` etc. | `put_bucket_notification_configuration` etc. | Partial | Targets are HTTP/S, Kafka, AMQP, not SNS/SQS/Lambda. See section 4 |
| Put/GetBucketReplication | `putBucketReplication` etc. | `put_bucket_replication` etc. | Partial | "Permitted only across zones". Real replication is multisite or NooBaa |
| Put/GetBucketRequestPayment | `putBucketRequestPayment` etc. | `put_bucket_request_payment` etc. | Listed | |
| Put/Get/DeleteBucketOwnershipControls | `putBucketOwnershipControls` etc. | `put_bucket_ownership_controls` etc. | Listed | "Object Ownership" |
| Put/Get/DeletePublicAccessBlock | `putPublicAccessBlock` etc. | `put_public_access_block` etc. | Listed | Account-level via S3 Control |
| Put/GetObjectLockConfiguration | `putObjectLockConfiguration` etc. | `put_object_lock_configuration` etc. | Listed | Only if enabled at bucket creation |
| Analytics, Inventory, Metrics, IntelligentTiering configurations | `put/get/delete/listBucket...Configuration` | matching `*_configuration` calls | None | Not in the Ceph support table. Treat as unavailable |
| Accelerate configuration | `put/getBucketAccelerateConfiguration` | `put/get_bucket_accelerate_configuration` | None | Not in the Ceph support table |
| Metadata tables, annotations, directory buckets (`CreateSession`, `ListDirectoryBuckets`), `RenameObject`, `UpdateObjectEncryption` | various | various | None | AWS-newer features, not in the Ceph table |

### 2.2 Objects

| AWS S3 operation | Java v2 `S3Client` | boto3 | RGW | Notes |
|---|---|---|---|---|
| PutObject | `putObject` | `put_object`, `upload_file` | Listed | `x-amz-meta-*` value up to 8 KB |
| GetObject | `getObject` | `get_object`, `download_file` | Listed | |
| HeadObject | `headObject` | `head_object` | Listed | |
| DeleteObject | `deleteObject` | `delete_object` | Listed | Documented header: `x-amz-delete-if-unmodified-since` |
| DeleteObjects (batch) | `deleteObjects` | `delete_objects` | Test | Not named in the pages fetched. Verify |
| CopyObject | `copyObject` | `copy_object`, `copy` | Listed | |
| PostObject (browser form upload) | n/a (form POST) | `generate_presigned_post` | Listed | |
| GetObjectAttributes | `getObjectAttributes` | `get_object_attributes` | Listed | "Added in Tentacle". Older releases lack it |
| Put/Get/DeleteObjectTagging | `putObjectTagging` etc. | `put_object_tagging` etc. | Listed | |
| Put/GetObjectAcl | `putObjectAcl` / `getObjectAcl` | `put_object_acl` / `get_object_acl` | Listed | |
| Put/GetObjectRetention | `putObjectRetention` etc. | `put_object_retention` etc. | Listed | |
| Put/GetObjectLegalHold | `putObjectLegalHold` etc. | `put_object_legal_hold` etc. | Listed | |
| RestoreObject | `restoreObject` | `restore_object` | Test | Ceph has [Cloud Restore](https://docs.ceph.com/en/tentacle/radosgw/cloud-restore/) for cloud-tiered objects only |
| SelectObjectContent | `selectObjectContent` | `select_object_content` | Partial | [S3 Select](https://docs.ceph.com/en/latest/radosgw/s3select/): partial feature set, CSV only |
| GetObjectTorrent | `getObjectTorrent` | n/a | None | Not in the Ceph table |
| WriteGetObjectResponse (Object Lambda) | `writeGetObjectResponse` | `write_get_object_response` | None | Lambda is an AWS service |
| Append Object | none in the standard SDK API | none | Ceph extension | `PUT /{bucket}/{object}?append&position=`. Not usable with versioning. Compression and encryption disabled for appendable objects |

### 2.3 Multipart uploads

| AWS S3 operation | Java v2 `S3Client` | boto3 | RGW | Notes |
|---|---|---|---|---|
| CreateMultipartUpload | `createMultipartUpload` | `create_multipart_upload` | Listed | |
| UploadPart | `uploadPart` | `upload_part` | Listed | |
| UploadPartCopy | `uploadPartCopy` | `upload_part_copy` | Test | Not named in the pages fetched |
| CompleteMultipartUpload | `completeMultipartUpload` | `complete_multipart_upload` | Listed | |
| AbortMultipartUpload | `abortMultipartUpload` | `abort_multipart_upload` | Listed | |
| ListParts | `listParts` | `list_parts` | Listed | StorageClass shown as `STANDARD` or `REDUCED_REDUNDANCY` |
| ListMultipartUploads | `listMultipartUploads` | `list_multipart_uploads` | Listed | |

High-level helpers (Java `S3TransferManager`, boto3 `upload_file` / `download_file`) are SDK-side wrappers over the same operations, so they work the same way. Watch their default checksum behaviour (section 5).

### 2.4 Presigned URLs

| Concept | AWS | ODF / Ceph |
|---|---|---|
| Java | `S3Presigner.presignGetObject` / `presignPutObject` | Same API, with the presigner given the RGW endpoint, credentials, path-style. Ceph example: 20 minute GET URL |
| boto3 | `generate_presigned_url` | Same call. Ceph's page shows the legacy `generate_url` for boto |
| Hostname in URL | `*.amazonaws.com` | RGW endpoint. Clients and browsers must reach and trust it |

---

## 3. Identity, access, and admin mapping (non-S3 SDK clients)

| AWS | Java v2 / boto3 client | ODF / Ceph equivalent | Notes |
|---|---|---|---|
| IAM users, access keys | `IamClient` / `client("iam")` | RGW **IAM API** (CreateUser, CreateAccessKey, ...), `CephObjectStoreUser` CR, Admin Ops API, `radosgw-admin` | IAM API served from the same endpoint as S3. By default only account root users can call it |
| IAM groups | `IamClient` group calls | IAM API: CreateGroup, AddUserToGroup, Put/AttachGroupPolicy ... | Listed |
| IAM roles | `IamClient` role calls | IAM API: CreateRole, UpdateAssumeRolePolicy, Put/AttachRolePolicy, TagRole ... | Listed |
| IAM policies (inline) | `putUserPolicy` etc. | IAM API: PutUserPolicy, GetUserPolicy, DeleteUserPolicy, ListUserPolicies | Listed |
| IAM managed policies | `attachUserPolicy` | IAM API AttachUserPolicies. Predefined: IAMFullAccess, IAMReadOnlyAccess, AmazonSNSFullAccess, AmazonSNSReadOnlyAccess, AmazonS3FullAccess, AmazonS3ReadOnlyAccess | Custom managed policies (CreatePolicy) not in the list |
| OIDC providers | `createOpenIDConnectProvider` | IAM API: Create/Get/Delete/ListOpenIDConnectProvider | Listed |
| STS AssumeRole | `StsClient.assumeRole` | RGW STS AssumeRole | Needs `roles` capability on the caller |
| STS AssumeRoleWithWebIdentity (IRSA style) | `assumeRoleWithWebIdentity` | RGW STS AssumeRoleWithWebIdentity | Session tags only via this call |
| STS GetCallerIdentity | `getCallerIdentity` | RGW STS GetCallerIdentity | |
| Other STS calls (GetSessionToken, AssumeRoleWithSAML, ...) | | Not in the docs I found | Test |
| Per-user / per-bucket quota | Service Quotas (account-level) | Admin Ops API `PUT /admin/user?quota`, `PUT /admin/bucket?quota`, accounts quota, or `radosgw-admin quota set` | Ceph-only. Needs `users`/`buckets` write capability |
| Account summary | `getAccountSummary` | IAM API GetAccountSummary | Listed |

Two admin surfaces to keep apart:
1. **IAM API**: AWS-shaped, usable from AWS SDKs, served on the S3 endpoint.
2. **Admin Ops API** (`/admin/...`): Ceph-specific REST, not in AWS SDKs. Needs admin capabilities and signed requests.

References:
- [Ceph RGW IAM API](https://docs.ceph.com/en/latest/radosgw/iam/)
- [Ceph User Accounts](https://docs.ceph.com/en/latest/radosgw/account/)
- [Ceph Admin Operations (Admin Ops API)](https://docs.ceph.com/en/latest/radosgw/adminops/)
- [radosgw-admin man page](https://docs.ceph.com/en/latest/man/8/radosgw-admin/)
- [STS in Ceph](https://docs.ceph.com/en/latest/radosgw/STS/)
- [Session tags in STS](https://docs.ceph.com/en/latest/radosgw/session-tags/)

---

## 4. Feature behaviour that changes what the same call does

### 4.1 Bucket policy
Supported S3 actions are a subset: `s3:CreateBucket`, `s3:ListBucket`, `s3:ListBucketVersions`, `s3:PutBucketAcl`, object get/put/ACL/tagging (and version variants).
Supported condition keys include `aws:SecureTransport`, `aws:SourceIp`, `aws:UserAgent`, `aws:Referer`, `s3:prefix`, `s3:delimiter`, `s3:max-keys`, `s3:x-amz-acl`, `s3:x-amz-server-side-encryption*`, `s3:RequestObjectTag/*`, `s3:ExistingObjectTag/*`.
No string interpolation. Policies using other actions (for example `s3:DeleteObject`, `s3:PutBucketPolicy`) are not in the supported list on that page, so re-test each policy you migrate.
- [Ceph: Bucket policies](https://docs.ceph.com/en/latest/radosgw/bucketpolicy/)

### 4.2 Event notifications
AWS targets (SNS, SQS, Lambda, EventBridge) map to RGW targets (HTTP/S, Kafka, AMQP). Message format and compatibility have their own page.
Ceph additions: delete a single notification, `Lifecycle` and `Synced` events must be listed explicitly, `S3Key` filter max 3 rules.
- [Ceph: Bucket notifications](https://docs.ceph.com/en/latest/radosgw/notifications/)
- [Ceph: S3 bucket notifications compatibility](https://docs.ceph.com/en/latest/radosgw/s3-notification-compatibility/)
- [AWS: Event notification types and destinations](https://docs.aws.amazon.com/AmazonS3/latest/userguide/notification-how-to-event-types-and-destinations.html)

### 4.3 Encryption
| AWS | Ceph |
|---|---|
| SSE-S3 | SSE-S3: Vault integration only |
| SSE-KMS (AWS KMS) | SSE-KMS: Barbican, Vault, or KMIP |
| SSE-C | SSE-C: no setup needed, can be blocked per bucket |
Requests for server-side encryption must use HTTPS. Keys must be 256-bit, base64-encoded. Default bucket encryption works through Put/Get/DeleteBucketEncryption.
- [Ceph: Server-side encryption](https://docs.ceph.com/en/latest/radosgw/encryption/)
- [Ceph: Vault integration](https://docs.ceph.com/en/latest/radosgw/vault/)

### 4.4 Storage classes and lifecycle
AWS classes (Standard-IA, Glacier, ...) map to RGW storage classes you define through placement targets and pools. `StorageClass` on `putObject` works for classes that exist in the zonegroup.
- [Ceph: Pool placement and storage classes](https://docs.ceph.com/en/latest/radosgw/placement/)
- [Ceph: Cloud transition](https://docs.ceph.com/en/reef/radosgw/cloud-transition/)
- [AWS: Lifecycle and other bucket configurations](https://docs.aws.amazon.com/AmazonS3/latest/userguide/lifecycle-and-other-bucket-config.html)

### 4.5 Other documented differences
- Bucket names: stricter by default. `rgw_relaxed_s3_bucket_names=true` allows up to 255 characters with periods, dashes, underscores.
- Object lock: only for buckets created with it enabled.
- Unsupported header: `x-amz-id-2` (response).
- Canned ACLs: only four values.
- Object user metadata: 8 KB per value.
- `Get Object Attributes`: only on Tentacle or later.
- `allow-unordered` list extension: Ceph-only.
- Append Object: Ceph-only, conflicts with versioning.

---

## 5. Known SDK compatibility risks

1. **Default checksums (Java v2 >= 2.30.0, also Go v2, JS v3, Python)**
   - SDKs add a CRC-based checksum to uploads by default and may use `aws-chunked` / trailer encoding.
   - Red Hat Ceph bugs show RGW failures with some SDK versions: Go v2 omitting the decoded content length, SHA1/SHA256 trailing checksums returning 400 with aws-sdk-go-v2 while CRC32/CRC32C/CRC64NVME worked, and older RGW not recognising `STREAMING-AWS4-HMAC-SHA256-PAYLOAD` and `STREAMING-UNSIGNED-PAYLOAD-TRAILER` (fixed in ceph-17.2.6-208).
   - Mitigation to try if uploads fail: set `requestChecksumCalculation` / `responseChecksumValidation` to `WHEN_REQUIRED` (builder, env vars `AWS_REQUEST_CHECKSUM_CALCULATION` / `AWS_RESPONSE_CHECKSUM_VALIDATION`, or JVM properties `aws.requestChecksumCalculation` / `aws.responseChecksumValidation`).
   - `LegacyMd5Plugin` (Java v2 >= 2.31.32) restores MD5 on operations that require a checksum, for third-party S3 stores.
   - The sources do not confirm that your ODF version needs this. Test an upload with the exact SDK version you use before deciding.
2. **Virtual-hosted vs path-style**: set path-style unless you configured wildcard DNS and a wildcard certificate.
3. **Region**: any valid-looking region works. Do not rely on region-based behaviour (cross-region calls, region in ARN).
4. **ETag**: multipart ETags are not an MD5 of the data on either system. Use your own hashes or checksums to validate.
5. **Operations marked Test above**: `listObjectsV2`, `deleteObjects`, `uploadPartCopy`, `restoreObject`. Write a small integration test per operation your code calls.

References:
- [Bug 2367319: rgw checksums with recent aws-go-sdk-v2](https://bugzilla.redhat.com/show_bug.cgi?id=2367319)
- [Bug 2370002: trailing SHA1/SHA256 checksum 400 error](https://bugzilla.redhat.com/show_bug.cgi?id=2370002)
- [Bug 2256967: uploads with Java and Go SDKs fail when checksum requested](https://bugzilla.redhat.com/show_bug.cgi?id=2256967)
- [Data Integrity Protections for Amazon S3 (SDKs and Tools reference)](https://docs.aws.amazon.com/en_us/sdkref/latest/guide/feature-dataintegrity.html)
- [ceph-users thread: boto3 disable or enable checksum on bucket/rgw](https://spinics.net/lists/ceph-users/msg86008.html)

---

## 6. Migration checklist for application code

1. Make `endpoint`, `region`, `accessKey`, `secretKey`, `pathStyle` configuration properties (no `amazonaws.com` anywhere).
2. Build `S3Client` and `S3Presigner` from those properties (section 1.2).
3. Grep the code for every S3 operation it calls and tick each against section 2. Anything marked Test or None needs a test or a redesign.
4. Grep for other AWS clients: `IamClient`, `StsClient`, `SnsClient`, `SqsClient`, `KmsClient`, `CloudFrontClient`, `LambdaClient`, `CloudWatchClient`. Each needs an equivalent from section 3 or 4, or removal.
5. Check bucket policies, lifecycle rules, notifications, and encryption settings against section 4 before copying them.
6. Run an upload / download / multipart / presign test with your exact SDK version against ODF (checksum risk, section 5).
7. Keep one code path: the same `S3Client` code should run against both AWS and ODF during the migration window, switched only by configuration.

---

## 7. Source index

Ceph and ODF
- [Ceph Object Gateway S3 API (feature table)](https://docs.ceph.com/en/latest/radosgw/s3/)
- [Ceph: Bucket operations](https://docs.ceph.com/en/latest/radosgw/s3/bucketops/)
- [Ceph: Object operations](https://docs.ceph.com/en/latest/radosgw/s3/objectops/)
- [Ceph: Java examples](https://docs.ceph.com/en/latest/radosgw/s3/java/)
- [Ceph: Python examples](https://docs.ceph.com/en/latest/radosgw/s3/python/)
- [Ceph: IAM API](https://docs.ceph.com/en/latest/radosgw/iam/)
- [Ceph: Admin Operations](https://docs.ceph.com/en/latest/radosgw/adminops/)
- [Ceph: Bucket policies](https://docs.ceph.com/en/latest/radosgw/bucketpolicy/)
- [Ceph: Server-side encryption](https://docs.ceph.com/en/latest/radosgw/encryption/)
- [Ceph: STS](https://docs.ceph.com/en/latest/radosgw/STS/)
- [Ceph: Config reference (CORS note)](https://docs.ceph.com/en/latest/radosgw/config-ref/)
- [Rook: Bucket Claim](https://rook.io/docs/rook/latest/Storage-Configuration/Object-Storage-RGW/ceph-object-bucket-claim/)
- [ODF: Managing hybrid and multicloud resources](https://docs.redhat.com/en/documentation/red_hat_openshift_data_foundation/latest/html-single/managing_hybrid_and_multicloud_resources/index)

AWS
- [S3 API operations list](https://docs.aws.amazon.com/AmazonS3/latest/API/API_Operations_Amazon_Simple_Storage_Service.html)
- [Java SDK v2 developer guide: S3](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/examples-s3.html)
- [Java SDK v2: checksums](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/s3-checksums.html)
- [S3Client Javadoc](https://sdk.amazonaws.com/java/api/latest/software/amazon/awssdk/services/s3/S3Client.html)

## 8. Gaps and caveats
- The AWS API reference page lists operation names only. SDK method names for Java v2 and boto3 follow the standard naming convention (operation name in camelCase or snake_case) and were not individually verified against each SDK's reference. The boto3 reference (`boto3.amazonaws.com`) was not fetched.
- I did not find Ceph pages stating support for `ListObjectsV2`, `DeleteObjects`, `UploadPartCopy`, `RestoreObject`, or the full list of STS calls, so those are marked Test.
- All Ceph pages fetched say they describe a development version. Compare against the Ceph release shipped in your ODF version.
- ODF-specific details (RGW route name, `http` vs `https`, `BUCKET_PORT` value, NooBaa vs RGW endpoint differences) were not verified. Check them on your cluster.
- Checksum issues come from Red Hat Bugzilla reports for specific SDK and RGW versions. Their current status was not verified.

# Ceph RGW S3 Client

Custom S3-compatible client for Ceph RadosGW (ODF on-premise).  
Zero external dependencies — JDK 17 built-ins only.

---

## Why not AWS SDK / MinIO?

| Removed | Reason |
|---------|--------|
| `software.amazon.awssdk:s3` | Vendor-specific SDK, open source |
| `io.minio:minio` | Open source, community-only support |

Replacement: `java.net.http.HttpClient` + AWS Signature V4 (algorithm only, not SDK).  
Wire protocol is identical — Ceph RGW cannot distinguish from AWS SDK.

---

## Project structure

```
src/
  main/java/.../ceph/
    RgwS3Client.java          main client bean
    signer/
      AwsSigV4Signer.java     AWS Signature V4 implementation
    parser/
      RgwXmlParser.java       S3 XML response parser
  test/java/.../ceph/
    RgwS3ClientTest.java      integration tests (plain JUnit 5)
  resources/
    application.properties    endpoint + credentials config
```

---

## Configuration

```properties
# application.properties
ceph.s3.endpoint=http://localhost:80
ceph.s3.access-key=<access-key>
ceph.s3.secret-key=<secret-key>
```

---

## API Reference

### Instantiate

```java
RgwS3Client s3 = new RgwS3Client(endpoint, accessKey, secretKey);
```

### Bucket operations

```java
List<String>  s3.listBuckets()
void          s3.createBucket(String bucket)
boolean       s3.headBucket(String bucket)        // false if 404
void          s3.deleteBucket(String bucket)
```

### Object operations

```java
List<ObjectInfo>  s3.listObjects(String bucket)
List<ObjectInfo>  s3.listObjects(String bucket, String prefix, int maxKeys)
void              s3.putObject(String bucket, String key, byte[] data, String contentType)
InputStream       s3.getObject(String bucket, String key)   // caller closes stream
ObjectMetadata    s3.headObject(String bucket, String key)
void              s3.deleteObject(String bucket, String key)
```

### Multipart upload

```java
String                    s3.createMultipartUpload(String bucket, String key, String contentType)
String                    s3.uploadPart(String bucket, String key, String uploadId, int partNumber, byte[] data)  // returns ETag
List<PartInfo>            s3.listParts(String bucket, String key, String uploadId)
void                      s3.completeMultipartUpload(String bucket, String key, String uploadId, List<CompletedPart> parts)
void                      s3.abortMultipartUpload(String bucket, String key, String uploadId)
List<MultipartUploadInfo> s3.listMultipartUploads(String bucket)
```

### Value types

```java
record ObjectMetadata(long contentLength, String contentType, String etag)
record ObjectInfo(String key, long size, String etag)
record PartInfo(int partNumber, String etag, long size)
record MultipartUploadInfo(String key, String uploadId)
record CompletedPart(int partNumber, String etag)
```

---

## How it works

### 1. Transport — `java.net.http.HttpClient`

Each operation maps to a plain HTTP call against the RGW endpoint:

```
createBucket  →  PUT  /{bucket}
listObjects   →  GET  /{bucket}?list-type=2
putObject     →  PUT  /{bucket}/{key}
getObject     →  GET  /{bucket}/{key}
```

### 2. Auth — AWS Signature V4

Every request is signed with HMAC-SHA256. `AwsSigV4Signer` does this in 4 steps:

```
Step 1 — Canonical Request
  method + "\n" + uri-encoded-path + "\n" + sorted-query-string + "\n"
  + sorted-headers + "\n" + signed-header-names + "\n" + sha256(body)

Step 2 — String to Sign
  "AWS4-HMAC-SHA256\n" + datetime + "\n" + date/region/s3/aws4_request + "\n"
  + sha256(canonical-request)

Step 3 — Signing key (HMAC chain)
  kDate    = HMAC("AWS4" + secretKey, date)
  kRegion  = HMAC(kDate,              region)
  kService = HMAC(kRegion,            "s3")
  kSigning = HMAC(kService,           "aws4_request")

Step 4 — Authorization header
  AWS4-HMAC-SHA256 Credential=<accessKey>/<scope>,
    SignedHeaders=<names>, Signature=<hex(HMAC(kSigning, stringToSign))>
```

Ceph verifies by looking up `accessKey` → `secretKey` and recomputing the same signature.

Two edge cases handled:
- `host` header is managed by `HttpClient` — must not be set manually (throws `IllegalArgumentException`)
- Default ports (`:80`, `:443`) must be omitted from the `host` value used in signing

### 3. Parsing — `RgwXmlParser`

Ceph returns standard S3 XML. Parsed with `javax.xml.parsers.DocumentBuilder` (JDK built-in):

```
ListAllMyBucketsResult  →  parseBucketNames()
ListBucketResult        →  parseObjectList()
InitiateMultipartUploadResult  →  parseUploadId()
ListPartsResult         →  parsePartsList()
ListMultipartUploadsResult     →  parseMultipartUploads()
```

---

## Running tests

```bash
# Against local MicroCeph on port 80
mvn test -Dtest=RgwS3ClientTest -Dceph.endpoint=http://localhost:80

# Override credentials
mvn test -Dtest=RgwS3ClientTest \
  -Dceph.endpoint=http://localhost:80 \
  -Dceph.access-key=<key> \
  -Dceph.secret-key=<secret>
```

Default credentials in the test fall back to `application.properties` values.

### Test coverage

| Test | Operation |
|------|-----------|
| `listBuckets_containsTestBucket` | `GET /` |
| `headBucket_existingBucket_returnsTrue` | `HEAD /{bucket}` → 200 |
| `headBucket_nonExistingBucket_returnsFalse` | `HEAD /{bucket}` → 404 |
| `putObject_succeeds` | `PUT /{bucket}/{key}` |
| `listObjects_containsUploadedKey` | `GET /{bucket}?list-type=2` |
| `getObject_returnsCorrectContent` | `GET /{bucket}/{key}` |
| `headObject_returnsCorrectMetadata` | `HEAD /{bucket}/{key}` |
| `deleteObject_removesKey` | `DELETE /{bucket}/{key}` |
| `completeMultipartUpload_assemblesObject` | `POST ?uploads` → parts → `POST ?uploadId` |
| `listMultipartUploads_showsInProgressUpload` | `GET /{bucket}?uploads` |
| `abortMultipartUpload_removesUpload` | `DELETE /{bucket}/{key}?uploadId` |

---

## References

- [AWS Signature Version 4](https://docs.aws.amazon.com/IAM/latest/UserGuide/reference_aws-signing.html)
- [Amazon S3 REST API](https://docs.aws.amazon.com/AmazonS3/latest/API/Type_API_Reference.html)
- [Ceph RGW S3-compatible operations](https://docs.ceph.com/en/reef/radosgw/s3/)
- [Ceph MGR REST API](https://docs.ceph.com/en/reef/mgr/ceph_api/)
S3 REST API — từng operation

┌────────────────────┬──────────────────────────────────────────────────────────────────────────────────┐ │ Operation │ Doc │ ├────────────────────┼──────────────────────────────────────────────────────────────────────────────────┤ │ List Buckets │ https://docs.aws.amazon.com/AmazonS3/latest/API/API_ListBuckets.html │ ├────────────────────┼──────────────────────────────────────────────────────────────────────────────────┤ │ Create Bucket │ https://docs.aws.amazon.com/AmazonS3/latest/API/API_CreateBucket.html │ ├────────────────────┼──────────────────────────────────────────────────────────────────────────────────┤ │ List Objects V2 │ https://docs.aws.amazon.com/AmazonS3/latest/API/API_ListObjectsV2.html │ ├────────────────────┼──────────────────────────────────────────────────────────────────────────────────┤ │ Put Object │ https://docs.aws.amazon.com/AmazonS3/latest/API/API_PutObject.html │ ├────────────────────┼──────────────────────────────────────────────────────────────────────────────────┤ │ Get Object │ https://docs.aws.amazon.com/AmazonS3/latest/API/API_GetObject.html │ ├────────────────────┼──────────────────────────────────────────────────────────────────────────────────┤ │ Head Object │ https://docs.aws.amazon.com/AmazonS3/latest/API/API_HeadObject.html │ ├────────────────────┼──────────────────────────────────────────────────────────────────────────────────┤ │ Delete Object │ https://docs.aws.amazon.com/AmazonS3/latest/API/API_DeleteObject.html │ ├────────────────────┼──────────────────────────────────────────────────────────────────────────────────┤ │ Create Multipart │ https://docs.aws.amazon.com/AmazonS3/latest/API/API_CreateMultipartUpload.html │ ├────────────────────┼──────────────────────────────────────────────────────────────────────────────────┤ │ Upload Part │ https://docs.aws.amazon.com/AmazonS3/latest/API/API_UploadPart.html │ ├────────────────────┼──────────────────────────────────────────────────────────────────────────────────┤ │ Complete Multipart │ https://docs.aws.amazon.com/AmazonS3/latest/API/API_CompleteMultipartUpload.html │ ├────────────────────┼──────────────────────────────────────────────────────────────────────────────────┤ │ Abort Multipart │ https://docs.aws.amazon.com/AmazonS3/latest/API/API_AbortMultipartUpload.html │ └────────────────────┴──────────────────────────────────────────────────────────────────────────────────┘

Ceph side

S3 compatibility matrix: https://docs.ceph.com/en/reef/radosgw/s3/
Object operations: https://docs.ceph.com/en/reef/radosgw/s3/objectops/
Bucket operations: https://docs.ceph.com/en/reef/radosgw/s3/bucketops/
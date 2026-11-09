# AWS S3 to OpenShift Data Foundation (Ceph RGW): references and search notes

Compiled 2026-11-09 from web searches. Links came from search results and were not all opened.
ODF, Rook, and Ceph docs differ by version, so check the version you run.

Goal: stop using AWS services entirely, moving S3 data and everything that depends on S3 to ODF.

---

## 1. Architecture

```
App (S3 SDK) -> NooBaa MCG (optional S3 endpoint) -> Ceph RGW (S3-compatible) -> RADOS pools (BlueStore on OSDs)
```

| Concept | Reference |
|---|---|
| ODF components (Ceph, RGW, NooBaa operator) | [ODF architecture (4.20 PDF)](https://docs.redhat.com/en/documentation/red_hat_openshift_data_foundation/4.20/pdf/red_hat_openshift_data_foundation_architecture/Red_Hat_OpenShift_Data_Foundation-4.20-Red_Hat_OpenShift_Data_Foundation_architecture-en-US.pdf) |
| Ceph RGW overview | [Ceph Object Gateway](https://docs.ceph.com/en/reef/radosgw/) |
| RGW S3 API and supported features | [Ceph Object Gateway S3 API](https://docs.ceph.com/en/latest/radosgw/s3/) |
| Auth and ACLs (SigV4) | [Authentication and ACLs](https://docs.ceph.com/en/latest/radosgw/s3/authentication/) |
| ODF object docs (OBC, MCG, RGW endpoint) | [Managing hybrid and multicloud resources (latest)](https://docs.redhat.com/en/documentation/red_hat_openshift_data_foundation/latest/html-single/managing_hybrid_and_multicloud_resources/index) |

Search notes:
- RGW covers a large subset of the S3 REST API, not all of it.
- Most core operations are supported: lifecycle, versioning, tagging, multipart, copy, POST object, bucket and object ACLs.
- Bucket replication is partial (cross-zone only). Canned ACLs differ from AWS. `x-amz-id-2` is unsupported.
- SigV4 is supported, including UNSIGNED-PAYLOAD and streaming payload modes. Keystone can be the auth source.

## 2. Buckets, users, Kubernetes-native resources

| Concept | Reference |
|---|---|
| ObjectBucketClaim (Rook) | [Bucket Claim, Rook](https://rook.io/docs/rook/latest/Storage-Configuration/Object-Storage-RGW/ceph-object-bucket-claim/) |
| OBC in ODF | [ODF OBC chapter (4.8, old)](https://access.redhat.com/documentation/en-us/red_hat_openshift_container_storage/4.8/html/managing_hybrid_and_multicloud_resources/object-bucket-claim), or the latest guide in section 1 |
| CephObjectStore | [CephObjectStore CRD](https://rook.io/docs/rook/latest/CRDs/Object-Storage/ceph-object-store-crd/) |
| CephObjectStoreUser, object storage overview | [Object Storage Overview](https://rook.io/docs/rook/latest-release/Storage-Configuration/Object-Storage-RGW/object-storage/) |
| All CRD fields | [Rook CRD specification](https://rook.io/docs/rook/latest/CRDs/specification/) |
| COSI (newer alternative to OBC) | [Rook COSI](https://www.rook.io/docs/rook/latest-release/Storage-Configuration/Object-Storage-RGW/cosi/) |

Search notes:
- An OBC makes the provisioner create a bucket (or grant access to one). It also creates a Secret (credentials) and a ConfigMap (endpoint), both named after the OBC.
- ODF storage classes: `ocs-storagecluster-ceph-rgw` (RGW), `openshift-storage.noobaa.io` (MCG). In external mode the RGW class is `ocs-external-storagecluster-ceph-rgw`.
- An old 4.5-era note says the RGW OBC class only exists on fresh installs. Verify against your version.
- `CephObjectStoreUser` gives S3 credentials independent of any OBC. The keys land in a Secret.
- An OBC's `bucketOwner` can name an existing RGW account.
- Rook 1.17: extra OBC options (bucket policy, lifecycle) are disabled by default. Enable them with `ROOK_OBC_ALLOW_ADDITIONAL_CONFIG_FIELDS`. The same release added declarative credential rotation for `CephObjectStoreUser`.
- Rook blocks deleting a `CephObjectStore` that still holds buckets.

## 3. NooBaa / MCG

| Concept | Reference |
|---|---|
| MCG bucket replication | [MCG bucket replication (4.21)](https://docs.redhat.com/en/documentation/red_hat_openshift_data_foundation/4.21/html/managing_hybrid_and_multicloud_resources/multicloud_object_gateway_bucket_replication) |
| Mirroring, namespace buckets | [Mirroring data for hybrid and multicloud buckets (4.15)](https://docs.redhat.com/en/documentation/red_hat_openshift_data_foundation/4.15/html/managing_hybrid_and_multicloud_resources/mirroring-data-for-hybrid-and-multicloud-buckets) |
| Developer-oriented article | [ODF for developers and data scientists](https://developers.redhat.com/index%2Ephp/articles/2024/07/31/red-hat-openshift-data-foundation-developers-and-data-scientists) |

Search notes:
- The replication policy is a JSON file, attached when creating an OBC (MCG CLI) or set on a bucket class (placement and namespace classes). It can be edited in YAML later.
- A namespace bucket class uses a namespace policy of type `single` (one store for read and write) or `multi` (one write store, several read stores).
- Log-based replication for namespace buckets is available in the web console and via YAML.

## 4. Feature-by-feature: AWS vs RGW

| Feature | AWS side | Ceph side |
|---|---|---|
| Versioning, lifecycle, replication | [Lifecycle and other bucket configs](https://docs.aws.amazon.com/AmazonS3/latest/userguide/lifecycle-and-other-bucket-config.html), [Replication requirements](https://docs.aws.amazon.com/AmazonS3/latest/userguide/replication-and-other-bucket-configs.html) | [S3 API feature table](https://docs.ceph.com/en/latest/radosgw/s3/) |
| Storage classes, tiering | [Lifecycle transitions](https://docs.aws.amazon.com/AmazonS3/latest/userguide/lifecycle-and-other-bucket-config.html) | [Pool placement and storage classes](https://docs.ceph.com/en/latest/radosgw/placement/), [Cloud transition](https://docs.ceph.com/en/reef/radosgw/cloud-transition/) |
| Cross-region replication | [Replication requirements](https://docs.aws.amazon.com/AmazonS3/latest/userguide/replication-and-other-bucket-configs.html) | [Multi-Site](https://docs.ceph.com/en/latest/radosgw/multisite/), [Sync policy](https://docs.ceph.com/en/reef/radosgw/multisite-sync-policy/) |
| IAM roles, STS | (not searched) | [STS in Ceph](https://docs.ceph.com/en/latest/radosgw/STS/), [Session tags](https://docs.ceph.com/en/latest/radosgw/session-tags/) |
| Event notifications | [Event notification types and destinations](https://docs.aws.amazon.com/AmazonS3/latest/userguide/notification-how-to-event-types-and-destinations.html) | [Bucket notifications](https://docs.ceph.com/en/reef/radosgw/notifications/), [Compatibility](https://docs.ceph.com/en/latest/radosgw/s3-notification-compatibility/) |
| S3 Select | (not searched) | [Ceph S3 Select](https://docs.ceph.com/en/latest/radosgw/s3select/) |
| Encryption, KMS | (not searched) | [Vault integration](https://docs.ceph.com/en/latest/radosgw/vault/) |
| Very large buckets | n/a | [Dynamic bucket index resharding](https://docs.ceph.com/en/squid/radosgw/dynamicresharding/), [Bucket index internals](https://docs.ceph.com/en/latest/dev/radosgw/bucket_index/) |

Search notes:

**Versioning and lifecycle (AWS)**
- Lifecycle rules treat current and noncurrent versions separately.
- Expiration acts only on the current version. A delete marker as current version blocks further action on that object.
- Lifecycle skips objects whose replication status is PENDING or FAILED.
- Replication needs versioning enabled on both source and destination.
- Lifecycle transitions exclude objects under 128 KB by default.
- AWS events go to SNS, SQS, Lambda, or EventBridge. Delivery is at least once and unordered.

**Storage classes (Ceph)**
- Placement targets are defined in the zonegroup and mapped to pools in the zone. A bucket's placement target is chosen at creation and cannot be changed.
- Each target starts with a STANDARD class. You add a class to the zonegroup, then give the zone a pool with `radosgw-admin zone placement add`.
- Lifecycle `Transition` actions move objects between local storage classes.
- Cloud transition uses a `cloud-s3` or `cloud-s3-glacier` tier type pointing at a remote S3-compatible service. It is one-way. With `retain_head_object` false (the default) the local object is deleted after transition.
- Glacier-type classes require a restore on the cloud side first. Check release support.

**Multi-site (Ceph)**
- Hierarchy: realm, zonegroup, zone, plus a "period" with epochs.
- Each zonegroup needs a master zone. Secondary zones are added to it.
- Flow: `realm create`, `zonegroup create --master --default`, add zones, `period update --commit`.
- `radosgw-admin sync status` reports metadata sync and data sync separately.
- Sync policy can be set at zonegroup or bucket level.
- `ceph rgw realm bootstrap` can create realm, zonegroup, and zone together. A secondary cluster joins with a realm token.

**STS and IAM (Ceph)**
- Supported STS calls: AssumeRole, AssumeRoleWithWebIdentity, GetCallerIdentity.
- Callers need the `roles` capability (`radosgw-admin caps add --caps="roles=*"`). The IAM API handles CreateRole and PutRolePolicy.
- Session tags work only through AssumeRoleWithWebIdentity (exposed as `aws:PrincipalTag`).
- In Tentacle, STS Lite is deprecated. Tenant-level IAM is deprecated in favor of accounts, and role management is scoped under RGW Accounts.

**Notifications (Ceph)**
- Destinations: HTTP/S, Kafka, AMQP. There is no SNS, SQS, or Lambda.
- Ceph adds extensions such as single-notification delete and regex or tag filtering.

**S3 Select (Ceph)**
- Partial implementation, CSV objects only.

**Encryption (Ceph)**
- Vault is a KMS backend (`rgw crypt s3 kms backend`). Keys must be 256-bit, base64-encoded.
- Transit-engine keys must be exportable. RGW needs live Vault access to decrypt on read.

**Bucket index resharding (Ceph)**
- Dynamic resharding adds shards automatically when an index grows too big. The default ceiling is 1999 shards.
- Writes to the bucket are briefly blocked during a reshard, reads are not.
- Search did not cover how resharding interacts with encrypted buckets.

## 5. Sizing and operations

| Concept | Reference |
|---|---|
| Capacity, replicas, failure domains, networking | [ODF Planning your deployment (4.22 PDF)](https://docs.redhat.com/en/documentation/red_hat_openshift_data_foundation/4.22/pdf/planning_your_deployment/Red_Hat_OpenShift_Data_Foundation-4.22-Planning_your_deployment-en-US.pdf) |
| RGW metrics | [RGW metrics](https://docs.ceph.com/en/latest/radosgw/metrics/) |

Search notes:
- Failure domains (host, rack, zone) are detected from node labels.
- Add storage nodes in multiples of three, one per failure domain.
- Usable capacity is about raw divided by 3 with 3x replication. Example in the 4.22 docs: 1080 TiB raw gives about 360 TiB usable.
- Capacity alerts at 75% (near-full) and 85% (full) in an older release (4.16). Recovery is hard if capacity is exhausted.
- No more than about 12 devices per node is recommended.
- Cluster network carries 2x application write I/O plus recovery traffic. The 4.22 docs recommend a cluster network 2 to 3 times larger than the public network.

## 6. Data migration tooling and cost

| Concept | Reference |
|---|---|
| rclone with Ceph as provider | [rclone S3 backends](https://rclone.org/s3/) |
| S3 pricing, egress | [S3 pricing](https://aws.amazon.com/s3/pricing/) |
| Snowball | [Snowball pricing](https://aws.amazon.com/snowball/pricing/), [Snowball FAQ](https://aws.amazon.com/snowball/faqs/) |
| S3 FAQ | [Amazon S3 FAQs](https://aws.amazon.com/s3/faqs/) |

Search notes:
- rclone lists "Ceph Object Storage" as a provider. For S3-to-S3 efficiency, the docs suggest `--checksum` with `--update` and `--use-server-modtime`.
- Forum reports: `--fast-list`, `--s3-list-version 2` and `--s3-bucket-acl=bucket-owner-full-control` for specific problems. Those threads are old, so recheck the flags.
- Plain sync copies the latest version only. Old versions need explicit handling.
- Snowball import into S3 has no transfer charge. Exporting out of S3 bills regional egress plus device fees plus GETs. Example on the pricing page: 125 TB export from Seoul at $0.05/GB is $6,400 transfer plus a $3,200 device fee.
- The S3 FAQ mentions free data transfer out for customers moving off AWS, and says it excludes specialized services such as Snowball. Read the FAQ and ask your AWS account team before budgeting egress.

---

## Migration outline (summary)

0. **Inventory**: buckets, consumers, coupled AWS services (Lambda, SNS/SQS, KMS, CloudFront, Route 53, CloudWatch, CloudTrail), gap list.
1. **Design and size ODF**: capacity x replica factor plus headroom, failure domains, pool type, RGW and NooBaa choices, replacements for each AWS service.
2. **Deploy and harden**: StorageCluster, CephObjectStore, TLS route, KMS, quotas, monitoring, failure tests.
3. **Provision targets**: OBCs or `CephObjectStoreUser`, recreate versioning, policy, CORS, encryption. Apply lifecycle rules after the copy.
4. **App readiness**: configurable endpoint and credentials, `forcePathStyle`, presigned URLs, no hard-coded `amazonaws.com`.
5. **Data migration**: bulk copy, delta syncs, versions, metadata, checksum validation, egress cost.
6. **Cutover**: freeze writes, final sync, switch config, smoke test, keep AWS read-only for rollback.
7. **Decommission AWS**: watch access via CloudTrail, final archive if required, delete buckets, IAM users, KMS keys, confirm billing is clear.

Replacements for AWS services:

| AWS | Replacement |
|---|---|
| KMS | HashiCorp Vault or another supported KMS |
| SNS / SQS / Lambda | RGW bucket notifications to Kafka or AMQP, consumed by your own service |
| CloudFront | OpenShift Route/Ingress plus your CDN of choice |
| CloudWatch / CloudTrail | Prometheus and Grafana, plus the RGW ops log |
| IAM roles | RGW users, per-app keys from OBC Secrets, or limited STS |
| Route 53 / ACM | Internal DNS and cert-manager |

## Not searched
AWS IAM, KMS, CloudFront, CloudTrail docs. cert-manager, Kafka consumers, Prometheus and Grafana setup. ODF's own RGW bucket policy and Object Lock docs (the Ceph S3 feature table covers them at the API level).

package com.example.dbperformtest.ceph.parser;

import com.example.dbperformtest.ceph.parser.RgwXmlParser.MultipartUploadInfo;
import com.example.dbperformtest.ceph.parser.RgwXmlParser.ObjectInfo;
import com.example.dbperformtest.ceph.parser.RgwXmlParser.PartInfo;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RgwXmlParserTest {

    // ── parseBucketNames ──────────────────────────────────────────────────────

    @Test
    void parseBucketNames_returnsBucketNames() {
        byte[] xml = xml("""
                <?xml version="1.0" encoding="UTF-8"?>
                <ListAllMyBucketsResult>
                  <Owner><ID>owner1</ID><DisplayName>owner</DisplayName></Owner>
                  <Buckets>
                    <Bucket><Name>alpha</Name><CreationDate>2024-01-01T00:00:00.000Z</CreationDate></Bucket>
                    <Bucket><Name>beta</Name><CreationDate>2024-01-02T00:00:00.000Z</CreationDate></Bucket>
                  </Buckets>
                </ListAllMyBucketsResult>
                """);

        List<String> names = RgwXmlParser.parseBucketNames(xml);
        assertThat(names).containsExactly("alpha", "beta");
    }

    @Test
    void parseBucketNames_emptyBuckets_returnsEmptyList() {
        byte[] xml = xml("""
                <?xml version="1.0" encoding="UTF-8"?>
                <ListAllMyBucketsResult>
                  <Owner><ID>owner1</ID><DisplayName>owner</DisplayName></Owner>
                  <Buckets/>
                </ListAllMyBucketsResult>
                """);

        assertThat(RgwXmlParser.parseBucketNames(xml)).isEmpty();
    }

    // ── parseObjectList ───────────────────────────────────────────────────────

    @Test
    void parseObjectList_returnsObjectInfoList() {
        byte[] xml = xml("""
                <?xml version="1.0" encoding="UTF-8"?>
                <ListBucketResult>
                  <Contents>
                    <Key>dir/file1.txt</Key>
                    <Size>1024</Size>
                    <ETag>"abc123"</ETag>
                  </Contents>
                  <Contents>
                    <Key>dir/file2.txt</Key>
                    <Size>2048</Size>
                    <ETag>"def456"</ETag>
                  </Contents>
                </ListBucketResult>
                """);

        List<ObjectInfo> objects = RgwXmlParser.parseObjectList(xml);

        assertThat(objects).hasSize(2);

        ObjectInfo first = objects.get(0);
        assertThat(first.key()).isEqualTo("dir/file1.txt");
        assertThat(first.size()).isEqualTo(1024L);
        // ETag quotes must be stripped
        assertThat(first.etag()).isEqualTo("abc123");

        ObjectInfo second = objects.get(1);
        assertThat(second.key()).isEqualTo("dir/file2.txt");
        assertThat(second.size()).isEqualTo(2048L);
        assertThat(second.etag()).isEqualTo("def456");
    }

    @Test
    void parseObjectList_emptyBucket_returnsEmptyList() {
        byte[] xml = xml("""
                <?xml version="1.0" encoding="UTF-8"?>
                <ListBucketResult>
                  <Name>empty-bucket</Name>
                  <KeyCount>0</KeyCount>
                </ListBucketResult>
                """);

        assertThat(RgwXmlParser.parseObjectList(xml)).isEmpty();
    }

    // ── parseUploadId ─────────────────────────────────────────────────────────

    @Test
    void parseUploadId_extractsUploadId() {
        byte[] xml = xml("""
                <?xml version="1.0" encoding="UTF-8"?>
                <InitiateMultipartUploadResult>
                  <Bucket>my-bucket</Bucket>
                  <Key>my-object</Key>
                  <UploadId>VXBsb2FkIElEIGZvciA2aWWpbmcncyBteS1tb3ZpZS5tMnRzIHVwbG9hZA</UploadId>
                </InitiateMultipartUploadResult>
                """);

        assertThat(RgwXmlParser.parseUploadId(xml))
                .isEqualTo("VXBsb2FkIElEIGZvciA2aWWpbmcncyBteS1tb3ZpZS5tMnRzIHVwbG9hZA");
    }

    // ── parsePartsList ────────────────────────────────────────────────────────

    @Test
    void parsePartsList_returnsPartInfoList() {
        byte[] xml = xml("""
                <?xml version="1.0" encoding="UTF-8"?>
                <ListPartsResult>
                  <Part>
                    <PartNumber>1</PartNumber>
                    <ETag>"aaa111"</ETag>
                    <Size>5242880</Size>
                  </Part>
                  <Part>
                    <PartNumber>2</PartNumber>
                    <ETag>"bbb222"</ETag>
                    <Size>1024</Size>
                  </Part>
                </ListPartsResult>
                """);

        List<PartInfo> parts = RgwXmlParser.parsePartsList(xml);

        assertThat(parts).hasSize(2);

        PartInfo p1 = parts.get(0);
        assertThat(p1.partNumber()).isEqualTo(1);
        assertThat(p1.etag()).isEqualTo("aaa111");
        assertThat(p1.size()).isEqualTo(5242880L);

        PartInfo p2 = parts.get(1);
        assertThat(p2.partNumber()).isEqualTo(2);
        assertThat(p2.etag()).isEqualTo("bbb222");
        assertThat(p2.size()).isEqualTo(1024L);
    }

    @Test
    void parsePartsList_noParts_returnsEmptyList() {
        byte[] xml = xml("""
                <?xml version="1.0" encoding="UTF-8"?>
                <ListPartsResult>
                  <Bucket>my-bucket</Bucket>
                  <Key>my-object</Key>
                </ListPartsResult>
                """);

        assertThat(RgwXmlParser.parsePartsList(xml)).isEmpty();
    }

    // ── parseMultipartUploads ─────────────────────────────────────────────────

    @Test
    void parseMultipartUploads_returnsUploadInfoList() {
        byte[] xml = xml("""
                <?xml version="1.0" encoding="UTF-8"?>
                <ListMultipartUploadsResult>
                  <Upload>
                    <Key>video.mp4</Key>
                    <UploadId>upload-id-1</UploadId>
                  </Upload>
                  <Upload>
                    <Key>archive.zip</Key>
                    <UploadId>upload-id-2</UploadId>
                  </Upload>
                </ListMultipartUploadsResult>
                """);

        List<MultipartUploadInfo> uploads = RgwXmlParser.parseMultipartUploads(xml);

        assertThat(uploads).hasSize(2);

        MultipartUploadInfo u1 = uploads.get(0);
        assertThat(u1.key()).isEqualTo("video.mp4");
        assertThat(u1.uploadId()).isEqualTo("upload-id-1");

        MultipartUploadInfo u2 = uploads.get(1);
        assertThat(u2.key()).isEqualTo("archive.zip");
        assertThat(u2.uploadId()).isEqualTo("upload-id-2");
    }

    @Test
    void parseMultipartUploads_noUploads_returnsEmptyList() {
        byte[] xml = xml("""
                <?xml version="1.0" encoding="UTF-8"?>
                <ListMultipartUploadsResult>
                  <Bucket>my-bucket</Bucket>
                </ListMultipartUploadsResult>
                """);

        assertThat(RgwXmlParser.parseMultipartUploads(xml)).isEmpty();
    }

    // ── Error handling ────────────────────────────────────────────────────────

    @Test
    void parse_malformedXml_throwsRuntimeException() {
        byte[] bad = "not xml at all".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> RgwXmlParser.parseBucketNames(bad))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to parse XML response");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static byte[] xml(String s) {
        return s.strip().getBytes(StandardCharsets.UTF_8);
    }
}

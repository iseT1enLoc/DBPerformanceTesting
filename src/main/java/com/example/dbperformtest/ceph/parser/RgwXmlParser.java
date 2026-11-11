package com.example.dbperformtest.ceph.parser;

import com.example.dbperformtest.ceph.messages.BucketItem;
import com.example.dbperformtest.ceph.messages.Item;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

public final class RgwXmlParser {

    private RgwXmlParser() {}

    // ── Buckets ───────────────────────────────────────────────────────────────

    public static List<BucketItem> parseBuckets(byte[] xml) {
        NodeList buckets = parse(xml).getElementsByTagName("Bucket");
        List<BucketItem> list = new ArrayList<>();
        for (int i = 0; i < buckets.getLength(); i++) {
            Element el = (Element) buckets.item(i);
            String name     = text(el, "Name");
            String created  = text(el, "CreationDate");
            list.add(new BucketItem(name, parseDate(created)));
        }
        return list;
    }

    // ── Objects ───────────────────────────────────────────────────────────────

    public static ListPage parseListPage(byte[] xml) {
        Document doc = parse(xml);
        boolean isTruncated = "true".equalsIgnoreCase(
                text(doc.getDocumentElement(), "IsTruncated"));
        String nextToken = text(doc.getDocumentElement(), "NextContinuationToken");

        // Regular objects from <Contents>
        NodeList contents = doc.getElementsByTagName("Contents");
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < contents.getLength(); i++) {
            Element el  = (Element) contents.item(i);
            String key  = text(el, "Key");
            long   size = Long.parseLong(text(el, "Size"));
            String etag = text(el, "ETag").replace("\"", "");
            String lm   = text(el, "LastModified");
            items.add(new Item(key, size, etag, parseDate(lm), false));
        }

        // "Directory" entries from <CommonPrefixes> (non-recursive listing)
        NodeList prefixes = doc.getElementsByTagName("CommonPrefixes");
        for (int i = 0; i < prefixes.getLength(); i++) {
            String prefix = text((Element) prefixes.item(i), "Prefix");
            items.add(new Item(prefix, 0, null, null, true));
        }

        return new ListPage(items, isTruncated, nextToken.isEmpty() ? null : nextToken);
    }

    // ── Multipart ─────────────────────────────────────────────────────────────

    public static String parseUploadId(byte[] xml) {
        return text(parse(xml).getDocumentElement(), "UploadId");
    }

    public static List<PartInfo> parsePartsList(byte[] xml) {
        NodeList nodes = parse(xml).getElementsByTagName("Part");
        List<PartInfo> list = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            Element el  = (Element) nodes.item(i);
            int partNum = Integer.parseInt(text(el, "PartNumber"));
            String etag = text(el, "ETag").replace("\"", "");
            long   size = Long.parseLong(text(el, "Size"));
            list.add(new PartInfo(partNum, etag, size));
        }
        return list;
    }

    public static List<MultipartUploadInfo> parseMultipartUploads(byte[] xml) {
        NodeList nodes = parse(xml).getElementsByTagName("Upload");
        List<MultipartUploadInfo> list = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            Element el = (Element) nodes.item(i);
            list.add(new MultipartUploadInfo(text(el, "Key"), text(el, "UploadId")));
        }
        return list;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static Document parse(byte[] xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse XML response", e);
        }
    }

    private static String text(Element el, String tag) {
        NodeList nodes = el.getElementsByTagName(tag);
        return nodes.getLength() > 0 ? nodes.item(0).getTextContent().trim() : "";
    }

    private static ZonedDateTime parseDate(String s) {
        if (s == null || s.isEmpty()) return null;
        try { return ZonedDateTime.parse(s); } catch (Exception e) { return null; }
    }

    // ── Value types ───────────────────────────────────────────────────────────

    public record ListPage(List<Item> items, boolean isTruncated, String nextContinuationToken) {}
    public record PartInfo(int partNumber, String etag, long size) {}
    public record MultipartUploadInfo(String key, String uploadId) {}
}

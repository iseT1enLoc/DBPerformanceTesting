package com.example.dbperformtest.ceph.parser;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

public final class RgwXmlParser {

    private RgwXmlParser() {}

    public static List<String> parseBucketNames(byte[] xml) {
        // ListAllMyBucketsResult has both <Bucket><Name> and <Owner><DisplayName>
        // — only collect Name from inside <Bucket> elements
        NodeList buckets = parse(xml).getElementsByTagName("Bucket");
        List<String> names = new ArrayList<>();
        for (int i = 0; i < buckets.getLength(); i++) {
            names.add(text((Element) buckets.item(i), "Name"));
        }
        return names;
    }

    public static List<ObjectInfo> parseObjectList(byte[] xml) {
        NodeList nodes = parse(xml).getElementsByTagName("Contents");
        List<ObjectInfo> list = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            Element el   = (Element) nodes.item(i);
            String  key  = text(el, "Key");
            long    size = Long.parseLong(text(el, "Size"));
            String  etag = text(el, "ETag").replace("\"", "");
            list.add(new ObjectInfo(key, size, etag));
        }
        return list;
    }

    public static String parseUploadId(byte[] xml) {
        return text(parse(xml).getDocumentElement(), "UploadId");
    }

    public static List<PartInfo> parsePartsList(byte[] xml) {
        NodeList nodes = parse(xml).getElementsByTagName("Part");
        List<PartInfo> list = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            Element el      = (Element) nodes.item(i);
            int     partNum = Integer.parseInt(text(el, "PartNumber"));
            String  etag    = text(el, "ETag").replace("\"", "");
            long    size    = Long.parseLong(text(el, "Size"));
            list.add(new PartInfo(partNum, etag, size));
        }
        return list;
    }

    public static List<MultipartUploadInfo> parseMultipartUploads(byte[] xml) {
        NodeList nodes = parse(xml).getElementsByTagName("Upload");
        List<MultipartUploadInfo> list = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            Element el       = (Element) nodes.item(i);
            String  key      = text(el, "Key");
            String  uploadId = text(el, "UploadId");
            list.add(new MultipartUploadInfo(key, uploadId));
        }
        return list;
    }

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

    public record ObjectInfo(String key, long size, String etag) {}
    public record PartInfo(int partNumber, String etag, long size) {}
    public record MultipartUploadInfo(String key, String uploadId) {}
}

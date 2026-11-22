package com.example.dbperformtest.scylla;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Properties;

public final class ScyllaConfig {

    private static final Properties PROPS = loadProps();

    private static Properties loadProps() {
        Properties p = new Properties();
        try (InputStream in = ScyllaConfig.class.getClassLoader()
                .getResourceAsStream("application.properties")) {
            if (in != null) p.load(in);
        } catch (IOException ignored) {}
        return p;
    }

    private static String get(String key, String defaultValue) {
        // system property (-Dkey=value) overrides application.properties
        String sys = System.getProperty(key);
        if (sys != null) return sys;
        return PROPS.getProperty(key, defaultValue);
    }

    private ScyllaConfig() {}

    public static DynamoDbClient createClient() {
        String endpoint  = get("scylla.endpoint",   "http://localhost:8000");
        String accessKey = get("scylla.access-key", "any");
        String secretKey = get("scylla.secret-key", "any");
        System.out.printf("Creating DynamoDbClient with endpoint=%s, accessKey=%s, secretKey=%s%n",
                endpoint, accessKey, secretKey);
        return DynamoDbClient.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .httpClient(UrlConnectionHttpClient.create())
                .build();
    }
}

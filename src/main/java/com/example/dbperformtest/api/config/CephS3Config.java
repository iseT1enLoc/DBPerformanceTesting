package com.example.dbperformtest.api.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;

@Configuration
public class CephS3Config {

    @Value("${ceph.s3.endpoint:http://localhost:80}")
    private String endpoint;

    @Value("${ceph.s3.access-key:admin}")
    private String accessKey;

    @Value("${ceph.s3.secret-key:admin}")
    private String secretKey;

    @Bean
    public S3Client cephS3Client() {
        return S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                // MicroCeph is typically deployed as a single-region cluster;
                // the region value is arbitrary but must be non-null for the SDK.
                .region(Region.US_EAST_1)
                // Required so path-style URLs (http://host/bucket/key) are used
                // instead of virtual-hosted style (http://bucket.host/key).
                .forcePathStyle(true)
                .build();
    }
}

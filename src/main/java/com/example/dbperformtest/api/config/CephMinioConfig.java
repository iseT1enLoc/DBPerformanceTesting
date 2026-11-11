package com.example.dbperformtest.api.config;

import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CephMinioConfig {

    @Value("${ceph.s3.endpoint:http://localhost:80}")
    private String endpoint;

    @Value("${ceph.s3.access-key:admin}")
    private String accessKey;

    @Value("${ceph.s3.secret-key:admin}")
    private String secretKey;

    @Bean
    public MinioClient minioClient() {
        return MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
    }
}

package com.example.dbperformtest.config;

import com.oracle.nosql.spring.data.config.AbstractNosqlConfiguration;
import com.oracle.nosql.spring.data.config.NosqlDbConfig;
import com.oracle.nosql.spring.data.repository.config.EnableNosqlRepositories;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Collection;
import java.util.List;

@Configuration
@EnableNosqlRepositories(basePackages = "com.example.dbperformtest.repository")
public class NosqlConfig extends AbstractNosqlConfiguration {

    @Bean
    public NosqlDbConfig nosqlDBConfig() {
        return NosqlDbConfig.createProxyConfig("http://localhost:8180");
    }

    @Override
    protected Collection<String> getMappingBasePackages() {
        return List.of("com.example.dbperformtest.model");
    }
}

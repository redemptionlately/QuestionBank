package com.allen.questionbank.common;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;
import javax.sql.DataSource;
import java.util.Map;

/**
 * 读写分离装配（@Profile("read-replica") 隔离：默认环境与既有测试完全不受影响）。
 *
 * <p>结构：Hikari(master) + Hikari(slave) → ReplicationRoutingDataSource → LazyConnectionDataSourceProxy(@Primary)。
 * 从库侧开 super_read_only：任何错误路由到从库的写操作直接报错，路由错误藏不住。</p>
 */
@Configuration
@Profile("read-replica")
public class ReadReplicaDataSourceConfig {

    @Bean
    HikariDataSource masterDataSource(DataSourceProperties properties,
                                      @Value("${app.replication.master-url}") String masterUrl) {
        return hikariOf(masterUrl, properties.getUsername(), properties.getPassword(), "rw-master-pool");
    }

    @Bean
    HikariDataSource slaveDataSource(DataSourceProperties properties,
                                     @Value("${app.replication.slave-url}") String slaveUrl) {
        return hikariOf(slaveUrl, properties.getUsername(), properties.getPassword(), "ro-slave-pool");
    }

    @Bean
    ReplicationRoutingDataSource routingDataSource(HikariDataSource masterDataSource, HikariDataSource slaveDataSource) {
        ReplicationRoutingDataSource routing = new ReplicationRoutingDataSource();
        routing.setTargetDataSources(Map.of(
                ReplicationRoutingDataSource.WRITE, masterDataSource,
                ReplicationRoutingDataSource.READ, slaveDataSource));
        routing.setDefaultTargetDataSource(masterDataSource);
        return routing;
    }

    /**
     * @Primary 且加 Lazy 代理：Spring Boot 的 DataSource 自动配置因子存在 DataSource bean 而 back off；
     * JPA/Flyway/JdbcTemplate 都拿到它。Lazy 的必要性见 ReplicationRoutingDataSource 的类注释。
     */
    @Bean
    @Primary
    DataSource dataSource(ReplicationRoutingDataSource routingDataSource) {
        return new LazyConnectionDataSourceProxy(routingDataSource);
    }

    private HikariDataSource hikariOf(String jdbcUrl, String username, String password, String poolName) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        config.setPoolName(poolName);
        config.setMaximumPoolSize(5);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(5000);
        return new HikariDataSource(config);
    }
}

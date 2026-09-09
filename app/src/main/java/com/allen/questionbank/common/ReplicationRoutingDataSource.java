package com.allen.questionbank.common;

import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 读写分离路由：只读事务走从库，其余走主库。
 *
 * <p>路由依据是 Spring 事务同步器的 read-only 标志——{@code @Transactional(readOnly = true)}
 * 在 doBegin 末尾就会把它设上；配合外层的 {@code LazyConnectionDataSourceProxy}
 * 把真实连接获取推迟到第一条 SQL 执行时，保证判断时标志已就位（否则 Hibernate
 * 在事务 begin 阶段就抓连接，路由永远落主库——这是本方案最容易踩错的时序点）。</p>
 */
public class ReplicationRoutingDataSource extends AbstractRoutingDataSource {

    public static final String WRITE = "write";
    public static final String READ = "read";

    @Override
    protected Object determineCurrentLookupKey() {
        return TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                ? READ
                : WRITE;
    }
}

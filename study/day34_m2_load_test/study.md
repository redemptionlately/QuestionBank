# 必须会背会写

- 吞吐是单位时间完成请求数，响应时间是一次请求从开始到响应的耗时；P50/P95/P99 是排序后的分位点，warmup 用于排除冷启动
- 压测条件必须固定 commit、机器、JDK/JVM 参数、数据库版本、数据量、请求体、并发模型、连接池、持续时间和采样方式
- 同步 API 的“接受任务耗时”与异步任务的“最终完成耗时”是两条指标链，不能混为一个 latency
- 开环负载按计划速率发请求，闭环负载按前一个响应返回后再发；两者在高延迟时产生不同压力
- Little 定律在稳定系统中近似为 `L = λW`：并发中的请求数约等于吞吐 `λ` 乘平均响应时间 `W`；它要求稳定且指标口径一致
- 外部源码索引（会背会写）：[k6 options](https://grafana.com/docs/k6/latest/using-k6/k6-options/) 的 vus/duration/warmup；[JMeter Thread Group](https://jmeter.apache.org/usermanual/test_plan.html) 的并发模型

# 必须理解

- 负载接近容量时排队延迟非线性上升；增加线程可能放大数据库锁、连接池等待和上下文切换
- 压测结果只绑定当前环境和输入；生产容量需要真实部署、数据分布、SLO 和故障预算
- 外部源码索引（必须理解）：[k6 metrics](https://grafana.com/docs/k6/latest/using-k6/metrics/) 的 trend/rate/counter 与分位数统计
- 官方：[k6](https://grafana.com/docs/k6/latest/)、[JMeter](https://jmeter.apache.org/usermanual/)

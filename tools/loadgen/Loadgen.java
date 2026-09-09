import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 开环压测器：按预设到达速率发请求，不等上一个返回（open-loop），比闭环更接近真实流量。
 * 用法：
 *   java Loadgen.java --url http://127.0.0.1:8080/api/papers/published \
 *     --method GET --token-file target/token.txt --rps 200 --warmup 10 --duration 60 --out out.json
 */
public class Loadgen {

    record Sample(long latencyNanos, int status) {}

    public static void main(String[] args) throws Exception {
        Args a = Args.parse(args);
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();

        String token = a.tokenFile == null ? null : Files.readString(Path.of(a.tokenFile)).trim();
        HttpRequest request = buildRequest(a, token);

        ConcurrentLinkedQueue<Sample> samples = new ConcurrentLinkedQueue<>();
        AtomicLong sent = new AtomicLong();
        AtomicLong failed = new AtomicLong();
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicBoolean warmedUp = new AtomicBoolean(false);

        System.out.println("[loadgen] target=" + a.url + " method=" + a.method + " rps=" + a.rps
                + " warmup=" + a.warmupSeconds + "s duration=" + a.durationSeconds + "s open-loop");

        long periodMicros = 1_000_000L / a.rps;
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        Thread producer = new Thread(() -> {
            long nextNanos = System.nanoTime();
            while (running.get()) {
                long now = System.nanoTime();
                if (now < nextNanos) {
                    long sleepMillis = Math.max(0, (nextNanos - now) / 1_000_000L);
                    try {
                        Thread.sleep(Math.min(sleepMillis, 5));
                    } catch (InterruptedException e) {
                        return;
                    }
                    continue;
                }
                if (nextNanos - now > 1_000_000_000L) {
                    nextNanos = now;
                }
                nextNanos += periodMicros * 1_000L;
                long start = System.nanoTime();
                sent.incrementAndGet();
                boolean counting = warmedUp.get();
                try {
                    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                    if (counting) {
                        samples.add(new Sample(System.nanoTime() - start, response.statusCode()));
                    }
                    if (response.statusCode() >= 500) {
                        failed.incrementAndGet();
                    }
                } catch (IOException | InterruptedException e) {
                    failed.incrementAndGet();
                    if (counting) {
                        samples.add(new Sample(System.nanoTime() - start, 0));
                    }
                }
            }
        });
        producer.setDaemon(true);

        scheduler.schedule(() -> {
            warmedUp.set(true);
            System.out.println("[loadgen] warmup finished, start measuring");
        }, a.warmupSeconds, TimeUnit.SECONDS);

        producer.start();
        Thread.sleep((a.warmupSeconds + a.durationSeconds) * 1000L);
        running.set(false);
        producer.join(5000);
        scheduler.shutdownNow();

        report(a, samples, sent.get(), failed.get());
    }

    private static HttpRequest buildRequest(Args a, String token) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(a.url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json");
        if (token != null && !token.isBlank()) {
            builder.header("Authorization", "Bearer " + token);
        }
        if (a.method.equals("GET")) {
            builder.GET();
        } else {
            builder.POST(HttpRequest.BodyPublishers.ofString(a.body == null ? "" : a.body, StandardCharsets.UTF_8));
        }
        return builder.build();
    }

    private static void report(Args a, ConcurrentLinkedQueue<Sample> samples, long sent, long failed) throws IOException {
        List<Long> latencies = new ArrayList<>();
        long count = 0;
        long errors = 0;
        for (Sample s : samples) {
            count++;
            latencies.add(s.latencyNanos / 1_000_000L);
            if (s.status == 0 || s.status >= 400) {
                errors++;
            }
        }
        Collections.sort(latencies);
        double seconds = a.durationSeconds;
        double rps = count / seconds;
        double errorRate = count == 0 ? 0 : (double) errors / count * 100;

        System.out.println();
        System.out.println("=== 压测结果（开环，warmup 后统计）===");
        System.out.printf(Locale.ROOT, "目标 RPS        : %d%n", a.rps);
        System.out.printf(Locale.ROOT, "实测吞吐 RPS    : %.1f%n", rps);
        System.out.printf(Locale.ROOT, "样本数          : %d (发送 %d)%n", count, sent);
        System.out.printf(Locale.ROOT, "错误数 / 错误率 : %d / %.2f%%%n", errors, errorRate);
        System.out.printf(Locale.ROOT, "P50             : %d ms%n", percentile(latencies, 50));
        System.out.printf(Locale.ROOT, "P95             : %d ms%n", percentile(latencies, 95));
        System.out.printf(Locale.ROOT, "P99             : %d ms%n", percentile(latencies, 99));
        System.out.printf(Locale.ROOT, "P999            : %d ms%n", percentile(latencies, 99.9));
        System.out.printf(Locale.ROOT, "Max             : %d ms%n", latencies.isEmpty() ? 0 : latencies.get(latencies.size() - 1));

        if (a.out != null) {
            StringBuilder json = new StringBuilder();
            json.append("{\"target\":\"").append(a.url).append("\",\"method\":\"").append(a.method)
                    .append("\",\"targetRps\":").append(a.rps)
                    .append(",\"measuredRps\":").append(String.format(Locale.ROOT, "%.2f", rps))
                    .append(",\"samples\":").append(count)
                    .append(",\"errors\":").append(errors)
                    .append(",\"errorRatePct\":").append(String.format(Locale.ROOT, "%.2f", errorRate))
                    .append(",\"p50Ms\":").append(percentile(latencies, 50))
                    .append(",\"p95Ms\":").append(percentile(latencies, 95))
                    .append(",\"p99Ms\":").append(percentile(latencies, 99))
                    .append(",\"p999Ms\":").append(percentile(latencies, 99.9))
                    .append(",\"timestamp\":\"").append(Instant.now()).append("\"}");
            Files.writeString(Path.of(a.out), json.toString(), StandardCharsets.UTF_8);
            System.out.println("原始 JSON 已写入: " + a.out);
        }
    }

    private static long percentile(List<Long> sorted, double pct) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int index = (int) Math.ceil(pct / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    static final class Args {
        String url = "http://127.0.0.1:8080/api/papers/published";
        String method = "GET";
        String body;
        String tokenFile;
        int rps = 100;
        int warmupSeconds = 10;
        int durationSeconds = 60;
        String out;

        static Args parse(String[] args) {
            Args a = new Args();
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--url" -> a.url = args[++i];
                    case "--method" -> a.method = args[++i].toUpperCase(Locale.ROOT);
                    case "--body" -> a.body = args[++i];
                    case "--token-file" -> a.tokenFile = args[++i];
                    case "--rps" -> a.rps = Integer.parseInt(args[++i]);
                    case "--warmup" -> a.warmupSeconds = Integer.parseInt(args[++i]);
                    case "--duration" -> a.durationSeconds = Integer.parseInt(args[++i]);
                    case "--out" -> a.out = args[++i];
                    default -> throw new IllegalArgumentException("未知参数: " + args[i] + " 全部参数: " + Arrays.toString(args));
                }
            }
            return a;
        }
    }
}

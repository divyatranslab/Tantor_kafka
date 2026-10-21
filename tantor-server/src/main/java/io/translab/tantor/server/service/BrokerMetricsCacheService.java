package io.translab.tantor.server.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.translab.tantor.server.domain.Cluster;
import io.translab.tantor.server.domain.ExternalCluster;
import io.translab.tantor.server.domain.ClusterServiceAssignment;
import io.translab.tantor.server.domain.Host;
import io.translab.tantor.server.dto.BrokerSummaryDto;
import io.translab.tantor.server.config.MonitoringProperties;
import io.translab.tantor.server.repository.HostRepository;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class BrokerMetricsCacheService {

    private final HostRepository hostRepository;
    private final KafkaAdminService kafkaAdminService;
    private final ExternalClusterService externalClusterService;
    private final HostStatusService hostStatusService;
    private final ObjectMapper objectMapper;
    private final MonitoringProperties monitoringProperties;
    private final RestTemplate restTemplate = new RestTemplateBuilder()
            .setConnectTimeout(Duration.ofSeconds(2))
            .setReadTimeout(Duration.ofSeconds(2))
            .build();

    private final Map<UUID, CachedBrokers> cache = new ConcurrentHashMap<>();
    private final Map<UUID, Object> refreshLocks = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 10000;
    private static final int METRICS_FETCH_THREADS = 8;
    private static final int METRICS_FETCH_QUEUE_CAPACITY = 64;
    private static final long METRICS_FETCH_THREAD_KEEP_ALIVE_SECONDS = 30;

    private final ThreadPoolExecutor metricsExecutor = createMetricsExecutor();

    public List<BrokerSummaryDto> getBrokerSummaries(Cluster cluster) {
        List<ClusterServiceAssignment> services = cluster.getServices() == null
                ? List.of()
                : cluster.getServices();
        return getBrokerSummaries(
                cluster,
                () -> services,
                currentServices -> loadHostsById(currentServices),
                null);
    }

    public List<BrokerSummaryDto> getBrokerSummaries(
            Cluster cluster,
            List<ClusterServiceAssignment> services,
            Map<String, Host> hostsById
    ) {
        List<ClusterServiceAssignment> safeServices = services == null ? List.of() : services;
        Map<String, Host> safeHosts = hostsById == null ? Map.of() : hostsById;
        List<BrokerInput> inputs = safeServices.stream()
                .filter(service -> isKafkaNodeRole(service.getRole()))
                .map(service -> brokerInput(service, safeHosts.get(service.getHostId())))
                .toList();
        return getBrokerSummaries(
                cluster,
                () -> safeServices,
                ignored -> safeHosts,
                inputs);
    }

    private List<BrokerSummaryDto> getBrokerSummaries(
            Cluster cluster,
            Supplier<List<ClusterServiceAssignment>> servicesSupplier,
            Function<List<ClusterServiceAssignment>, Map<String, Host>> hostsResolver,
            List<BrokerInput> inputKey
    ) {
        synchronized (refreshLock(cluster.getId())) {
            CachedBrokers cached = cache.get(cluster.getId());
            long now = System.currentTimeMillis();

            if (cached != null && !cached.external && (now - cached.timestamp < CACHE_TTL_MS)
                    && Objects.equals(cached.inputKey, inputKey)) {
                return cached.brokers;
            }

            long deadlineNanos = System.nanoTime()
                    + TimeUnit.SECONDS.toNanos(monitoringProperties.getBrokerRefreshTimeoutSeconds());

            // Cache miss or expired, fetch asynchronously
            List<ClusterServiceAssignment> services = servicesSupplier.get();
            if (services == null) {
                services = List.of();
            }
            Map<String, Host> hostsById = hostsResolver.apply(services);
            List<Future<IndexedBroker>> futures = new ArrayList<>();
            CompletionService<IndexedBroker> completedFetches =
                    new ExecutorCompletionService<>(metricsExecutor);
            for (ClusterServiceAssignment service : services) {
                if (System.nanoTime() >= deadlineNanos) {
                    log.warn("Broker metrics refresh deadline reached before all nodes were submitted for cluster {}",
                            cluster.getId());
                    break;
                }
                if (!isKafkaNodeRole(service.getRole())) {
                    continue;
                }
                Host host = hostsById.get(service.getHostId());
                try {
                    int resultIndex = futures.size();
                    futures.add(completedFetches.submit(
                            () -> new IndexedBroker(resultIndex, fetchMetricsForBroker(service, host))));
                } catch (RejectedExecutionException e) {
                    log.warn("Broker metrics capacity reached for cluster {}; skipping node {}",
                            cluster.getId(), service.getNodeId());
                }
            }

            BrokerSummaryDto[] brokerResults = new BrokerSummaryDto[futures.size()];
            for (int completedCount = 0; completedCount < futures.size(); completedCount++) {
                try {
                    long remainingNanos = deadlineNanos - System.nanoTime();
                    if (remainingNanos <= 0) {
                        throw new TimeoutException("Broker metrics refresh deadline exceeded");
                    }
                    Future<IndexedBroker> completed = completedFetches.poll(remainingNanos, TimeUnit.NANOSECONDS);
                    if (completed == null) {
                        throw new TimeoutException("Broker metrics refresh deadline exceeded");
                    }
                    IndexedBroker result = completed.get();
                    brokerResults[result.index()] = result.summary();
                } catch (InterruptedException e) {
                    cancelOutstanding(futures);
                    Thread.currentThread().interrupt();
                    log.warn("Interrupted while refreshing broker metrics for cluster {}", cluster.getId());
                    break;
                } catch (TimeoutException e) {
                    cancelOutstanding(futures);
                    log.warn("Broker metrics refresh deadline exceeded for cluster {}", cluster.getId());
                    break;
                } catch (ExecutionException e) {
                    log.warn("Failed to fetch broker metrics for cluster {}", cluster.getId(), e.getCause());
                }
            }

            List<BrokerSummaryDto> brokers = Arrays.stream(brokerResults)
                    .filter(Objects::nonNull)
                    .toList();
            cache.put(cluster.getId(), new CachedBrokers(brokers, System.currentTimeMillis(), inputKey, false));
            return brokers;
        }
    }

    private Map<String, Host> loadHostsById(List<ClusterServiceAssignment> services) {
        Set<String> hostIds = services.stream()
                .filter(service -> isKafkaNodeRole(service.getRole()))
                .map(ClusterServiceAssignment::getHostId)
                .filter(hostId -> hostId != null && !hostId.isBlank())
                .collect(Collectors.toSet());
        if (hostIds.isEmpty()) {
            return Map.of();
        }
        Map<String, Host> hostsById = new HashMap<>();
        for (Host host : hostRepository.findAllById(hostIds)) {
            hostsById.putIfAbsent(host.getId(), host);
        }
        return hostsById;
    }

    private void cancelOutstanding(List<Future<IndexedBroker>> futures) {
        futures.forEach(future -> future.cancel(true));
    }

    private static ThreadPoolExecutor createMetricsExecutor() {
        AtomicInteger threadNumber = new AtomicInteger();
        ThreadFactory threadFactory = task -> {
            Thread thread = new Thread(task, "broker-metrics-" + threadNumber.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                METRICS_FETCH_THREADS,
                METRICS_FETCH_THREADS,
                METRICS_FETCH_THREAD_KEEP_ALIVE_SECONDS,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(METRICS_FETCH_QUEUE_CAPACITY),
                threadFactory,
                new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    private record IndexedBroker(int index, BrokerSummaryDto summary) {}

    @PreDestroy
    public void shutdownMetricsExecutor() {
        metricsExecutor.shutdownNow();
    }

    private Object refreshLock(UUID clusterId) {
        return refreshLocks.computeIfAbsent(clusterId, ignored -> new Object());
    }

    private BrokerInput brokerInput(ClusterServiceAssignment service, Host host) {
        return new BrokerInput(
                service.getHostId(), service.getNodeId(), service.getRole(),
                host == null ? null : host.getId(),
                host == null ? null : host.getHostname(),
                host == null ? null : host.getHostIp(),
                host == null ? null : host.getStatus(),
                host == null ? null : host.getRemoved(),
                host == null ? null : host.getLastHeartbeat(),
                host == null ? null : host.getCpuUsagePct(),
                host == null ? null : host.getMemTotalMb(),
                host == null ? null : host.getMemUsedMb(),
                host == null ? null : host.getDiskTotalGb(),
                host == null ? null : host.getDiskUsedGb());
    }

    public List<BrokerSummaryDto> getBrokerSummaries(ExternalCluster cluster) {
        synchronized (refreshLock(cluster.getId())) {
            CachedBrokers cached = cache.get(cluster.getId());
            long now = System.currentTimeMillis();

            if (cached != null && cached.external && (now - cached.timestamp < CACHE_TTL_MS)) {
                return cached.brokers;
            }

            List<BrokerSummaryDto> brokers = fetchBootstrapOnlyExternalBrokers(cluster);
            cache.put(cluster.getId(), new CachedBrokers(brokers, now, null, true));
            return brokers;
        }
    }

    private BrokerSummaryDto fetchMetricsForBroker(ClusterServiceAssignment svc, Host host) {
        if (host == null) return null;

        boolean heartbeatOk = "ONLINE".equalsIgnoreCase(hostStatusService.agentConnectivityStatus(host));
        
        BrokerSummaryDto.BrokerSummaryDtoBuilder builder = BrokerSummaryDto.builder()
            .brokerId(svc.getNodeId())
            .hostname(host.getHostname())
            .role(svc.getRole())
            .isController(isControllerRole(svc.getRole()))
            .lastHeartbeat(host.getLastHeartbeat())
            .hostMetricStatus(heartbeatOk ? "LIVE" : (host.getLastHeartbeat() == null ? "UNAVAILABLE" : "STALE"))
            .metricsTimestamp(System.currentTimeMillis());

        if (heartbeatOk) {
            builder.cpuUsagePct(host.getCpuUsagePct());
            builder.memoryTotalMb(host.getMemTotalMb());
            builder.memoryUsedMb(host.getMemUsedMb());
            builder.diskTotalGb(host.getDiskTotalGb());
            builder.diskUsedGb(host.getDiskUsedGb());
            builder.diskUsedBytes(gibibytesToBytes(host.getDiskUsedGb()));
            builder.diskTotalBytes(gibibytesToBytes(host.getDiskTotalGb()));
        }

        // Fetch JMX
        boolean jmxReachable = false;
        String targetIp = host.getHostIp();
        try {
            if ((targetIp == null || targetIp.isBlank())
                    && host.getIpAddresses() != null && !host.getIpAddresses().isBlank()) {
                List<String> ips = objectMapper.readValue(host.getIpAddresses(), new TypeReference<List<String>>() {});
                if (!ips.isEmpty()) {
                    targetIp = ips.get(0);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse IPs for host {}", host.getId());
        }

        if (targetIp != null) {
            try {
                String url = "http://" + targetIp + ":" + monitoringProperties.getJmxExporterPort() + "/metrics";
                String metricsText = restTemplate.getForObject(url, String.class);
                if (metricsText != null) {
                    jmxReachable = true;
                    parsePrometheusText(metricsText, builder);
                }
            } catch (Exception e) {
                log.warn("Failed to fetch JMX metrics from {}:{}: {}", targetIp, monitoringProperties.getJmxExporterPort(), e.getMessage());
            }
        }

        builder.isJmxReachable(jmxReachable);

        // Determine Health
        if (heartbeatOk && jmxReachable) {
            builder.brokerHealth("HEALTHY");
        } else if (heartbeatOk && !jmxReachable) {
            builder.brokerHealth("DEGRADED");
        } else {
            builder.brokerHealth("OFFLINE");
        }

        return builder.build();
    }

    private List<BrokerSummaryDto> fetchBootstrapOnlyExternalBrokers(ExternalCluster cluster) {
        return externalClusterService.brokerRecords(cluster).stream()
                .filter(record -> isKafkaNodeRole(record.getRole()))
                .map(record -> {
                    boolean live = record.isRunning();
                    return BrokerSummaryDto.builder()
                        .brokerId(record.getNodeId() != null ? record.getNodeId() : -1)
                        .hostname(record.getHostname() != null ? record.getHostname() : record.getBootstrap())
                        .role(record.getRole() != null ? record.getRole() : "broker")
                        .isController(isControllerRole(record.getRole()))
                        .brokerHealth(live ? "HEALTHY" : "DEGRADED")
                        .lastHeartbeat(record.getLastSeen() != null ? OffsetDateTime.parse(record.getLastSeen()) : null)
                        .hostMetricStatus(live ? "LIVE" : (record.getLastSeen() == null ? "UNAVAILABLE" : "STALE"))
                        .isJmxReachable(false)
                        .metricsTimestamp(System.currentTimeMillis())
                        .cpuUsagePct(live ? record.getCpuUsagePct() : null)
                        .memoryTotalMb(live ? record.getMemoryTotalMb() : null)
                        .memoryUsedMb(live ? record.getMemoryUsedMb() : null)
                        .diskTotalGb(live ? record.getDiskTotalGb() : null)
                        .diskUsedGb(live ? record.getDiskUsedGb() : null)
                        .diskUsedBytes(live ? resolveDiskBytes(record.getDiskUsedBytes(), record.getDiskUsedGb()) : null)
                        .diskTotalBytes(live ? resolveDiskBytes(record.getDiskTotalBytes(), record.getDiskTotalGb()) : null)
                        .messagesInPerSec(live ? record.getMessagesInPerSec() : 0.0)
                        .bytesInPerSec(live ? record.getBytesInPerSec() : 0.0)
                        .build();
                })
                .collect(Collectors.toList());
    }

    private Long resolveDiskBytes(Long exactBytes, Long legacyGiB) {
        return exactBytes != null ? exactBytes : gibibytesToBytes(legacyGiB);
    }

    private Long gibibytesToBytes(Long value) {
        if (value == null || value < 0) return null;
        long gibibyte = 1024L * 1024L * 1024L;
        return value > Long.MAX_VALUE / gibibyte ? Long.MAX_VALUE : value * gibibyte;
    }

    private boolean isKafkaNodeRole(String role) {
        return role != null && ("broker".equalsIgnoreCase(role)
                || "controller".equalsIgnoreCase(role)
                || "broker_controller".equalsIgnoreCase(role)
                || "broker_zookeeper".equalsIgnoreCase(role));
    }

    private boolean isControllerRole(String role) {
        return role != null && ("controller".equalsIgnoreCase(role)
                || "broker_controller".equalsIgnoreCase(role));
    }

    private void parsePrometheusText(String text, BrokerSummaryDto.BrokerSummaryDtoBuilder builder) {
        String[] lines = text.split("\n");
        for (String line : lines) {
            if (line.startsWith("#")) continue;
            String[] parts = line.split(" ");
            if (parts.length < 2) continue;
            
            String metric = parts[0].toLowerCase();
            try {
                double val = Double.parseDouble(parts[1]);
                if (metric.startsWith("kafka_server_brokertopicmetrics_messagesinpersec_oneminuterate")
                        || namedOneMinuteRate(metric, "messagesinpersec")) {
                    builder.messagesInPerSec(val);
                } else if (metric.startsWith("kafka_server_brokertopicmetrics_bytesinpersec_oneminuterate")
                        || namedOneMinuteRate(metric, "bytesinpersec")) {
                    builder.bytesInPerSec(val);
                } else if (metric.startsWith("kafka_server_brokertopicmetrics_bytesoutpersec_oneminuterate")
                        || namedOneMinuteRate(metric, "bytesoutpersec")) {
                    builder.bytesOutPerSec(val);
                } else if (metric.startsWith("kafka_controller_kafkacontroller_activecontrollercount_value")) {
                    builder.isController(val > 0);
                }
            } catch (Exception e) {
                // ignore unparseable
            }
        }
    }

    private boolean namedOneMinuteRate(String metric, String name) {
        return metric.startsWith("kafka_server_brokertopicmetrics_oneminuterate")
                && metric.contains("name=\"" + name + "\"");
    }

    private static class CachedBrokers {
        List<BrokerSummaryDto> brokers;
        long timestamp;
        List<BrokerInput> inputKey;
        boolean external;

        CachedBrokers(List<BrokerSummaryDto> brokers, long timestamp, List<BrokerInput> inputKey, boolean external) {
            this.brokers = brokers;
            this.timestamp = timestamp;
            this.inputKey = inputKey;
            this.external = external;
        }
    }

    private record BrokerInput(
            String hostId,
            Integer nodeId,
            String role,
            String resolvedHostId,
            String hostname,
            String hostIp,
            String status,
            Boolean removed,
            OffsetDateTime lastHeartbeat,
            Double cpuUsagePct,
            Long memoryTotalMb,
            Long memoryUsedMb,
            Long diskTotalGb,
            Long diskUsedGb) {}
}

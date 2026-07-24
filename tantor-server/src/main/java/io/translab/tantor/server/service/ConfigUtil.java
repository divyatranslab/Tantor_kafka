package io.translab.tantor.server.service;

import io.translab.tantor.server.domain.Cluster;
import io.translab.tantor.server.domain.ClusterServiceAssignment;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

public class ConfigUtil {
    public static String activeKafkaInstallDir(Map<String, Object> config) {
            String configured = ConfigUtil.stringConfig(config, "kafka_install_base_dir", ConfigUtil.stringConfig(config, "kafka_install_dir", "/opt")).trim();
            if (configured.isBlank()) {
                configured = "/opt";
            }
            configured = ConfigUtil.trimTrailingSlash(configured);
            if (configured.endsWith("/kafka")) {
                return configured;
            }
            String leaf = configured.substring(configured.lastIndexOf('/') + 1);
            if (leaf.startsWith("kafka_")) {
                int lastSlash = configured.lastIndexOf('/');
                return (lastSlash <= 0 ? "" : configured.substring(0, lastSlash)) + "/kafka";
            }
            return configured + "/kafka";
        }

    public static String defaultKafkaDataDir(Map<String, Object> config) {
            String configured = ConfigUtil.stringConfig(config, "kafka_install_base_dir", ConfigUtil.stringConfig(config, "kafka_install_dir", "/opt")).trim();
            if (configured.isBlank()) {
                configured = "/opt";
            }
            configured = ConfigUtil.trimTrailingSlash(configured);
            if ("/opt".equals(configured) || "/".equals(configured)) {
                return "/data/kafka";
            }
            if (configured.endsWith("/kafka")) {
                int lastSlash = configured.lastIndexOf('/');
                configured = lastSlash <= 0 ? "/" : configured.substring(0, lastSlash);
            }
            return ConfigUtil.trimTrailingSlash(configured) + "/kafka-data";
        }

    public static String trimTrailingSlash(String value) {
            while (value.length() > 1 && value.endsWith("/")) {
                value = value.substring(0, value.length() - 1);
            }
            return value;
        }

    public static String serviceConfigId(ClusterServiceAssignment service) {
            return service.getRole() + "-" + (service.getNodeId() == null ? "unknown" : service.getNodeId());
        }

    public static String serviceConfigLabel(ClusterServiceAssignment service) {
            String role = service.getRole() == null ? "Kafka" : service.getRole().replace('_', ' ');
            return ConfigUtil.capitalizeWords(role) + " Node " + (service.getNodeId() == null ? "" : service.getNodeId());
        }

    public static String serviceConfigDescription(ClusterServiceAssignment service) {
            return ConfigUtil.serviceNameForRole(service.getRole()) + ".service config for host " + service.getHostId();
        }

    public static String serviceConfigPath(String role, String mode, String kafkaVersion, String installDir) {
            if ("zookeeper".equalsIgnoreCase(mode)) {
                if ("zookeeper".equals(role)) return installDir + "/config/zookeeper.properties";
                return installDir + "/config/server.properties";
            }
            String configRoot = ConfigUtil.kafkaMajorVersion(kafkaVersion) >= 4 ? installDir + "/config" : installDir + "/config/kraft";
            if ("controller".equals(role)) return configRoot + "/controller.properties";
            if ("broker".equals(role)) return configRoot + "/broker.properties";
            return configRoot + "/server.properties";
        }

    public static int kafkaMajorVersion(String version) {
            if (version == null) return 0;
            try { return Integer.parseInt(version.trim().replaceFirst("^[vV]", "").split("\\.")[0]); }
            catch (Exception ignored) { return 0; }
        }

        @SuppressWarnings("unchecked")
        public static Map<String, Object> serviceConfig(Map<String, Object> clusterConfig, ClusterServiceAssignment service, ObjectMapper objectMapper) {
            Map<String, Object> result = new HashMap<>(clusterConfig);
            if (service.getConfigJson() == null || service.getConfigJson().isBlank()) return result;
            try {
                Map<String, Object> stored = objectMapper.readValue(service.getConfigJson(), Map.class);
                if (stored != null) result.putAll(stored);
            } catch (Exception ignored) {
                // Fall back to cluster-level deployment settings for legacy assignments.
            }
            return result;
        }

        @SuppressWarnings("unchecked")
        public static Map<String, Object> storedProperties(ClusterServiceAssignment service, ObjectMapper objectMapper) {
            if (service.getConfigJson() == null || service.getConfigJson().isBlank()) return new LinkedHashMap<>();
            try {
                Map<String, Object> stored = objectMapper.readValue(service.getConfigJson(), Map.class);
                Object properties = stored == null ? null : stored.get("properties");
                return properties instanceof Map<?, ?> ? new LinkedHashMap<>((Map<String, Object>) properties) : new LinkedHashMap<>();
            } catch (Exception ignored) {
                return new LinkedHashMap<>();
            }
        }

    public static String serviceNameForRole(String role) {
            if ("controller".equals(role)) return "controller";
            if ("zookeeper".equals(role)) return "zookeeper";
            if ("broker_controller".equals(role) || "broker_zookeeper".equals(role)) return "kafka";
            return "broker";
        }

    public static String brokerLogDirs(Map<String, Object> config, String dataDir) {
            String configured = ConfigUtil.stringConfig(config, "log_dirs", "");
            return configured.isBlank() ? dataDir + "/broker-data" : configured;
        }

    public static String metadataLogDirForRole(String role, Map<String, Object> config, String dataDir) {
            String configured = ConfigUtil.stringConfig(config, "metadata_log_dir", "");
            if (!configured.isBlank()) return configured;
            if ("controller".equals(role)) return dataDir + "/controller-data/metadata";
            return dataDir + "/broker-metadata";
        }

    public static boolean isBrokerRole(String role) {
            return "broker".equals(role) || "broker_controller".equals(role) || "broker_zookeeper".equals(role);
        }

    public static boolean isControllerRole(String role) {
            return "controller".equals(role) || "broker_controller".equals(role);
        }

    public static String capitalizeWords(String value) {
            StringBuilder result = new StringBuilder();
            for (String part : value.split(" ")) {
                if (part.isBlank()) continue;
                if (result.length() > 0) result.append(' ');
                result.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
            }
            return result.toString();
        }

    public static String activeServerConfigPath(Cluster cluster, String installDir) {
            return "zookeeper".equalsIgnoreCase(cluster.getMode())
                    ? installDir + "/config/server.properties"
                    : installDir + "/config/kraft/server.properties";
        }

    public static String stringConfig(Map<String, Object> config, String key, String defaultValue) {
            Object value = config.get(key);
            if (value == null || String.valueOf(value).isBlank()) {
                return defaultValue;
            }
            return String.valueOf(value);
        }

    public static String firstNodeId(Cluster cluster, Map<String, Object> config) {
            Object configured = config.get("node_id");
            if (configured != null && !String.valueOf(configured).isBlank()) {
                return String.valueOf(configured);
            }
            if (cluster.getServices() != null && !cluster.getServices().isEmpty() && cluster.getServices().get(0).getNodeId() != null) {
                return String.valueOf(cluster.getServices().get(0).getNodeId());
            }
            return "1";
        }

    public static String firstServiceRole(Cluster cluster) {
            if (cluster.getServices() != null && !cluster.getServices().isEmpty() && cluster.getServices().get(0).getRole() != null) {
                return cluster.getServices().get(0).getRole();
            }
            return "broker_controller";
        }

    public static String processRoles(String role) {
            if (role == null || role.isBlank() || "broker_controller".equalsIgnoreCase(role)) {
                return "broker,controller";
            }
            if ("broker_zookeeper".equalsIgnoreCase(role)) {
                return "broker";
            }
            return role.replace('_', ',');
        }

    public static String firstBootstrapPort(Cluster cluster, String fallback) {
            if (cluster.getBootstrapServers() != null && !cluster.getBootstrapServers().isBlank()) {
                String endpoint = cluster.getBootstrapServers().split(",")[0];
                int idx = endpoint.lastIndexOf(':');
                if (idx > -1 && idx < endpoint.length() - 1) {
                    return endpoint.substring(idx + 1).replaceAll("[^0-9]", "");
                }
            }
            return fallback;
        }

    public static String hostFromEndpoint(String endpoint) {
            if (endpoint == null) return "";
            String value = endpoint.trim();
            int scheme = value.indexOf("://");
            if (scheme >= 0) {
                value = value.substring(scheme + 3);
            }
            int slash = value.indexOf('/');
            if (slash >= 0) {
                value = value.substring(0, slash);
            }
            int colon = value.lastIndexOf(':');
            if (colon > 0) {
                value = value.substring(0, colon);
            }
            return value.trim();
        }

    public static String serializeProperties(Map<String, Object> properties) {
            StringBuilder result = new StringBuilder();
            properties.forEach((key, value) -> {
                if ("servers".equals(key)) return;
                result.append(key).append('=').append(value == null ? "" : String.valueOf(value).replace("\r", "").replace("\n", " ")).append('\n');
            });
            return result.toString();
        }

    public static String auditProperties(String properties) {
            if (properties == null || properties.isBlank()) return properties;
            return properties.lines().map(line -> {
                int separator = line.indexOf('=');
                if (separator < 0) return line;
                String key = line.substring(0, separator);
                return key + "=" + ConfigUtil.auditConfigValue(key, line.substring(separator + 1));
            }).collect(java.util.stream.Collectors.joining("\n"));
        }

    public static String auditConfigValue(String key, String value) {
            String normalized = key == null ? "" : key.toLowerCase(java.util.Locale.ROOT);
            if (normalized.contains("password") || normalized.contains("secret")
                    || normalized.contains("token") || normalized.contains("credential")
                    || normalized.contains("jaas")) {
                return "<redacted>";
            }
            return value;
        }

    public static List<String> parseAgentAddresses(String ipAddresses, ObjectMapper objectMapper) {
            if (ipAddresses == null || ipAddresses.isBlank()) {
                return List.of();
            }
            try {
                return objectMapper.readValue(ipAddresses, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
            } catch (Exception ignored) {
                List<String> values = new ArrayList<>();
                for (String part : ipAddresses.replaceAll("\\[|\\]|\\\"", "").split(",")) {
                    String value = part.trim();
                    if (!value.isBlank()) {
                        values.add(value);
                    }
                }
                return values;
            }
        }

    public static String firstAddressFromJson(String ipAddressesJson, ObjectMapper objectMapper) {
            if (ipAddressesJson == null || ipAddressesJson.isBlank()) {
                return "";
            }
            try {
                List<?> addresses = objectMapper.readValue(ipAddressesJson, List.class);
                for (Object address : addresses) {
                    String value = String.valueOf(address);
                    if (!value.isBlank() && !value.startsWith("127.") && !"localhost".equalsIgnoreCase(value)) {
                        return value;
                    }
                }
            } catch (Exception ignored) {
                String cleaned = ipAddressesJson.replace("[", "").replace("]", "").replace("\"", "");
                for (String part : cleaned.split(",")) {
                    String value = part.trim();
                    if (!value.isBlank() && !value.startsWith("127.") && !"localhost".equalsIgnoreCase(value)) {
                        return value;
                    }
                }
            }
            return "";
        }

}

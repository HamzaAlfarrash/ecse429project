package performance;

import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public final class TodoPerformanceRunner {

    private static final List<Integer> DEFAULT_OBJECT_COUNTS = List.of(10, 50, 100, 250, 500, 1000);
    private static final int DEFAULT_ITERATIONS = 5;
    private static final int STARTUP_TIMEOUT_SECONDS = 20;
    private static final String DEFAULT_BASE_URI = "http://localhost:4567";

    private final Config config;
    private final HttpClient httpClient;
    private final Random random;
    private final SystemMetricsReader metricsReader;

    private TodoPerformanceRunner(Config config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        this.random = new Random(config.seed());
        this.metricsReader = new SystemMetricsReader();
    }

    public static void main(String[] args) throws Exception {
        Config config = Config.fromSystemProperties();
        TodoPerformanceRunner runner = new TodoPerformanceRunner(config);
        runner.execute();
    }

    private void execute() throws Exception {
        prepareOutputDirectories();

        List<Measurement> measurements = new ArrayList<>();
        for (Operation operation : Operation.values()) {
            for (int objectCount : config.objectCounts()) {
                runScenario(operation, objectCount, measurements);
            }
        }

        writeArtifacts(measurements);
        System.out.printf(
                Locale.US,
                "Completed %d performance measurements. Results written to %s%n",
                measurements.size(),
                config.outputDir().toAbsolutePath()
        );
    }

    private void runScenario(Operation operation, int objectCount, List<Measurement> measurements) throws Exception {
        System.out.printf(
                Locale.US,
                "Running %s experiment with %d seeded todo objects (%d iterations)%n",
                operation.label(),
                objectCount,
                config.iterations()
        );

        Path logFile = config.outputDir().resolve("server-" + operation.label() + "-" + objectCount + ".log");
        TodoApiServer server = new TodoApiServer(logFile, config.port(), config.baseUri());
        server.start();

        List<Integer> seededIds = new ArrayList<>();
        try {
            seededIds.addAll(seedTodos(objectCount));
            for (int iteration = 1; iteration <= config.iterations(); iteration++) {
                measurements.add(runMeasurement(server, operation, objectCount, iteration, seededIds));
            }
        } finally {
            cleanupTodos(seededIds);
            server.stop();
        }
    }

    private Measurement runMeasurement(
            TodoApiServer server,
            Operation operation,
            int objectCount,
            int iteration,
            List<Integer> seededIds
    ) throws Exception {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(operation, "operation");

        SystemMetrics beforeMetrics = metricsReader.read(server.pid());
        long startedAtNs = System.nanoTime();
        MeasurementResult result = switch (operation) {
            case CREATE -> performCreate(iteration);
            case UPDATE -> performUpdate(iteration, seededIds);
            case DELETE -> performDelete(iteration, seededIds);
        };
        long durationNs = System.nanoTime() - startedAtNs;
        SystemMetrics afterMetrics = metricsReader.read(server.pid());

        return new Measurement(
                operation.label(),
                objectCount,
                iteration,
                durationNs / 1_000_000.0d,
                average(beforeMetrics.cpuPercent(), afterMetrics.cpuPercent()),
                average(beforeMetrics.freeMemoryMb(), afterMetrics.freeMemoryMb()),
                result.status(),
                result.notes()
        );
    }

    private MeasurementResult performCreate(int iteration) throws Exception {
        JSONObject payload = createTodoPayload("create", iteration);
        HttpResponse<String> response = postJson("/todos", payload);
        if (response.statusCode() != 201) {
            return MeasurementResult.failure("HTTP_" + response.statusCode(), response.body());
        }

        int createdId = new JSONObject(response.body()).getInt("id");
        cleanupTodos(List.of(createdId));
        return MeasurementResult.success();
    }

    private MeasurementResult performUpdate(int iteration, List<Integer> seededIds) throws Exception {
        if (seededIds.isEmpty()) {
            return MeasurementResult.failure("FAILURE", "No seeded ids available for update");
        }

        int index = (iteration - 1) % seededIds.size();
        int id = seededIds.get(index);
        JSONObject payload = createTodoPayload("update", iteration);

        HttpResponse<String> response = putJson("/todos/" + id, payload);
        if (response.statusCode() != 200) {
            return MeasurementResult.failure("HTTP_" + response.statusCode(), response.body());
        }
        return MeasurementResult.success();
    }

    private MeasurementResult performDelete(int iteration, List<Integer> seededIds) throws Exception {
        if (seededIds.isEmpty()) {
            return MeasurementResult.failure("FAILURE", "No seeded ids available for delete");
        }

        int index = (iteration - 1) % seededIds.size();
        int id = seededIds.get(index);
        HttpResponse<String> response = delete("/todos/" + id);
        if (response.statusCode() != 200) {
            return MeasurementResult.failure("HTTP_" + response.statusCode(), response.body());
        }

        JSONObject replacementPayload = createTodoPayload("replacement", iteration);
        HttpResponse<String> replacementResponse = postJson("/todos", replacementPayload);
        if (replacementResponse.statusCode() != 201) {
            return MeasurementResult.failure("FAILURE", "Replacement create failed: " + replacementResponse.body());
        }

        int replacementId = new JSONObject(replacementResponse.body()).getInt("id");
        seededIds.set(index, replacementId);
        return MeasurementResult.success();
    }

    private List<Integer> seedTodos(int objectCount) throws Exception {
        List<Integer> ids = new ArrayList<>();
        for (int i = 0; i < objectCount; i++) {
            JSONObject payload = createTodoPayload("seed", i);
            HttpResponse<String> response = postJson("/todos", payload);
            if (response.statusCode() != 201) {
                throw new IllegalStateException("Failed to seed todo " + i + ": " + response.body());
            }
            ids.add(new JSONObject(response.body()).getInt("id"));
        }
        return ids;
    }

    private void cleanupTodos(List<Integer> ids) {
        List<Integer> cleanupOrder = new ArrayList<>(new LinkedHashSet<>(ids));
        Collections.reverse(cleanupOrder);
        for (int id : cleanupOrder) {
            try {
                delete("/todos/" + id);
            } catch (Exception ignored) {
                // Cleanup is best-effort because the API state is reset between experiment groups.
            }
        }
    }

    private JSONObject createTodoPayload(String prefix, int sequence) {
        int suffix = random.nextInt(1_000_000);
        return new JSONObject()
                .put("title", String.format(Locale.US, "part-c-%s-%03d-%06d", prefix, sequence, suffix))
                .put("description", String.format(Locale.US, "generated-%s-%03d-%06d", prefix, sequence, suffix))
                .put("doneStatus", false);
    }

    private HttpResponse<String> postJson(String path, JSONObject payload) throws Exception {
        return sendJsonRequest("POST", path, payload.toString());
    }

    private HttpResponse<String> putJson(String path, JSONObject payload) throws Exception {
        return sendJsonRequest("PUT", path, payload.toString());
    }

    private HttpResponse<String> delete(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(path))
                .timeout(Duration.ofSeconds(5))
                .DELETE()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> sendJsonRequest(String method, String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(path))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private URI uri(String path) {
        return URI.create(config.baseUri() + path);
    }

    private void prepareOutputDirectories() throws IOException {
        if (Files.exists(config.outputDir())) {
            try (var paths = Files.walk(config.outputDir())) {
                paths.sorted(Comparator.reverseOrder())
                        .filter(path -> !path.equals(config.outputDir()))
                        .forEach(this::deleteQuietly);
            }
        }
        Files.createDirectories(config.outputDir());
        Files.createDirectories(config.outputDir().resolve("chart-data"));
        Files.createDirectories(config.outputDir().resolve("notes"));
        writeMetadataFile();
    }

    private void writeMetadataFile() throws IOException {
        List<String> lines = List.of(
                "Part C Todo Performance Run",
                "timestamp=" + DateTimeFormatter.ISO_INSTANT.format(Instant.now()),
                "base_uri=" + config.baseUri(),
                "iterations=" + config.iterations(),
                "tiers=" + config.objectCounts().stream().map(String::valueOf).collect(Collectors.joining(",")),
                "seed=" + config.seed()
        );
        Files.write(
                config.outputDir().resolve("notes").resolve("run-metadata.txt"),
                lines,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
        );
    }

    private void writeArtifacts(List<Measurement> measurements) throws IOException {
        writeRawCsv(measurements);
        List<SummaryRow> summaryRows = summarize(measurements);
        writeSummaryCsv(summaryRows);
        writeChartData(summaryRows);
    }

    private void writeRawCsv(List<Measurement> measurements) throws IOException {
        Path rawCsv = config.outputDir().resolve("raw-results.csv");
        List<String> lines = new ArrayList<>();
        lines.add("operation,object_count,iteration,duration_ms,avg_cpu_pct,free_memory_mb,status,notes");
        for (Measurement measurement : measurements) {
            lines.add(String.join(",",
                    measurement.operation(),
                    String.valueOf(measurement.objectCount()),
                    String.valueOf(measurement.iteration()),
                    formatDouble(measurement.durationMs()),
                    formatDouble(measurement.avgCpuPct()),
                    formatDouble(measurement.freeMemoryMb()),
                    csvEscape(measurement.status()),
                    csvEscape(measurement.notes())
            ));
        }
        Files.write(rawCsv, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private void writeSummaryCsv(List<SummaryRow> rows) throws IOException {
        Path summaryCsv = config.outputDir().resolve("summary-results.csv");
        List<String> lines = new ArrayList<>();
        lines.add("operation,object_count,total_runs,successes,failures,avg_duration_ms,min_duration_ms,max_duration_ms,avg_cpu_pct,avg_free_memory_mb");
        for (SummaryRow row : rows) {
            lines.add(String.join(",",
                    row.operation(),
                    String.valueOf(row.objectCount()),
                    String.valueOf(row.totalRuns()),
                    String.valueOf(row.successes()),
                    String.valueOf(row.failures()),
                    formatDouble(row.avgDurationMs()),
                    formatDouble(row.minDurationMs()),
                    formatDouble(row.maxDurationMs()),
                    formatDouble(row.avgCpuPct()),
                    formatDouble(row.avgFreeMemoryMb())
            ));
        }
        Files.write(summaryCsv, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private void writeChartData(List<SummaryRow> rows) throws IOException {
        Path chartDirectory = config.outputDir().resolve("chart-data");
        for (Operation operation : Operation.values()) {
            List<String> lines = new ArrayList<>();
            lines.add("object_count,avg_duration_ms,avg_cpu_pct,avg_free_memory_mb");
            rows.stream()
                    .filter(row -> row.operation().equals(operation.label()))
                    .sorted(Comparator.comparingInt(SummaryRow::objectCount))
                    .forEach(row -> lines.add(String.join(",",
                            String.valueOf(row.objectCount()),
                            formatDouble(row.avgDurationMs()),
                            formatDouble(row.avgCpuPct()),
                            formatDouble(row.avgFreeMemoryMb())
                    )));

            Files.write(
                    chartDirectory.resolve(operation.label() + ".csv"),
                    lines,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING
            );
        }
    }

    private List<SummaryRow> summarize(List<Measurement> measurements) {
        Map<String, List<Measurement>> grouped = measurements.stream()
                .collect(Collectors.groupingBy(
                        measurement -> measurement.operation() + "|" + measurement.objectCount(),
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        List<SummaryRow> rows = new ArrayList<>();
        for (Map.Entry<String, List<Measurement>> entry : grouped.entrySet()) {
            List<Measurement> group = entry.getValue();
            Measurement first = group.get(0);
            List<Measurement> successes = group.stream()
                    .filter(measurement -> "SUCCESS".equals(measurement.status()))
                    .toList();

            rows.add(new SummaryRow(
                    first.operation(),
                    first.objectCount(),
                    group.size(),
                    successes.size(),
                    group.size() - successes.size(),
                    average(successes.stream().map(Measurement::durationMs).toList()),
                    minimum(successes.stream().map(Measurement::durationMs).toList()),
                    maximum(successes.stream().map(Measurement::durationMs).toList()),
                    average(successes.stream().map(Measurement::avgCpuPct).toList()),
                    average(successes.stream().map(Measurement::freeMemoryMb).toList())
            ));
        }

        rows.sort(Comparator
                .comparing(SummaryRow::operation)
                .thenComparingInt(SummaryRow::objectCount));
        return rows;
    }

    private static double average(double first, double second) {
        if (Double.isNaN(first) && Double.isNaN(second)) {
            return Double.NaN;
        }
        if (Double.isNaN(first)) {
            return second;
        }
        if (Double.isNaN(second)) {
            return first;
        }
        return (first + second) / 2.0d;
    }

    private static double average(List<Double> values) {
        List<Double> validValues = values.stream()
                .filter(value -> !Double.isNaN(value))
                .toList();
        if (validValues.isEmpty()) {
            return Double.NaN;
        }
        return validValues.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
    }

    private static double minimum(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).min().orElse(Double.NaN);
    }

    private static double maximum(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).max().orElse(Double.NaN);
    }

    private static String formatDouble(double value) {
        return Double.isNaN(value) ? "" : String.format(Locale.US, "%.3f", value);
    }

    private static String csvEscape(String value) {
        String escaped = value == null ? "" : value.replace("\"", "\"\"");
        return "\"" + escaped + "\"";
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to delete stale output path " + path, exception);
        }
    }

    private enum Operation {
        CREATE("create"),
        UPDATE("update"),
        DELETE("delete");

        private final String label;

        Operation(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private record Measurement(
            String operation,
            int objectCount,
            int iteration,
            double durationMs,
            double avgCpuPct,
            double freeMemoryMb,
            String status,
            String notes
    ) {
    }

    private record SummaryRow(
            String operation,
            int objectCount,
            int totalRuns,
            int successes,
            int failures,
            double avgDurationMs,
            double minDurationMs,
            double maxDurationMs,
            double avgCpuPct,
            double avgFreeMemoryMb
    ) {
    }

    private record MeasurementResult(String status, String notes) {
        static MeasurementResult success() {
            return new MeasurementResult("SUCCESS", "");
        }

        static MeasurementResult failure(String status, String notes) {
            return new MeasurementResult(status, notes == null ? "" : notes);
        }
    }

    private record Config(Path outputDir, List<Integer> objectCounts, int iterations, int port, long seed, String baseUri) {

        static Config fromSystemProperties() {
            String tiersValue = System.getProperty("partc.tiers", "");
            List<Integer> objectCounts = tiersValue.isBlank()
                    ? DEFAULT_OBJECT_COUNTS
                    : parseObjectCounts(tiersValue);

            int iterations = Integer.parseInt(System.getProperty("partc.iterations", String.valueOf(DEFAULT_ITERATIONS)));
            int port = Integer.parseInt(System.getProperty("partc.port", "4567"));
            long seed = Long.parseLong(System.getProperty("partc.seed", "4292026"));
            Path outputDir = Path.of(System.getProperty("partc.outputDir", "part_c/performance/results/latest"));
            String defaultBaseUri = "http://localhost:" + port;
            String baseUri = System.getProperty("partc.baseUri", defaultBaseUri);
            return new Config(outputDir, objectCounts, iterations, port, seed, baseUri);
        }

        private static List<Integer> parseObjectCounts(String value) {
            return List.of(value.split(",")).stream()
                    .map(String::trim)
                    .filter(token -> !token.isEmpty())
                    .map(Integer::parseInt)
                    .distinct()
                    .sorted()
                    .toList();
        }
    }

    private static final class SystemMetricsReader {
        private final com.sun.management.OperatingSystemMXBean operatingSystemBean =
                (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

        SystemMetrics read(long pid) {
            double cpuPercent = readProcessCpuPercent(pid);
            double freeMemoryMb = operatingSystemBean.getFreeMemorySize() / (1024.0d * 1024.0d);
            return new SystemMetrics(cpuPercent, freeMemoryMb);
        }

        private double readProcessCpuPercent(long pid) {
            Process process = null;
            try {
                process = new ProcessBuilder("ps", "-p", Long.toString(pid), "-o", "%cpu=")
                        .redirectErrorStream(true)
                        .start();

                try (InputStream inputStream = process.getInputStream()) {
                    String output = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8).trim();
                    if (!process.waitFor(2, TimeUnit.SECONDS) || output.isBlank()) {
                        return Double.NaN;
                    }
                    return Double.parseDouble(output);
                }
            } catch (Exception exception) {
                return Double.NaN;
            } finally {
                if (process != null) {
                    process.destroyForcibly();
                }
            }
        }
    }

    private record SystemMetrics(double cpuPercent, double freeMemoryMb) {
    }

    private static final class TodoApiServer {
        private final Path logFile;
        private final int port;
        private final String baseUri;
        private Process process;

        TodoApiServer(Path logFile, int port, String baseUri) {
            this.logFile = logFile;
            this.port = port;
            this.baseUri = baseUri;
        }

        void start() throws Exception {
            Files.createDirectories(logFile.getParent());
            ProcessBuilder processBuilder = new ProcessBuilder(
                    "java",
                    "-jar",
                    "runTodoManagerRestAPI-1.5.5.jar",
                    "-port=" + port
            );
            processBuilder.redirectErrorStream(true);
            processBuilder.redirectOutput(ProcessBuilder.Redirect.to(logFile.toFile()));
            process = processBuilder.start();
            waitForReady();
        }

        void stop() {
            if (process == null) {
                return;
            }

            try {
                HttpClient.newHttpClient().send(
                        HttpRequest.newBuilder(URI.create(baseUri + "/shutdown"))
                                .timeout(Duration.ofSeconds(2))
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.discarding()
                );
            } catch (Exception ignored) {
                // Fall through to process termination.
            }

            process.destroy();
            try {
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(5, TimeUnit.SECONDS);
                }
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            } finally {
                process = null;
            }
        }

        long pid() {
            if (process == null) {
                throw new IllegalStateException("API process is not running");
            }
            return process.pid();
        }

        private void waitForReady() throws Exception {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(1))
                    .build();
            Instant deadline = Instant.now().plusSeconds(STARTUP_TIMEOUT_SECONDS);

            while (Instant.now().isBefore(deadline)) {
                try {
                    HttpResponse<String> response = client.send(
                            HttpRequest.newBuilder(URI.create(baseUri + "/todos"))
                                    .timeout(Duration.ofSeconds(1))
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
                    );
                    if (response.statusCode() == 200) {
                        return;
                    }
                } catch (ConnectException ignored) {
                    // The service is still starting.
                } catch (IOException ignored) {
                    // Retry until timeout.
                }
                Thread.sleep(250L);
            }

            throw new IllegalStateException("Todo API did not become ready within " + STARTUP_TIMEOUT_SECONDS + " seconds");
        }
    }
}

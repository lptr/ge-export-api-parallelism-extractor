package com.gradle.enterprise.export;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Iterators;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.MoreExecutors;
import com.gradle.enterprise.export.util.DurationConverter;
import com.gradle.enterprise.export.util.HttpUrlConverter;
import com.gradle.enterprise.export.util.PatternConverter;
import com.gradle.enterprise.export.util.StreamableQueue;
import okhttp3.ConnectionPool;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSources;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.google.common.collect.ImmutableSortedMap.copyOfSorted;
import static java.time.Instant.now;

@Command(
        name = "analyze",
        description = "Analyze GE data",
        mixinStandardHelpOptions = true
)
public final class AnalyzeBuilds implements Callable<Integer> {
    @Option(names = "--server", description = "GE server URL", converter = HttpUrlConverter.class)
    private HttpUrl serverUrl = HttpUrl.parse("https://ge.gradle.org");

    @Option(names = "--api-key", description = "Export API access key, can be set via EXPORT_API_ACCESS_KEY environment variable")
    private String exportApiAccessKey = System.getenv("EXPORT_API_ACCESS_KEY");

    @Option(names = "--max-concurrency", description = "Maximum number of build scans streamed concurrently")
    private int maxBuildScansStreamedConcurrently = 30;

    @Option(names = "--builds", split = ",", description = "Comma-separated list of build IDs to process")
    private List<String> builds;

    @Option(names = "--load-builds-from", description = "File to load build IDs from (one ID per line); ignored when --builds is specified")
    private File buildInputFile;

    @Option(names = "--save-builds-to", description = "File to save build IDs to")
    private File buildOutputFile;

    @Option(names = "--query-since", description = "Query builds in the given timeframe; defaults to two hours, see Duration.parse() for more info; ignored when --builds or --load-builds-from is specified", converter = DurationConverter.class)
    private Duration since = Duration.ofHours(2);

    @Option(names = "--include-project", description = "Comma-separated list of projects to include")
    private List<String> includeProjects;

    @Option(names = "--exclude-project", description = "Comma-separated list of projects to exclude")
    private List<String> excludeProjects;

    @Option(names = "--include-tag", description = "Comma-separated list of tags to include")
    private List<String> includeTags;

    @Option(names = "--exclude-tag", description = "Comma-separated list of tags to exclude")
    private List<String> excludeTags;

    @Option(names = "--include-requested-tasks", description = "Include only builds that requested tasks mathcing the given regular expression", converter = PatternConverter.class)
    private Pattern includeRequestedTasks;

    @Option(names = "--exclude-requested-tasks", description = "Exclude builds that requested tasks mathcing the given regular expression", converter = PatternConverter.class)
    private Pattern excludeRequestedTasks;

    @Option(names = "--include-task-type", description = "Include only tasks with FQCNs starting with the given pattern")
    private List<String> includeTaskTypes;

    @Option(names = "--exclude-task-type", description = "Exclude tasks with FQCNs starting with the given pattern")
    private List<String> excludeTaskTypes;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void main(String[] args) {
        int exitCode = new CommandLine(new AnalyzeBuilds()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws Exception {
        OkHttpClient httpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ZERO)
                .readTimeout(Duration.ZERO)
                .retryOnConnectionFailure(true)
                .connectionPool(new ConnectionPool(maxBuildScansStreamedConcurrently, 30, TimeUnit.SECONDS))
                .authenticator(Authenticators.bearerToken(exportApiAccessKey))
                .protocols(ImmutableList.of(Protocol.HTTP_1_1))
                .build();
        httpClient.dispatcher().setMaxRequests(maxBuildScansStreamedConcurrently);
        httpClient.dispatcher().setMaxRequestsPerHost(maxBuildScansStreamedConcurrently);

        try {
            processEvents(httpClient);
        } finally {
            // Cleanly shuts down the HTTP client, which speeds up process termination
            httpClient.dispatcher().cancelAll();
            MoreExecutors.shutdownAndAwaitTermination(httpClient.dispatcher().executorService(), Duration.ofSeconds(10));
        }
        return 0;
    }

    private void processEvents(OkHttpClient httpClient) throws Exception {
        System.out.printf("Connecting to GE server at %s, fetching info about projects: %s%n", serverUrl, includeProjects.stream().collect(Collectors.joining(", ")));
        EventSource.Factory eventSourceFactory = EventSources.createFactory(httpClient);
        Stream<String> buildIds = builds != null ? builds.stream()
                : buildInputFile != null ? loadBuildsFromFile(buildInputFile)
                : queryBuildsFromPast(since, eventSourceFactory);
        BuildStatistics composedStats = buildIds
                .parallel()
                .map(buildId -> processEventSource(eventSourceFactory, buildId, requestBuildInfo(buildId), new ProcessBuildInfo(buildId, includeProjects, excludeProjects, includeTags, excludeTags, includeRequestedTasks, excludeRequestedTasks)))
                .map(future -> future.thenCompose(result -> {
                    if (result.matches) {
                        return processEventSource(eventSourceFactory, result.buildId, requestTaskEvents(result.buildId), new ProcessTaskEvents(result.buildId, result.maxWorkers, includeTaskTypes, excludeTaskTypes));
                    } else {
                        return CompletableFuture.completedFuture(BuildStatistics.EMPTY);
                    }
                }))
                .map(future -> future.exceptionally(error -> {
                            error.printStackTrace();
                            return BuildStatistics.EMPTY;
                        })
                )
                .map(future -> {
                    try {
                        return future.get();
                    } catch (InterruptedException | ExecutionException e) {
                        throw new RuntimeException(e);
                    }
                })
                .reduce(BuildStatistics::merge)
                .orElse(BuildStatistics.EMPTY);

        composedStats.print();

        if (buildOutputFile != null) {
            System.out.printf("Storing build IDs in %s%n", buildOutputFile);
            buildOutputFile.getParentFile().mkdirs();
            try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(buildOutputFile.toPath(), StandardCharsets.UTF_8))) {
                composedStats.getBuildIds()
                        .forEach(writer::println);
            }
        }
    }

    private static <T> CompletableFuture<T> processEventSource(EventSource.Factory eventSourceFactory, String buildId, Request request, BuildEventProcessor<T> processor) {
        BuildEventProcessingListener<T> listener = new BuildEventProcessingListener<>(buildId, processor);
        eventSourceFactory.newEventSource(request, listener);
        return listener.getResult();
    }

    private static Stream<String> loadBuildsFromFile(File file) {
        System.out.println("Fetching build IDs from " + file);
        try {
            return Files.readAllLines(file.toPath(), StandardCharsets.UTF_8).stream();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Nonnull
    private Stream<String> queryBuildsFromPast(Duration duration, EventSource.Factory eventSourceFactory) {
        Instant since = now().minus(duration);
        System.out.printf("Querying builds since %s%n", DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
                .withZone(ZoneId.systemDefault())
                .format(since));
        StreamableQueue<String> buildQueue = new StreamableQueue<>("FINISHED");
        FilterBuildsByBuildTool buildToolFilter = new FilterBuildsByBuildTool(buildQueue);
        eventSourceFactory.newEventSource(requestBuilds(since), buildToolFilter);
        return buildQueue.stream();
    }

    @Nonnull
    @SuppressWarnings("ConstantConditions")
    private Request requestBuilds(Instant since) {
        return new Request.Builder()
                .url(serverUrl.resolve("/build-export/v2/builds/since/" + since.toEpochMilli()))
                .build();
    }

    @Nonnull
    @SuppressWarnings("ConstantConditions")
    private Request requestBuildInfo(String buildId) {
        return new Request.Builder()
                .url(serverUrl.resolve("/build-export/v2/build/" + buildId + "/events?eventTypes=ProjectStructure,UserTag,BuildModes,BuildRequestedTasks"))
                .build();
    }

    @Nonnull
    @SuppressWarnings("ConstantConditions")
    private Request requestTaskEvents(String buildId) {
        return new Request.Builder()
                .url(serverUrl.resolve("/build-export/v2/build/" + buildId + "/events?eventTypes=TaskStarted,TaskFinished"))
                .build();
    }

    private static class FilterBuildsByBuildTool extends PrintFailuresEventSourceListener {
        private final StreamableQueue<String> buildQueue;
        private final AtomicInteger buildCount = new AtomicInteger(0);

        private FilterBuildsByBuildTool(StreamableQueue<String> buildQueue) {
            this.buildQueue = buildQueue;
        }

        @Override
        public void onOpen(@Nonnull EventSource eventSource, @Nonnull Response response) {
            System.out.println("Streaming builds...");
        }

        @Override
        public void onEvent(@Nonnull EventSource eventSource, @Nullable String id, @Nullable String type, @Nonnull String data) {
            JsonNode json = parse(data);
            JsonNode buildToolJson = json.get("toolType");
            if (buildToolJson != null && buildToolJson.asText().equals("gradle")) {
                String buildId = json.get("buildId").asText();
                buildCount.incrementAndGet();
                try {
                    buildQueue.put(buildId);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        }

        @Override
        public void onClosed(@Nonnull EventSource eventSource) {
            System.out.println("Finished querying builds, found " + buildCount.get());
            try {
                buildQueue.close();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static JsonNode parse(String data) {
        try {
            return MAPPER.readTree(data);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    private static class ProcessBuildInfo implements BuildEventProcessor<ProcessBuildInfo.Result> {
        public static class Result {
            public final String buildId;
            public final boolean matches;
            public final int maxWorkers;

            public Result(String buildId, boolean matches, int maxWorkers) {
                this.buildId = buildId;
                this.matches = matches;
                this.maxWorkers = maxWorkers;
            }
        }

        private final String buildId;
        private final List<String> includeProjects;
        private final List<String> excludeProjects;
        private final List<String> includeTags;
        private final List<String> excludeTags;
        private final Pattern includeRequestedTasks;
        private final Pattern excludeRequestedTasks;

        private final List<String> rootProjects = new ArrayList<>();
        private final List<String> tags = new ArrayList<>();
        private List<String> requestedTasks;
        private int maxWorkers;

        private ProcessBuildInfo(
                String buildId,
                List<String> includeProjects,
                List<String> excludeProjects,
                List<String> includeTags,
                List<String> excludeTags,
                Pattern includeRequestedTasks,
                Pattern excludeRequestedTasks
        ) {
            this.buildId = buildId;
            this.includeProjects = includeProjects;
            this.excludeProjects = excludeProjects;
            this.includeTags = includeTags;
            this.excludeTags = excludeTags;
            this.includeRequestedTasks = includeRequestedTasks;
            this.excludeRequestedTasks = excludeRequestedTasks;
        }

        @Override
        public void process(@Nullable String id, @Nonnull JsonNode eventJson) {
            String eventType = eventJson.get("type").get("eventType").asText();
            switch (eventType) {
                case "ProjectStructure":
                    String rootProject = eventJson.get("data").get("rootProjectName").asText();
                    rootProjects.add(rootProject);
                    break;
                case "UserTag":
                    String tag = eventJson.get("data").get("tag").asText();
                    tags.add(tag);
                    break;
                case "BuildModes":
                    maxWorkers = eventJson.get("data").get("maxWorkers").asInt();
                    break;
                case "BuildRequestedTasks":
                    this.requestedTasks = ImmutableList.copyOf(Iterators.transform(eventJson.get("data").get("requested").elements(), JsonNode::asText));
                    break;
                default:
                    throw new AssertionError("Unknown event type: " + eventType);
            }
        }

        @Override
        public Result complete() {
            boolean matches = true
                    && matches(rootProjects, includeProjects, excludeProjects, pattern -> pattern::contains)
                    && matches(tags, includeTags, excludeTags, pattern -> pattern::contains)
                    && matches(requestedTasks, includeRequestedTasks, excludeRequestedTasks, pattern -> task -> pattern.matcher(task).matches());
            return new Result(buildId, matches, maxWorkers);
        }
    }

    private static <T> boolean matches(Collection<String> elements, T includePattern, T excludePattern, Function<T, Predicate<String>> matcher) {
        if (includePattern != null && !elements.stream().anyMatch(matcher.apply(includePattern))) {
            return false;
        }
        if (excludePattern != null && elements.stream().anyMatch(matcher.apply(excludePattern))) {
            return false;
        }
        return true;
    }

    private static class ProcessTaskEvents implements BuildEventProcessor<BuildStatistics> {
        private final String buildId;
        private final int maxWorkers;
        private final List<String> includeTaskTypes;
        private final List<String> excludedTaskTypes;
        private final Map<Long, TaskInfo> tasks = new HashMap<>();

        private static class TaskInfo {
            public final String type;
            public final String path;
            public final long startTime;
            public long finishTime;
            public String outcome;

            public TaskInfo(String type, String path, long startTime) {
                this.type = type;
                this.path = path;
                this.startTime = startTime;
            }
        }

        private ProcessTaskEvents(String buildId, int maxWorkers, List<String> includeTaskTypes, List<String> excludedTaskTypes) {
            this.buildId = buildId;
            this.maxWorkers = maxWorkers;
            this.includeTaskTypes = includeTaskTypes;
            this.excludedTaskTypes = excludedTaskTypes;
        }

        @Override
        public void process(@Nullable String id, @Nonnull JsonNode eventJson) {
            long timestamp = eventJson.get("timestamp").asLong();
            String eventType = eventJson.get("type").get("eventType").asText();
            long eventId = eventJson.get("data").get("id").asLong();
            switch (eventType) {
                case "TaskStarted":
                    tasks.put(eventId, new TaskInfo(
                            eventJson.get("data").get("className").asText(),
                            eventJson.get("data").get("path").asText(),
                            timestamp
                    ));
                    break;
                case "TaskFinished":
                    TaskInfo task = tasks.get(eventId);
                    task.finishTime = timestamp;
                    task.outcome = eventJson.get("data").get("outcome").asText();
                    break;
                default:
                    throw new AssertionError("Unknown event type: " + eventType);
            }
        }

        @Override
        public BuildStatistics complete() {
            System.out.println("Finished processing build " + buildId);
            SortedMap<Long, Integer> startStopEvents = new TreeMap<>();
            AtomicInteger taskCount = new AtomicInteger(0);
            SortedMap<String, Long> taskTypeTimes = new TreeMap<>();
            SortedMap<String, Long> taskPathTimes = new TreeMap<>();
            tasks.values().stream()
                    .filter(task -> matches(Collections.singleton(task.type), includeTaskTypes, excludedTaskTypes, pattern -> task.type::startsWith))
                    .filter(task -> task.outcome.equals("success") || task.outcome.equals("failed"))
                    .forEach(task -> {
                        taskCount.incrementAndGet();
                        add(taskTypeTimes, task.type, task.finishTime - task.startTime);
                        add(taskPathTimes, task.path, task.finishTime - task.startTime);
                        add(startStopEvents, task.startTime, 1);
                        add(startStopEvents, task.finishTime, -1);
                    });

            int concurrencyLevel = 0;
            long lastTimeStamp = 0;
            SortedMap<Integer, Long> histogram = new TreeMap<>();
            for (Map.Entry<Long, Integer> entry : startStopEvents.entrySet()) {
                long timestamp = entry.getKey();
                int delta = entry.getValue();
                if (concurrencyLevel != 0) {
                    long duration = timestamp - lastTimeStamp;
                    add(histogram, concurrencyLevel, duration);
                }
                concurrencyLevel += delta;
                lastTimeStamp = timestamp;
            }
            return new DefaultBuildStatistics(
                    ImmutableList.of(buildId),
                    taskCount.get(),
                    copyOfSorted(taskTypeTimes),
                    copyOfSorted(taskPathTimes),
                    copyOfSorted(histogram),
                    ImmutableSortedMap.of(maxWorkers, 1)
            );
        }
    }

    private static <K> void add(Map<K, Integer> map, K key, int delta) {
        map.compute(key, (k, value) -> (value == null ? 0 : value) + delta);
    }

    private static <K> void add(Map<K, Long> map, K key, long delta) {
        map.compute(key, (k, value) -> (value == null ? 0 : value) + delta);
    }

    private interface BuildStatistics {
        public static final BuildStatistics EMPTY = new BuildStatistics() {
            @Override
            public List<String> getBuildIds() {
                return ImmutableList.of();
            }

            @Override
            public void print() {
                System.out.println("No matching builds found");
            }

            @Override
            public BuildStatistics merge(BuildStatistics other) {
                return other;
            }
        };

        List<String> getBuildIds();

        void print();

        BuildStatistics merge(BuildStatistics other);
    }

    private static class DefaultBuildStatistics implements BuildStatistics {
        private final ImmutableList<String> buildIds;
        private final int taskCount;
        private final ImmutableSortedMap<String, Long> taskTypeTimes;
        private final ImmutableSortedMap<String, Long> taskPathTimes;
        private final ImmutableSortedMap<Integer, Long> workerTimes;
        private final ImmutableSortedMap<Integer, Integer> maxWorkers;

        public DefaultBuildStatistics(
                ImmutableList<String> buildIds,
                int taskCount,
                ImmutableSortedMap<String, Long> taskTypeTimes,
                ImmutableSortedMap<String, Long> taskPathTimes,
                ImmutableSortedMap<Integer, Long> workerTimes,
                ImmutableSortedMap<Integer, Integer> maxWorkers
        ) {
            this.buildIds = buildIds;
            this.taskCount = taskCount;
            this.taskTypeTimes = taskTypeTimes;
            this.taskPathTimes = taskPathTimes;
            this.workerTimes = workerTimes;
            this.maxWorkers = maxWorkers;
        }

        @Override
        public ImmutableList<String> getBuildIds() {
            return buildIds;
        }

        @Override
        public void print() {
            System.out.println("Statistics for " + buildIds.size() + " builds with " + taskCount + " tasks");

            System.out.println();
            System.out.println("Concurrency levels:");
            int maxConcurrencyLevel = workerTimes.lastKey();
            for (int concurrencyLevel = maxConcurrencyLevel; concurrencyLevel >= 1; concurrencyLevel--) {
                System.out.printf("%d: %d ms%n", concurrencyLevel, workerTimes.getOrDefault(concurrencyLevel, 0L));
            }

            System.out.println();
            System.out.println("Task times by type:");
            taskTypeTimes.forEach((taskType, count) -> System.out.printf("%s: %d%n", taskType, count));

            System.out.println();
            System.out.println("Task times by path:");
            taskPathTimes.forEach((taskPath, count) -> System.out.printf("%s: %d%n", taskPath, count));

            System.out.println();
            System.out.println("Max workers:");
            int mostWorkers = maxWorkers.lastKey();
            for (int maxWorker = mostWorkers; maxWorker >= 1; maxWorker--) {
                System.out.printf("%d: %d builds%n", maxWorker, maxWorkers.getOrDefault(maxWorker, 0));
            }
        }

        @Override
        public BuildStatistics merge(BuildStatistics o) {
            if (o instanceof DefaultBuildStatistics) {
                DefaultBuildStatistics other = (DefaultBuildStatistics) o;
                ImmutableList<String> buildIds = ImmutableList.<String>builder().addAll(this.buildIds).addAll(other.buildIds).build();
                int taskCount = this.taskCount + other.taskCount;
                ImmutableSortedMap<String, Long> taskTypeTimes = mergeMaps(this.taskTypeTimes, other.taskTypeTimes, 0L, Long::sum);
                ImmutableSortedMap<String, Long> taskPathTimes = mergeMaps(this.taskPathTimes, other.taskPathTimes, 0L, Long::sum);
                ImmutableSortedMap<Integer, Long> workerTimes = mergeMaps(this.workerTimes, other.workerTimes, 0L, Long::sum);
                ImmutableSortedMap<Integer, Integer> maxWorkers = mergeMaps(this.maxWorkers, other.maxWorkers, 0, Integer::sum);
                return new DefaultBuildStatistics(buildIds, taskCount, taskTypeTimes, taskPathTimes, workerTimes, maxWorkers);
            } else {
                return this;
            }
        }

        private static <K extends Comparable<K>, V> ImmutableSortedMap<K, V> mergeMaps(Map<K, V> a, Map<K, V> b, V zero, BinaryOperator<V> add) {
            ImmutableSortedMap.Builder<K, V> merged = ImmutableSortedMap.naturalOrder();
            for (K key : Sets.union(a.keySet(), b.keySet())) {
                merged.put(key, add.apply(a.getOrDefault(key, zero), b.getOrDefault(key, zero)));
            }
            return merged.build();
        }
    }
}

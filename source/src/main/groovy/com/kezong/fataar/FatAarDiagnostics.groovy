package com.kezong.fataar

import groovy.json.JsonOutput
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.execution.TaskExecutionListener
import org.gradle.api.tasks.TaskProvider

import java.text.SimpleDateFormat
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.jar.JarInputStream

/**
 * Optional, read-only timing diagnostics for fat-aar tasks.
 *
 * Enable with -PfataarDiagnostics=true. The listener and reports are deliberately
 * isolated from the packaging path: diagnostics must never change task inputs,
 * outputs, ordering, or archive contents.
 */
class FatAarDiagnostics {

    private static final String ENABLE_PROPERTY = 'fataarDiagnostics'
    private static final String DEEP_PROPERTY = 'fataarDiagnosticsDeep'
    private static final String REGISTRY_PROPERTY = 'com.kezong.fataar.diagnostics.registry'
    private static final String REPORT_DIR = 'intermediates/fat-aar/diagnostics'

    static boolean enabled(Project project) {
        return project.findProperty(ENABLE_PROPERTY)?.toString()?.toBoolean() ?: false
    }

    /**
     * Opt-in deep archive scanning (-PfataarDiagnosticsDeep=true) on top of -PfataarDiagnostics=true.
     * Deep scanning decompresses every classes.jar to count classes, which perturbs the explode
     * timings it is meant to measure. Default mode reads only the zip central directory.
     */
    static boolean deep(Project project) {
        return enabled(project) && (project.findProperty(DEEP_PROPERTY)?.toString()?.toBoolean() ?: false)
    }

    static void attach(Project project) {
        if (!enabled(project)) {
            return
        }

        Project root = project.rootProject
        Registry registry
        if (root.extensions.extraProperties.has(REGISTRY_PROPERTY)) {
            registry = root.extensions.extraProperties.get(REGISTRY_PROPERTY) as Registry
        } else {
            registry = new Registry()
            root.extensions.extraProperties.set(REGISTRY_PROPERTY, registry)
            project.gradle.addListener(registry)
            project.gradle.buildFinished { result ->
                registry.writeReports(result)
            }
        }
        registry.register(project)
    }

    static void markTask(Project project, Task task, String variant, String category) {
        if (!enabled(project)) {
            return
        }
        Registry registry = getRegistry(project)
        if (registry != null) {
            registry.registerTask(project, task, variant, category)
        }
    }

    static void markTask(Project project, TaskProvider taskProvider, String variant, String category) {
        if (!enabled(project)) {
            return
        }
        taskProvider.configure { Task task ->
            markTask(project, task, variant, category)
        }
    }

    static long startStage(Project project, String variant, String stage) {
        if (!enabled(project)) {
            return 0L
        }
        return System.nanoTime()
    }

    static void recordStage(Project project, String variant, String stage, long startedAt) {
        if (startedAt == 0L || !enabled(project)) {
            return
        }
        Registry registry = getRegistry(project)
        if (registry != null) {
            registry.recordStage(project, variant, stage, elapsedMillis(startedAt), [:])
        }
    }

    static void recordStage(Project project,
                            String variant,
                            String stage,
                            long startedAt,
                            Map details) {
        if (startedAt == 0L || !enabled(project)) {
            return
        }
        Registry registry = getRegistry(project)
        if (registry != null) {
            registry.recordStage(project, variant, stage, elapsedMillis(startedAt), details ?: [:])
        }
    }

    static void recordArchive(Project project,
                              String variant,
                              String taskName,
                              File archive,
                              boolean nested) {
        if (!enabled(project) || archive == null || !archive.isFile()) {
            return
        }
        long startedAt = System.nanoTime()
        boolean deepScan = deep(project)
        Map details = [
                taskName: taskName,
                path: archive.absolutePath,
                nested: nested,
                bytes: archive.length(),
                entryCount: 0,
                classesJarBytes: 0L,
                classesJarCompressedBytes: 0L,
                deep: deepScan
        ]
        ZipFile zip = null
        try {
            zip = new ZipFile(archive)
            // 只读 zip 中央目录：不解压任何数据，代价与归档大小无关。
            Enumeration entries = zip.entries()
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement() as ZipEntry
                details.entryCount = (details.entryCount as Integer) + 1
                if (entry.name == 'classes.jar') {
                    details.classesJarBytes = entry.size < 0L ? 0L : entry.size
                    details.classesJarCompressedBytes = entry.compressedSize < 0L ? 0L : entry.compressedSize
                    if (deepScan) {
                        countClassesJarEntries(zip, entry, details)
                    }
                }
            }
        } catch (Exception exception) {
            details.error = exception.class.name + ': ' + exception.message
        } finally {
            zip?.close()
        }
        // 自报扫描开销：诊断测出来的耗时必须能把诊断自身的成本扣掉
        details.scanMillis = elapsedMillis(startedAt)
        Registry registry = getRegistry(project)
        registry?.recordArchive(project, variant, details)
    }

    /**
     * Deep scan: class counting needs the decompressed stream, so it is gated behind
     * -PfataarDiagnosticsDeep=true to keep the default path perturbation-free.
     */
    private static void countClassesJarEntries(ZipFile zip, ZipEntry classesJar, Map details) {
        JarInputStream classes = null
        try {
            classes = new JarInputStream(zip.getInputStream(classesJar))
            ZipEntry classEntry
            while ((classEntry = classes.nextJarEntry) != null) {
                details.classesJarEntryCount = ((details.classesJarEntryCount ?: 0) as Integer) + 1
                if (!classEntry.directory && classEntry.name.endsWith('.class')) {
                    details.classesJarClassCount = ((details.classesJarClassCount ?: 0) as Integer) + 1
                }
            }
        } finally {
            classes?.close()
        }
    }

    private static long elapsedMillis(long startedAt) {
        long elapsed = (System.nanoTime() - startedAt) / 1_000_000L
        return elapsed < 0L ? 0L : elapsed
    }

    private static Registry getRegistry(Project project) {
        Project root = project.rootProject
        if (!root.extensions.extraProperties.has(REGISTRY_PROPERTY)) {
            return null
        }
        return root.extensions.extraProperties.get(REGISTRY_PROPERTY) as Registry
    }

    private static String safeFileName(String variant) {
        String value = variant ?: 'unknown'
        return value.replaceAll('[^A-Za-z0-9._-]', '_')
    }

    private static String timestamp() {
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX").format(new Date())
    }

    private static class Registry implements TaskExecutionListener {
        private final Map<String, Collector> collectors = new ConcurrentHashMap<>()
        private final Map<String, TaskMetadata> taskMetadata = new ConcurrentHashMap<>()
        private final Map<String, Long> activeTasks = new ConcurrentHashMap<>()

        void register(Project project) {
            collectors.computeIfAbsent(project.path) { new Collector(project) }
        }

        void registerTask(Project project, Task task, String variant, String category) {
            register(project)
            taskMetadata.put(task.path, new TaskMetadata(variant, category))
        }

        @Override
        void beforeExecute(Task task) {
            Collector collector = collectors.get(task.project.path)
            if (collector == null) {
                return
            }
            TaskMetadata metadata = taskMetadata.get(task.path)
            if (metadata == null && !isFallbackTask(task)) {
                return
            }
            activeTasks.put(task.path, System.nanoTime())
        }

        @Override
        void afterExecute(Task task, org.gradle.api.tasks.TaskState state) {
            Long startedAt = activeTasks.remove(task.path)
            if (startedAt == null) {
                return
            }
            Collector collector = collectors.get(task.project.path)
            if (collector == null) {
                return
            }
            TaskMetadata metadata = taskMetadata.get(task.path)
            if (metadata == null) {
                metadata = inferMetadata(task)
            }
            collector.recordTask(task, metadata, elapsedMillis(startedAt), state)
        }

        void recordStage(Project project, String variant, String stage, long durationMs, Map details) {
            Collector collector = collectors.get(project.path)
            if (collector != null) {
                collector.recordStage(variant, stage, durationMs, details)
            }
        }

        void recordArchive(Project project, String variant, Map details) {
            Collector collector = collectors.get(project.path)
            if (collector != null) {
                collector.recordArchive(variant, details)
            }
        }

        void writeReports(org.gradle.BuildResult result) {
            collectors.values().each { Collector collector ->
                try {
                    collector.writeReport(result)
                } catch (Exception exception) {
                    collector.project.logger.warn(
                            "[fat-aar][diagnostics] Could not write diagnostics report for " +
                                    "${collector.project.path}: ${exception.message}")
                }
            }
        }

        private static boolean isFallbackTask(Task task) {
            String name = task.name.toLowerCase(Locale.ROOT)
            return name.startsWith('pre') && name.endsWith('embed') ||
                    name.startsWith('explode') ||
                    name.startsWith('mergeclasses') ||
                    name.startsWith('mergeembeddedclasses') ||
                    name.startsWith('mergejars') ||
                    name.startsWith('unpackbundleaar') ||
                    name.startsWith('rebundleaar') ||
                    name.startsWith('mergedatabindingmetadata') ||
                    name.startsWith('mergeembedservicesandkotlin') ||
                    name.contains('transformr')
        }

        private static TaskMetadata inferMetadata(Task task) {
            String name = task.name.toLowerCase(Locale.ROOT)
            String category
            if (name.startsWith('explode')) {
                category = 'explode'
            } else if (name.startsWith('mergeembeddedclasses')) {
                category = 'mergeEmbeddedClasses'
            } else if (name.startsWith('mergeclasses')) {
                category = 'mergeClasses'
            } else if (name.startsWith('mergejars')) {
                category = 'mergeJars'
            } else if (name.startsWith('unpackbundleaar')) {
                category = 'unpackBundleAar'
            } else if (name.startsWith('rebundleaar')) {
                category = 'reBundleAar'
            } else if (name.contains('transformr')) {
                category = 'transformR'
            } else if (name.startsWith('mergedatabindingmetadata')) {
                category = 'dataBinding'
            } else if (name.startsWith('mergeembedservicesandkotlin')) {
                category = 'servicesAndKotlin'
            } else {
                category = 'preEmbed'
            }
            return new TaskMetadata('unknown', category)
        }
    }

    private static class TaskMetadata {
        final String variant
        final String category

        TaskMetadata(String variant, String category) {
            this.variant = variant ?: 'unknown'
            this.category = category ?: 'unknown'
        }
    }

    private static class Collector {
        final Project project
        final List<Map> tasks = Collections.synchronizedList([])
        final List<Map> stages = Collections.synchronizedList([])
        final List<Map> archives = Collections.synchronizedList([])

        Collector(Project project) {
            this.project = project
        }

        void recordTask(Task task, TaskMetadata metadata, long durationMs, org.gradle.api.tasks.TaskState state) {
            tasks.add([
                    taskPath    : task.path,
                    taskName    : task.name,
                    variant     : metadata.variant,
                    category    : metadata.category,
                    durationMs  : durationMs,
                    skipped     : state.skipped,
                    upToDate    : state.upToDate,
                    didWork     : state.didWork,
                    failed      : state.failure != null,
                    failureType : state.failure?.class?.name
            ])
        }

        void recordStage(String variant, String stage, long durationMs, Map details) {
            stages.add([
                    variant   : variant ?: 'unknown',
                    stage     : stage,
                    durationMs: durationMs,
                    details   : details ?: [:]
            ])
        }

        void recordArchive(String variant, Map details) {
            Map event = new LinkedHashMap(details ?: [:])
            event.variant = variant ?: 'unknown'
            archives.add(event)
        }

        void writeReport(org.gradle.BuildResult result) {
            Map<String, List<Map>> tasksByVariant = [:].withDefault { [] }
            tasks.each { Map event -> tasksByVariant[event.variant] << event }
            Map<String, List<Map>> stagesByVariant = [:].withDefault { [] }
            stages.each { Map event -> stagesByVariant[event.variant] << event }
            Map<String, List<Map>> archivesByVariant = [:].withDefault { [] }
            archives.each { Map event -> archivesByVariant[event.variant] << event }

            Set<String> variants = new LinkedHashSet<>()
            variants.addAll(tasksByVariant.keySet())
            variants.addAll(stagesByVariant.keySet())
            variants.addAll(archivesByVariant.keySet())
            if (variants.isEmpty()) {
                variants.add('unknown')
            }

            variants.each { String variant ->
                File report = project.file("${project.buildDir}/${REPORT_DIR}/${safeFileName(variant)}.json")
                report.parentFile.mkdirs()
                Map payload = [
                        schemaVersion: 1,
                        generatedAt  : timestamp(),
                        projectPath  : project.path,
                        projectDir   : project.projectDir.absolutePath,
                        variant      : variant,
                        gradleVersion: project.gradle.gradleVersion,
                        buildSuccess : result.failure == null,
                        tasks        : tasksByVariant[variant] ?: [],
                        stages       : stagesByVariant[variant] ?: [],
                        archives     : archivesByVariant[variant] ?: []
                ]
                report.text = JsonOutput.prettyPrint(JsonOutput.toJson(payload))
                project.logger.lifecycle(
                        "[fat-aar][diagnostics] ${project.path}:${variant} report: ${report.absolutePath}")
                printSummary(variant, payload)
            }
        }

        private void printSummary(String variant, Map payload) {
            Map<String, Long> totals = [:]
            (payload.tasks ?: []).each { Map event ->
                addDuration(totals, event.category as String, event.durationMs)
            }
            (payload.stages ?: []).each { Map event ->
                addDuration(totals, "stage:${event.stage}", event.durationMs)
            }
            String summary = totals.collect { key, value -> "${key}=${value}ms" }.join(', ')
            project.logger.lifecycle("[fat-aar][diagnostics][${project.path}:${variant}] ${summary}")
        }

        private static void addDuration(Map<String, Long> totals, String key, Object value) {
            long duration = value instanceof Number ? ((Number) value).longValue() : 0L
            long current = totals.containsKey(key) ? totals.get(key).longValue() : 0L
            totals.put(key, current + duration)
        }
    }
}

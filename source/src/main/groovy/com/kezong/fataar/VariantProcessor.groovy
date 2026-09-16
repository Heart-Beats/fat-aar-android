package com.kezong.fataar

import com.android.build.gradle.api.LibraryVariant
import com.android.build.gradle.internal.api.DefaultAndroidSourceSet
import com.android.build.gradle.tasks.ManifestProcessorTask
import com.kezong.fataar.tasks.MergeDataBindingMetadataTask
import com.kezong.fataar.tasks.MergeEmbedServicesAndKotlinTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.ResolvedArtifact
import org.gradle.api.artifacts.ResolvedDependency
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.internal.artifacts.ResolvableDependency
import org.gradle.api.internal.tasks.CachingTaskDependencyResolveContext
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskDependency
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.Zip

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
/**
 * Core
 * Processor for variant
 */
class VariantProcessor {

    private final Project mProject

    private final LibraryVariant mVariant

    private Collection<AndroidArchiveLibrary> mAndroidArchiveLibraries = new ArrayList<>()

    private Collection<File> mJarFiles = new ArrayList<>()

    private Collection<Task> mExplodeTasks = new ArrayList<>()

    private VersionAdapter mVersionAdapter

    private TaskProvider mMergeClassTask
    private File mMergedClassesIndex

    private TaskProvider mUnpackBundleTask

    private TaskProvider mRJarTask

    private Map<String, Project> mEmbedProjectsMap

    private Collection<NestedEmbedNode> mNestedEmbedNodes

    private Map<String, SelectedVariantArtifact> mSyntheticArtifactSelections

    VariantProcessor(Project project,
                     LibraryVariant variant,
                     Map<String, Project> embedProjectsMap,
                     Collection<NestedEmbedNode> nestedEmbedNodes = Collections.emptyList(),
                     Map<String, SelectedVariantArtifact> syntheticArtifactSelections = Collections.emptyMap()) {
        mProject = project
        mVariant = variant
        mVersionAdapter = new VersionAdapter(project, variant)
        mEmbedProjectsMap = embedProjectsMap ?: Collections.emptyMap()
        mNestedEmbedNodes = nestedEmbedNodes ?: Collections.emptyList()
        mSyntheticArtifactSelections = syntheticArtifactSelections ?: Collections.emptyMap()
    }

    void addAndroidArchiveLibrary(AndroidArchiveLibrary library) {
        mAndroidArchiveLibraries.add(library)
    }

    void addJarFile(File jar) {
        mJarFiles.add(jar)
    }

    void processVariant(Collection<ResolvedArtifact> artifacts,
                        Collection<ResolvableDependency> dependencies,
                        RClassesTransform transform) {
        String taskPath = 'pre' + mVariant.name.capitalize() + 'Build'
        TaskProvider prepareTask = mProject.tasks.named(taskPath)
        if (prepareTask == null) {
            throw new RuntimeException("Can not find task ${taskPath}!")
        }
        TaskProvider bundleTask = VersionAdapter.getBundleTaskProvider(mProject, mVariant.name)
        preEmbed(artifacts, dependencies, prepareTask)
        processArtifacts(artifacts, prepareTask, bundleTask)
        processClassesAndJars(bundleTask)
        if (mAndroidArchiveLibraries.isEmpty()) {
            configureReBundleAarTask(bundleTask)
            return
        }
        processManifest()
        processResources()
        processAssets()
        processJniLibs()
        processConsumerProguard()
        processGenerateProguard()

        processJavaResourcesHooks()

        processRClasses(transform, bundleTask)

        // 改造点 1：重构或增强原有的 DataBinding 处理（必须在 processRClasses 之后，因为需要 Hook reBundleAar 任务）
        processDataBinding()
    }

    private static void printEmbedArtifacts(Collection<ResolvedArtifact> artifacts,
                                     Collection<ResolvedDependency> dependencies) {
        Collection<String> moduleNames = artifacts.stream().map { it.moduleVersion.id.name }.collect()
        dependencies.each { dependency ->
            if (!moduleNames.contains(dependency.moduleName)) {
                return
            }

            ResolvedArtifact self = dependency.allModuleArtifacts.find { module ->
                module.moduleVersion.id.name == dependency.moduleName
            }

            if (self == null) {
                return
            }

            FatUtils.logAnytime("[embed detected][$self.type]${self.moduleVersion.id}")
            moduleNames.remove(self.moduleVersion.id.name)

            dependency.allModuleArtifacts.each { artifact ->
                if (!moduleNames.contains(artifact.moduleVersion.id.name)) {
                    return
                }
                if (artifact != self) {
                    FatUtils.logAnytime("    - [embed detected][transitive][$artifact.type]${artifact.moduleVersion.id}")
                    moduleNames.remove(artifact.moduleVersion.id.name)
                }
            }
        }

        moduleNames.each { name ->
            ResolvedArtifact artifact = artifacts.find { it.moduleVersion.id.name == name }
            if (artifact != null) {
                FatUtils.logAnytime("[embed detected][$artifact.type]${artifact.moduleVersion.id}")
            }
        }
    }

    private void preEmbed(Collection<ResolvedArtifact> artifacts,
                          Collection<ResolvedDependency> dependencies,
                          TaskProvider prepareTask) {
        TaskProvider embedTask = mProject.tasks.register("pre${mVariant.name.capitalize()}Embed") {
            doFirst {
                printEmbedArtifacts(artifacts, dependencies)
            }
        }

        prepareTask.configure {
            dependsOn embedTask
        }
    }

    private TaskProvider configureReBundleAarTask(TaskProvider bundleTask) {
        File sourceAar = createAarOutputFile(bundleTask)
        File reBundleDir = DirectoryManager.getReBundleDirectory(mProject, mVariant)
        File finalAar = DirectoryManager.getFinalAarFile(mProject, mVariant, sourceAar)
        String variantName = mVariant.name.capitalize()

        mUnpackBundleTask = mProject.tasks.register("unpackBundleAar${variantName}", Sync.class) {
            it.dependsOn(bundleTask)
            it.from mProject.zipTree(sourceAar)
            it.into reBundleDir
            it.include "**"
            // 关键：用惰性 provider 跟踪 bundle 任务的真实 archive 输出。
            // 原先在配置期就把 archive 路径算成 File，AGP 的 archive 名/内容变化无法可靠触发本任务，
            // 会导致「bundle aar 已更新，但 reBundle 仍复用旧解包目录」→ 旧类逐层传到顶层 fat aar。
            if (FatUtils.compareVersion(mProject.gradle.gradleVersion, "5.1") >= 0) {
                it.inputs.files(bundleTask.map { Task t -> ((org.gradle.api.tasks.bundling.Zip) t).archiveFile })
                        .withPathSensitivity(PathSensitivity.RELATIVE)
            } else {
                it.inputs.file(sourceAar).withPathSensitivity(PathSensitivity.RELATIVE)
            }
            it.outputs.dir(reBundleDir)
        }

        String taskName = "reBundleAar${variantName}"
        TaskProvider task = mProject.getTasks().register(taskName, Zip.class) {
            it.dependsOn(mUnpackBundleTask)
            it.from reBundleDir
            it.include "**"

            try {
                def strategyClass = Class.forName("org.gradle.api.file.DuplicatesStrategy")
                it.setDuplicatesStrategy(strategyClass.getField("INCLUDE").get(null))
            } catch (Exception ignore) {
                // Older Gradle versions don't have this or it's not strict.
            }

            if (FatUtils.compareVersion(mProject.gradle.gradleVersion, "5.1") >= 0) {
                it.getArchiveFileName().set(finalAar.name)
                it.getDestinationDirectory().set(finalAar.parentFile)
            } else {
                it.archiveName = finalAar.name
                it.destinationDir = finalAar.parentFile
            }

            it.inputs.dir(reBundleDir)
            it.outputs.file(finalAar)
            doLast {
                FatUtils.logAnytime(" target: ${finalAar.absolutePath} [${FatUtils.formatDataSize(finalAar.size())}]")
            }
        }
        Task assembleTask = mProject.tasks.findByName("assemble${variantName}")
        if (assembleTask != null) {
            assembleTask.dependsOn(task)
        }
        return task
    }

    private File createAarOutputFile(TaskProvider bundleTask) {
        Task task = bundleTask.get()
        if (FatUtils.compareVersion(mProject.gradle.gradleVersion, "5.1") >= 0) {
            return new File(task.getDestinationDirectory().getAsFile().get(), task.getArchiveFileName().get())
        }
        return new File(task.destinationDir, task.archiveName)
    }

    /** 注入索引文件（路径确定，可安全用于 inputs.file 声明） */
    private File mergedClassesIndexFile() {
        File mergeDir = DirectoryManager.getMergeClassDirectory(mProject, mVariant)
        return new File(mergeDir.parentFile, "merged-classes-${mVariant.name}.index")
    }

    private void processRClasses(RClassesTransform transform, TaskProvider<Task> bundleTask) {
        TaskProvider reBundleTask = configureReBundleAarTask(bundleTask)
        TaskProvider transformTask = mProject.tasks.named("transformClassesWith${transform.name.capitalize()}For${mVariant.name.capitalize()}")
        transformTask.configure {
            it.dependsOn(mMergeClassTask)
        }
        if (mProject.fataar.transformR) {
            transformRClasses(transform, transformTask, bundleTask, reBundleTask)
        } else {
            generateRClasses(bundleTask, reBundleTask)
        }
    }

    private void transformRClasses(RClassesTransform transform, TaskProvider transformTask, TaskProvider bundleTask, TaskProvider reBundleTask) {
        transform.putTargetPackage(mVariant.name, mVariant.getApplicationId())
        transformTask.configure {
            doFirst {
                // 需改写的包 = 「会被打进本次 fat aar 的全部包」，而不是仅直接 embed 的库：
                // 模块代码可合法引用编译期任意内部模块的 R（api 依赖链），这些引用同样必须改写到目标包，
                // 否则最终 aar 只会有一个聚合 R，运行期将抛 NoClassDefFoundError: <pkg>.R$xxx。
                transform.putLibraryPackages(mVariant.name, collectRepackagedPackages())
            }
        }
        reBundleTask.configure {
            dependsOn(transformTask)
        }
    }

    /**
     * 收集「会被打进本次 fat aar 的全部包名」：
     * 1) 直接 embed 的 aar 包（兜底：保证合并类目录尚未产出时也可用）；
     * 2) 合并（explode）后类目录下出现的所有包——覆盖嵌套 / 深层模块以及 embed 的本地 aar。
     * 未 embed 的外部依赖不在该目录中，其 R 引用天然不会被改写（消费方会提供对应 R）。
     */
    private Collection<String> collectRepackagedPackages() {
        Set<String> packages = new LinkedHashSet<>()
        mAndroidArchiveLibraries.each { lib ->
            String pkg = lib.packageName
            if (pkg != null && !pkg.isEmpty()) {
                packages.add(pkg)
            }
        }
        File mergeDir = DirectoryManager.getMergeClassDirectory(mProject, mVariant)
        if (mergeDir != null && mergeDir.exists()) {
            mergeDir.eachFileRecurse { File f ->
                if (f.isFile() && f.name.endsWith('.class')) {
                    String rel = mergeDir.toPath().relativize(f.toPath()).toString().replace('\\', '/')
                    int idx = rel.lastIndexOf('/')
                    if (idx > 0) {
                        packages.add(rel.substring(0, idx).replace('/', '.'))
                    }
                }
            }
        }
        FatUtils.logAnytime('[fat-aar][R] repackaged packages of ' + mProject.path + ':' + mVariant.name + ' = ' + packages.size())
        return packages
    }

    private void generateRClasses(TaskProvider<Task> bundleTask, TaskProvider<Task> reBundleTask) {
        RClassesGenerate rClassesGenerate = new RClassesGenerate(mProject, mVariant, mAndroidArchiveLibraries)
        TaskProvider rTask = rClassesGenerate.configure(bundleTask)
        mRJarTask = rTask
        reBundleTask.configure {
            dependsOn(rTask)
        }
    }

    private void processDataBinding() {
        String variantName = mVariant.name.capitalize()
        File reBundleDir = DirectoryManager.getReBundleDirectory(mProject, mVariant)
        File mergedDbBaseDir = mProject.file("${mProject.buildDir}/intermediates/merged_databinding_final/${mVariant.name}")
        Collection<File> metadataRoots = new LinkedHashSet<>()
        metadataRoots.add(reBundleDir)
        metadataRoots.addAll(mAndroidArchiveLibraries.collect { it.rootFolder })

        def mergeDbMetadataTask = mProject.tasks.register("mergeDataBindingMetadata${variantName}", MergeDataBindingMetadataTask) {
            it.variant = mVariant
            it.metadataRoots = metadataRoots
            it.mergedDbBaseDir = mergedDbBaseDir
            it.dependsOn(mUnpackBundleTask)
            it.dependsOn(mExplodeTasks)
            if (mRJarTask != null) {
                it.dependsOn(mRJarTask)
            }
        }

        mProject.tasks.named("reBundleAar${variantName}").configure {
            it.dependsOn(mergeDbMetadataTask)
            it.eachFile { file ->
                if (file.file.absolutePath.replace('\\', '/').startsWith(reBundleDir.absolutePath.replace('\\', '/') + '/')) {
                    if (file.path.startsWith("data-binding/") || file.path.startsWith("data-binding-base-class-log/")) {
                        file.exclude()
                    }
                }
            }
            it.from(mergedDbBaseDir) {
                include "data-binding/**"
                include "data-binding-base-class-log/**"
            }
        }
    }

    private void processJavaResourcesHooks() {
        String variantName = mVariant.name.capitalize()
        def processJavaResTask = mProject.tasks.findByName("process${variantName}JavaRes")
        if (processJavaResTask == null) {
            return
        }

        File mergedServicesDir = mProject.file("${mProject.buildDir}/intermediates/merged_services/${mVariant.name}")
        File extractedKotlinModulesDir = mProject.file("${mProject.buildDir}/intermediates/extracted_kotlin_modules/${mVariant.name}")
        Collection<File> embeddedRoots = mAndroidArchiveLibraries.collect { it.rootFolder }
        Collection<File> serviceSourceDirectories = getLocalServiceSourceDirectories()
        def mergeServiceAndKotlinTask = mProject.tasks.register("mergeEmbedServicesAndKotlin${variantName}", MergeEmbedServicesAndKotlinTask) {
            it.variant = mVariant
            it.embeddedAarRoots = embeddedRoots
            it.localServiceSourceDirectories = serviceSourceDirectories
            it.mergedServicesDir = mergedServicesDir
            it.extractedKotlinModulesDir = extractedKotlinModulesDir
            it.dependsOn(mExplodeTasks)
        }

        processJavaResTask.dependsOn(mergeServiceAndKotlinTask)
        processJavaResTask.exclude { details ->
            String sourcePath = details.file.absolutePath.replace('\\', '/')
            if (sourcePath.contains('/merged_services/') || sourcePath.contains('/extracted_kotlin_modules/')) {
                return false
            }
            if (details.path.startsWith('META-INF/services/')) {
                return true
            }
            return embeddedRoots.any { File root ->
                String rootPath = root.absolutePath.replace('\\', '/') + '/'
                sourcePath.startsWith(rootPath) && details.path.endsWith('.kotlin_module')
            }
        }
        processJavaResTask.from(mergedServicesDir) {
            include "META-INF/services/*"
        }
        processJavaResTask.from(extractedKotlinModulesDir) {
            into "META-INF"
        }
    }

    private Collection<File> getLocalServiceSourceDirectories() {
        LinkedHashSet<String> sourceSetNames = new LinkedHashSet<>()
        sourceSetNames.add('main')
        sourceSetNames.add(mVariant.buildType.name)
        sourceSetNames.add(mVariant.flavorName)
        sourceSetNames.add(mVariant.name)
        mVariant.productFlavors.each { flavor ->
            sourceSetNames.add(flavor.name)
        }

        LinkedHashSet<File> directories = new LinkedHashSet<>()
        mProject.android.sourceSets.each { DefaultAndroidSourceSet sourceSet ->
            if (sourceSetNames.contains(sourceSet.name)) {
                directories.addAll(sourceSet.resources.srcDirs)
            }
        }
        return directories
    }

    // gradle < 6, return TaskDependency
    // gradle >= 6, return TaskDependencyContainer
    static def getTaskDependency(ResolvedArtifact artifact) {
        try {
            return artifact.buildDependencies
        } catch(MissingPropertyException ignore) {
            // since gradle 6.8.0, property is changed;
            return artifact.builtBy
        }
    }

    private SelectedVariantArtifact findSyntheticArtifactSelection(ResolvedArtifact artifact) {
        return mSyntheticArtifactSelections.get(normalizedPath(artifact.file))
    }

    private Project findEmbeddedProject(ResolvedArtifact artifact) {
        SelectedVariantArtifact syntheticSelection = findSyntheticArtifactSelection(artifact)
        if (syntheticSelection != null) {
            return syntheticSelection.project
        }

        def componentIdentifier
        try {
            componentIdentifier = artifact.id.componentIdentifier
        } catch (Exception exception) {
            throw new GradleException("Cannot determine the component identity for embedded artifact " +
                    "'${artifact.file.absolutePath}' in project '${mProject.path}'.", exception)
        }
        if (componentIdentifier instanceof ProjectComponentIdentifier) {
            return mEmbedProjectsMap.get(componentIdentifier.projectPath)
        }
        return null
    }

    private NestedEmbedNode findNestedNode(ResolvedArtifact artifact) {
        SelectedVariantArtifact syntheticSelection = findSyntheticArtifactSelection(artifact)
        Project artifactProject = syntheticSelection == null ? findEmbeddedProject(artifact) : syntheticSelection.project
        if (artifactProject == null) {
            return null
        }

        Collection<NestedEmbedNode> candidates = mNestedEmbedNodes.findAll { NestedEmbedNode node ->
            return node.parentProject.path == mProject.path &&
                    node.childProject.path == artifactProject.path &&
                    node.requestedVariant == mVariant.name &&
                    (syntheticSelection == null || node.selection.variant.name == syntheticSelection.variant.name)
        }
        return selectNestedNode(artifact, artifactProject, syntheticSelection, candidates)
    }

    private NestedEmbedNode selectNestedNode(ResolvedArtifact artifact,
                                             Project artifactProject,
                                             SelectedVariantArtifact syntheticSelection,
                                             Collection<NestedEmbedNode> candidates) {
        if (candidates.isEmpty()) {
            return null
        }
        if (candidates.size() == 1) {
            return candidates.first()
        }

        String selectedVariant = syntheticSelection == null ? '<resolved by Gradle>' : syntheticSelection.variant.name
        String nodeVariants = candidates.collect { it.selection.variant.name }.unique().join(', ')
        throw new GradleException("Ambiguous nested embed node for parent '${mProject.path}', child " +
                "'${artifactProject.path}', requested variant '${mVariant.name}', selected variant " +
                "'${selectedVariant}', artifact '${artifact.file.absolutePath}'. Candidate selected variants: ${nodeVariants}.")
    }

    private static String normalizedPath(File file) {
        return file.absoluteFile.toPath().normalize().toString()
    }

    private static Set<Task> getTaskDependencies(ResolvedArtifact artifact) {
        def taskDependency = getTaskDependency(artifact)
        Set<Task> dependencies = new LinkedHashSet<>()
        if (taskDependency instanceof TaskDependency) {
            dependencies.addAll(taskDependency.getDependencies(null))
            return dependencies
        }

        CachingTaskDependencyResolveContext context = new CachingTaskDependencyResolveContext()
        taskDependency.visitDependencies(context)
        context.queue.each { dependency ->
            dependencies.addAll(dependency.getDependencies(null))
        }
        return dependencies
    }

    private void verifyNestedAarOutput(NestedEmbedNode node) {
        File output = node.finalAarFile
        String details = "parent '${mProject.path}', child '${node.childProject.path}', " +
                "requested variant '${node.requestedVariant}', selected variant '${node.selection.variant.name}', " +
                "task '${node.reBundleTask.name}', source AAR '${node.selection.outputFile.absolutePath}', " +
                "expected final output '${output.absolutePath}'"
        if (!output.isFile() || !output.canRead()) {
            throw new GradleException("Nested fat AAR output is not readable: ${details}")
        }

        java.util.zip.ZipFile aar = null
        try {
            aar = new java.util.zip.ZipFile(output)
            if (aar.getEntry('AndroidManifest.xml') == null) {
                throw new GradleException("Nested fat AAR output is invalid (missing AndroidManifest.xml): ${details}")
            }
        } catch (GradleException exception) {
            throw exception
        } catch (Exception exception) {
            throw new GradleException("Nested fat AAR output is not a readable AAR: ${details}", exception)
        } finally {
            if (aar != null) {
                aar.close()
            }
        }
    }


    /**
     * exploded artifact files
     */
    private void processArtifacts(Collection<ResolvedArtifact> artifacts, TaskProvider<Task> prepareTask, TaskProvider<Task> bundleTask) {
        if (artifacts == null) {
            return
        }
        for (final ResolvedArtifact artifact in artifacts) {
            if (FatAarPlugin.ARTIFACT_TYPE_JAR == artifact.type) {
                addJarFile(artifact.file)
            } else if (FatAarPlugin.ARTIFACT_TYPE_AAR == artifact.type) {
                AndroidArchiveLibrary archiveLibrary = new AndroidArchiveLibrary(mProject, artifact, mVariant.name)
                Project embedProj = findEmbeddedProject(artifact)
                if (embedProj != null && embedProj != mProject) {
                    archiveLibrary.setEmbedProject(embedProj)
                }
                addAndroidArchiveLibrary(archiveLibrary)
                NestedEmbedNode nestedNode = findNestedNode(artifact)
                final def zipFolder = archiveLibrary.getRootFolder()
                zipFolder.mkdirs()
                def group = artifact.getModuleVersion().id.group.capitalize()
                def name = artifact.name.capitalize()
                String taskName = "explode${group}${name}${mVariant.name.capitalize()}"
                Task explodeTask = mProject.tasks.create(taskName, Copy) {
                    File inputAar = nestedNode == null ? artifact.file : nestedNode.finalAarFile
                    from mProject.zipTree(inputAar.absolutePath)
                    into zipFolder
                    // 显式声明输入/输出：子节点（嵌套 embed 的最终 aar）内容变化时必须重新解包，
                    // 否则深层源码改动会被旧解包产物吞掉，并逐层传到顶层 fat aar。
                    inputs.file(inputAar).withPathSensitivity(PathSensitivity.RELATIVE)
                    outputs.dir(zipFolder)

                    doFirst {
                        // Delete previously extracted data.
                        zipFolder.deleteDir()
                        if (nestedNode != null) {
                            verifyNestedAarOutput(nestedNode)
                        }
                    }
                }

                if (nestedNode != null) {
                    explodeTask.dependsOn(nestedNode.reBundleTask)
                } else {
                    Set<Task> dependencies = getTaskDependencies(artifact)
                    if (dependencies.isEmpty()) {
                        explodeTask.dependsOn(prepareTask)
                    } else {
                        explodeTask.dependsOn(dependencies)
                    }
                }
                Task javacTask = mVersionAdapter.getJavaCompileTask()
                javacTask.dependsOn(explodeTask)
                bundleTask.configure {
                    dependsOn(explodeTask)
                }
                mExplodeTasks.add(explodeTask)
            }
        }
    }

    /**
     * merge manifest
     */
    private void processManifest() {
        ManifestProcessorTask processManifestTask = mVersionAdapter.getProcessManifest()

        File manifestOutput
        if (FatUtils.compareVersion(VersionAdapter.AGPVersion, "4.2.0-alpha07") >= 0) {
            manifestOutput = mProject.file("${mProject.buildDir.path}/intermediates/merged_manifest/${mVariant.name}/AndroidManifest.xml")
        } else if (FatUtils.compareVersion(VersionAdapter.AGPVersion, "3.3.0") >= 0) {
            manifestOutput = mProject.file("${mProject.buildDir.path}/intermediates/library_manifest/${mVariant.name}/AndroidManifest.xml")
        } else {
            manifestOutput = mProject.file(processManifestTask.getManifestOutputDirectory().absolutePath + "/AndroidManifest.xml")
        }

        final List<File> inputManifests = new ArrayList<>()
        for (archiveLibrary in mAndroidArchiveLibraries) {
            inputManifests.add(archiveLibrary.getManifest())
        }

        TaskProvider<LibraryManifestMerger> manifestsMergeTask = mProject.tasks.register("merge${mVariant.name.capitalize()}Manifest", LibraryManifestMerger) {
            setGradleVersion(mProject.getGradle().getGradleVersion())
            setGradlePluginVersion(VersionAdapter.AGPVersion)
            setMainManifestFile(manifestOutput)
            setSecondaryManifestFiles(inputManifests)
            setOutputFile(manifestOutput)
        }

        processManifestTask.dependsOn(mExplodeTasks)
        processManifestTask.inputs.files(inputManifests)
        processManifestTask.doLast {
            // Merge manifests
            manifestsMergeTask.get().doTaskAction()
        }
    }

    private TaskProvider handleClassesMergeTask(final boolean isMinifyEnabled) {
        final TaskProvider task = mProject.tasks.register("mergeClasses" + mVariant.name.capitalize()) {
            dependsOn(mExplodeTasks)
            dependsOn(mVersionAdapter.getJavaCompileTask())
            try {
                // main lib maybe not use kotlin
                TaskProvider kotlinCompile = mProject.tasks.named("compile${mVariant.name.capitalize()}Kotlin")
                if (kotlinCompile != null) {
                    dependsOn(kotlinCompile)
                }
            } catch(Exception ignore) {

            }

            inputs.files(mAndroidArchiveLibraries.stream().map { it.classesJarFile }.collect())
                    .withPathSensitivity(PathSensitivity.RELATIVE)
            if (isMinifyEnabled) {
                inputs.files(mAndroidArchiveLibraries.stream().map { it.localJars }.collect())
                        .withPathSensitivity(PathSensitivity.RELATIVE)
                inputs.files(mJarFiles).withPathSensitivity(PathSensitivity.RELATIVE)
            }
            File outputDir = DirectoryManager.getMergeClassDirectory(mProject, mVariant)
            File javacDir = mVersionAdapter.getClassPathDirFiles().first()
            outputs.dir(outputDir)
            // 注入索引：记录「本次注入到 javac 目录的类」及其内容哈希。
            // 作用一（幂等）：下次注入前按索引精确删除上次注入的文件，避免上游删类后的残留；
            // 作用二（可跟踪）：作为本任务输出、并被下游打包任务作为输入，内容变化即触发重新打包。
            mMergedClassesIndex = mergedClassesIndexFile()
            outputs.file(mMergedClassesIndex)

            doFirst {
                // 幂等：先按上次的注入索引，把上次注入到 javac 目录的文件删干净（含已从产物中移除的类）
                if (mMergedClassesIndex != null && mMergedClassesIndex.exists()) {
                    mMergedClassesIndex.eachLine { String line ->
                        int sep = line.indexOf(' ')
                        if (sep > 0) {
                            File injected = new File(javacDir, line.substring(sep + 1))
                            if (injected.exists()) {
                                injected.delete()
                            }
                        }
                    }
                }
                // Extract relative paths and delete previous output.
                def pathsToDelete = new ArrayList<Path>()
                mProject.fileTree(outputDir).forEach {
                    pathsToDelete.add(Paths.get(outputDir.absolutePath).relativize(Paths.get(it.absolutePath)))
                }
                outputDir.deleteDir()
                // Delete output files from javac dir.
                pathsToDelete.forEach {
                    Files.deleteIfExists(Paths.get("$javacDir.absolutePath/${it.toString()}"))
                }
            }

            doLast {
                ExplodedHelper.processClassesJarInfoClasses(mProject, mAndroidArchiveLibraries, outputDir)
                if (isMinifyEnabled) {
                    ExplodedHelper.processLibsIntoClasses(mProject, mAndroidArchiveLibraries, mJarFiles, outputDir)
                }

                mProject.copy {
                    from outputDir
                    into javacDir
                    exclude 'META-INF/'
                }
                // 写注入索引：<sha1> <相对路径>（按路径排序，保证内容确定）
                def digest = java.security.MessageDigest.getInstance("SHA-1")
                def indexLines = []
                mProject.fileTree(outputDir).forEach { File f ->
                    if (f.isFile() && f.name.endsWith(".class")) {
                        String rel = outputDir.toPath().relativize(f.toPath()).toString().replace('\\', '/')
                        byte[] hash = digest.digest(f.bytes)
                        digest.reset()
                        indexLines.add(hash.encodeHex().toString() + " " + rel)
                    }
                }
                indexLines.sort()
                mMergedClassesIndex.text = indexLines.join(System.lineSeparator())
            }
        }
        return task
    }

    private TaskProvider handleJarMergeTask(final TaskProvider syncLibTask) {
        final TaskProvider task = mProject.tasks.register("mergeJars" + mVariant.name.capitalize()) {
            dependsOn(mExplodeTasks)
            dependsOn(mVersionAdapter.getJavaCompileTask())
            mustRunAfter(syncLibTask)

            inputs.files(mAndroidArchiveLibraries.stream().map { it.libsFolder }.collect())
                    .withPathSensitivity(PathSensitivity.RELATIVE)
            inputs.files(mJarFiles).withPathSensitivity(PathSensitivity.RELATIVE)
            def outputDir = mVersionAdapter.getLibsDirFile()
            outputs.dir(outputDir)

            doFirst {
                ExplodedHelper.processLibsIntoLibs(mProject, mAndroidArchiveLibraries, mJarFiles, outputDir)
            }
        }
        return task
    }

    /**
     * merge classes and jars
     */
    private void processClassesAndJars(TaskProvider<Task> bundleTask) {
        boolean isMinifyEnabled = mVariant.getBuildType().isMinifyEnabled()

        TaskProvider syncLibTask = mProject.tasks.named(mVersionAdapter.getSyncLibJarsTaskPath())
        TaskProvider extractAnnotationsTask = mProject.tasks.named("extract${mVariant.name.capitalize()}Annotations")

        mMergeClassTask = handleClassesMergeTask(isMinifyEnabled)
        // ===== 根治：类合并会把子模块类注入本模块的 javac 输出目录 =====
        // 凡「产出 classes.jar / aar」的任务都必须排在类合并之后执行，否则产物可能基于注入前的类集合
        // （表现为改了源码但 aar 里仍是旧类），并在嵌套 embed 下把旧类逐层传播到顶层 fat aar。
        bundleTask.configure {
            dependsOn(mMergeClassTask)
            mustRunAfter(mMergeClassTask)
        }
        ['bundleLibRuntimeToJar', 'bundleLibCompileToJar'].each { prefix ->
            def packagingTask = mProject.tasks.findByName(prefix + mVariant.name.capitalize())
            if (packagingTask != null) {
                packagingTask.mustRunAfter(mMergeClassTask)
                packagingTask.inputs.file(mergedClassesIndexFile()).withPathSensitivity(PathSensitivity.RELATIVE)
            }
        }
        bundleTask.configure {
            it.inputs.file(mergedClassesIndexFile()).withPathSensitivity(PathSensitivity.RELATIVE)
        }
        syncLibTask.configure {
            dependsOn(mMergeClassTask)
            inputs.files(mAndroidArchiveLibraries.stream().map { it.libsFolder }.collect())
                    .withPathSensitivity(PathSensitivity.RELATIVE)
            inputs.files(mJarFiles).withPathSensitivity(PathSensitivity.RELATIVE)
        }
        extractAnnotationsTask.configure {
            mustRunAfter(mMergeClassTask)
        }

        if (!isMinifyEnabled) {
            TaskProvider mergeJars = handleJarMergeTask(syncLibTask)
            bundleTask.configure {
                dependsOn(mergeJars)
            }
        }
    }

    /**
     * merge R.txt (actually is to fix issue caused by provided configuration) and res
     *
     * Now the same res Id will cause a build exception: Duplicate resources, to encourage you to change res Id.
     * Adding "android.disableResourceValidation=true" to "gradle.properties" can do a trick to skip the exception, but is not recommended.
     */
    private void processResources() {
        String taskPath = "generate" + mVariant.name.capitalize() + "Resources"
        TaskProvider resourceGenTask = mProject.tasks.named(taskPath)
        if (resourceGenTask == null) {
            throw new RuntimeException("Can not find task ${taskPath}!")
        }

        resourceGenTask.configure {
            dependsOn(mExplodeTasks)

            mProject.android.sourceSets.each { DefaultAndroidSourceSet sourceSet ->
                if (sourceSet.name == mVariant.name) {
                    for (archiveLibrary in mAndroidArchiveLibraries) {
                        FatUtils.logInfo("Merge resource，Library res：${archiveLibrary.resFolder}")
                        sourceSet.res.srcDir(archiveLibrary.resFolder)
                    }
                }
            }
        }
    }

    /**
     * merge assets
     *
     * AaptOptions.setIgnoreAssets and AaptOptions.setIgnoreAssetsPattern will work as normal
     */
    private void processAssets() {
        Task assetsTask = mVersionAdapter.getMergeAssets()
        if (assetsTask == null) {
            throw new RuntimeException("Can not find task in variant.getMergeAssets()!")
        }

        assetsTask.dependsOn(mExplodeTasks)
        assetsTask.doFirst {
            mProject.android.sourceSets.each {
                if (it.name == mVariant.name) {
                    for (archiveLibrary in mAndroidArchiveLibraries) {
                        if (archiveLibrary.assetsFolder != null && archiveLibrary.assetsFolder.exists()) {
                            FatUtils.logInfo("Merge assets，Library assets folder：${archiveLibrary.assetsFolder}")
                            it.assets.srcDir(archiveLibrary.assetsFolder)
                        }
                    }
                }
            }
        }
    }

    /**
     * merge jniLibs
     */
    private void processJniLibs() {
        String taskPath = 'merge' + mVariant.name.capitalize() + 'JniLibFolders'
        TaskProvider mergeJniLibsTask = mProject.tasks.named(taskPath)
        if (mergeJniLibsTask == null) {
            throw new RuntimeException("Can not find task ${taskPath}!")
        }

        mergeJniLibsTask.configure {
            dependsOn(mExplodeTasks)

            doFirst {
                for (archiveLibrary in mAndroidArchiveLibraries) {
                    if (archiveLibrary.jniFolder != null && archiveLibrary.jniFolder.exists()) {
                        mProject.android.sourceSets.each {
                            if (it.name == mVariant.name) {
                                it.jniLibs.srcDir(archiveLibrary.jniFolder)
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * merge proguard.txt
     */
    private void processConsumerProguard() {
        String mergeTaskName = 'merge' + mVariant.name.capitalize() + 'ConsumerProguardFiles'
        TaskProvider mergeFileTask = mProject.tasks.named(mergeTaskName)
        if (mergeFileTask == null) {
            throw new RuntimeException("Can not find task ${mergeTaskName}!")
        }

        mergeFileTask.configure {
            dependsOn(mExplodeTasks)
            doLast {
                try {
                    Collection<File> files = mAndroidArchiveLibraries.stream().map { it.proguardRules }.collect()
                    File of
                    if (outputFile instanceof File) {
                        of = outputFile
                    } else {
                        // RegularFileProperty.class
                        of = outputFile.get().asFile
                    }
                    FatUtils.mergeFiles(files, of)
                } catch (Exception e) {
                    FatUtils.logAnytime(("If you see this error message, please submit issue to " +
                            "https://github.com/kezong/fat-aar-android/issues with version of AGP and Gradle. Thank you.")
                    )
                    e.printStackTrace()
                }
            }
        }
    }

    /**
     * merge consumer proguard to generate proguard
     * @since AGP 3.6
     */
    private void processGenerateProguard() {
        TaskProvider mergeGenerateProguardTask
        try {
            String mergeName = 'merge' + mVariant.name.capitalize() + 'GeneratedProguardFiles'
            mergeGenerateProguardTask = mProject.tasks.named(mergeName)
        } catch(Exception ignore) {
            return
        }

        mergeGenerateProguardTask.configure {
            dependsOn(mExplodeTasks)
            doLast {
                try {
                    Collection<File> files = mAndroidArchiveLibraries.stream().map { it.proguardRules }.collect()
                    File of
                    if (outputFile instanceof File) {
                        of = outputFile
                    } else {
                        // RegularFileProperty.class
                        of = outputFile.get().asFile
                    }
                    FatUtils.mergeFiles(files, of)
                } catch (Exception e) {
                    FatUtils.logAnytime(("If you see this error message, please submit issue to " +
                            "https://github.com/kezong/fat-aar-android/issues with version of AGP and Gradle. Thank you.")
                    )
                    e.printStackTrace()
                }
            }
        }
    }
}

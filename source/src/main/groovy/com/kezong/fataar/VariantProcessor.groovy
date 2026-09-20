package com.kezong.fataar

import com.android.build.gradle.api.LibraryVariant
import com.android.build.gradle.internal.api.DefaultAndroidSourceSet
import com.android.build.gradle.tasks.ManifestProcessorTask
import com.kezong.fataar.tasks.MergeDataBindingMetadataTask
import com.kezong.fataar.tasks.MergeEmbedServicesAndKotlinTask
import com.kezong.fataar.tasks.MergeEmbeddedClassesTask
import com.kezong.fataar.tasks.RewriteRClassesTask
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

    private TaskProvider mFinalClassesJar

    private TaskProvider mUnpackBundleTask

    private TaskProvider mReBundleTask

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
                        Collection<ResolvableDependency> dependencies) {
        String taskPath = 'pre' + mVariant.name.capitalize() + 'Build'
        TaskProvider prepareTask = mProject.tasks.named(taskPath)
        if (prepareTask == null) {
            throw new RuntimeException("Can not find task ${taskPath}!")
        }
        TaskProvider bundleTask = VersionAdapter.getBundleTaskProvider(mProject, mVariant.name)
        // unpackBundleAar / reBundleAar 必须在类合并之前创建：
        // 归档级类合并的输入正是本模块薄产物解包后的 classes.jar。
        mReBundleTask = configureReBundleAarTask(bundleTask)
        preEmbed(artifacts, dependencies, prepareTask)
        processArtifacts(artifacts, prepareTask, bundleTask)
        processClassesAndJars(bundleTask)
        if (mAndroidArchiveLibraries.isEmpty()) {
            return
        }
        processManifest()
        processResources()
        processAssets()
        processJniLibs()
        processConsumerProguard()
        processGenerateProguard()

        processJavaResourcesHooks()

        processRClasses(bundleTask)

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
            FatAarDiagnostics.markTask(mProject, it, mVariant.name, 'preEmbed')
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
            FatAarDiagnostics.markTask(mProject, it, mVariant.name, 'unpackBundleAar')
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
            FatAarDiagnostics.markTask(mProject, it, mVariant.name, 'reBundleAar')
            it.dependsOn(mUnpackBundleTask)
            it.from(reBundleDir) {
                it.include "**"
                // classes.jar 由归档级合并 + 单遍 R 改写产出，不再使用 AGP 薄产物里的版本
                it.exclude "classes.jar"
            }

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

    private void processRClasses(TaskProvider<Task> bundleTask) {
        if (mProject.fataar.transformR) {
            // R 改写已由 rewriteRClasses 在类合并阶段完成，无需 AGP Transform。
            return
        }
        generateRClasses(bundleTask, mReBundleTask)
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
            FatAarDiagnostics.markTask(mProject, it, mVariant.name, 'dataBinding')
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
            FatAarDiagnostics.markTask(mProject, it, mVariant.name, 'servicesAndKotlin')
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
                String taskName = "explode${archiveLibrary.getTaskKey()}${mVariant.name.capitalize()}"
                Task explodeTask = mProject.tasks.create(taskName, Copy) {
                    FatAarDiagnostics.markTask(mProject, it, mVariant.name, 'explode')
                    File inputAar = nestedNode == null ? artifact.file : nestedNode.finalAarFile
                    doFirst {
                        FatAarDiagnostics.recordArchive(mProject, mVariant.name, taskName, inputAar, nestedNode != null)
                    }
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

    private TaskProvider handleJarMergeTask(final TaskProvider syncLibTask) {
        final TaskProvider task = mProject.tasks.register("mergeJars" + mVariant.name.capitalize()) {
            FatAarDiagnostics.markTask(mProject, it, mVariant.name, 'mergeJars')
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
        File reBundleDir = DirectoryManager.getReBundleDirectory(mProject, mVariant)
        File ownClassesJar = new File(reBundleDir, "classes.jar")
        File mergedClassesDir = DirectoryManager.getMergedClassesDirectory(mProject, mVariant)

        mFinalClassesJar = mProject.tasks.register("mergeEmbeddedClasses${mVariant.name.capitalize()}",
                MergeEmbeddedClassesTask) {
            FatAarDiagnostics.markTask(mProject, it, mVariant.name, 'mergeEmbeddedClasses')
            it.dependsOn(mUnpackBundleTask)
            it.dependsOn(mExplodeTasks)
            it.ownClassesJar = ownClassesJar
            it.embeddedClassesJars = mAndroidArchiveLibraries.collect { it.classesJarFile } as Set<File>
            if (isMinifyEnabled) {
                Set<File> extra = new LinkedHashSet<>()
                mAndroidArchiveLibraries.each { extra.addAll(it.localJars) }
                extra.addAll(mJarFiles)
                it.extraClassesJars = extra
            }
            it.outputJar = new File(mergedClassesDir, "merged-classes.jar")
        }

        if (mProject.fataar.transformR) {
            TaskProvider mergeTask = mFinalClassesJar
            mFinalClassesJar = mProject.tasks.register("rewriteRClasses${mVariant.name.capitalize()}",
                    RewriteRClassesTask) {
                FatAarDiagnostics.markTask(mProject, it, mVariant.name, 'rewriteRClasses')
                it.dependsOn(mergeTask)
                it.mergedClassesJar = new File(mergedClassesDir, "merged-classes.jar")
                it.targetPackage = mVariant.getApplicationId()
                it.extraRepackagedPackages = mProject.provider {
                    mAndroidArchiveLibraries.collect { it.packageName } as Set<String>
                }
                it.outputJar = new File(mergedClassesDir, "rewritten-classes.jar")
            }
        }

        syncLibTask.configure {
            inputs.files(mAndroidArchiveLibraries.stream().map { it.libsFolder }.collect())
                    .withPathSensitivity(PathSensitivity.RELATIVE)
            inputs.files(mJarFiles).withPathSensitivity(PathSensitivity.RELATIVE)
        }

        // 最终产物用「合并 + 单遍改写」后的 classes.jar 组装。
        // 必须写在这里：此时 mFinalClassesJar 已赋值，flatMap 才能取到它的 outputJar。
        mReBundleTask.configure {
            dependsOn(mFinalClassesJar)
            it.from(mFinalClassesJar.map { Task t -> t.outputJar }) { spec ->
                spec.rename { 'classes.jar' }
            }
            it.inputs.file(mFinalClassesJar.map { Task t -> t.outputJar })
                    .withPathSensitivity(PathSensitivity.RELATIVE)
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

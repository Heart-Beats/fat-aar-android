package com.kezong.fataar

import com.android.build.gradle.api.LibraryVariant
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.GradleException
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.ResolvedArtifact
import org.gradle.api.artifacts.ResolvedDependency

/**
 * plugin entry
 */
class FatAarPlugin implements Plugin<Project> {

    public static final String ARTIFACT_TYPE_AAR = 'aar'

    public static final String ARTIFACT_TYPE_JAR = 'jar'

    private static final String CONFIG_NAME = "embed"

    public static final String CONFIG_SUFFIX = 'Embed'

    private Project project

    private final Collection<Configuration> embedConfigurations = new ArrayList<>()

    final Map<String, Collection<FlattenedEmbedNode>> flattenedEmbedNodesByVariant = new LinkedHashMap<>()

    final Map<String, Collection<FlattenedEmbedArtifact>> flattenedEmbedArtifactsByVariant = new LinkedHashMap<>()

    @Override
    void apply(Project project) {
        this.project = project
        checkAndroidPlugin()
        FatUtils.attach(project)
        DirectoryManager.attach(project)
        FatAarDiagnostics.attach(project)
        project.extensions.create(FatAarExtension.NAME, FatAarExtension)
        createConfigurations()
        FatUtils.logAnytime("fat-aar plugin applied to project '${project.path}' (build: ${getBuildDateTime()})")
        registerProjectsEvaluatedHandler()
    }

    private static String getBuildDateTime() {
        try {
            def props = new Properties()
            def stream = FatAarPlugin.class.classLoader.getResourceAsStream('fat-aar-build.properties')
            if (stream != null) {
                props.load(stream)
                stream.close()
                return props.getProperty('build.date_time', 'unknown')
            }
        } catch (Exception ignore) {}
        return 'unknown'
    }

    private void doAfterEvaluate() {
        project.android.libraryVariants.all { variant ->
            Collection<ResolvedArtifact> artifacts = new ArrayList()
            Map<String, SelectedVariantArtifact> syntheticArtifactSelections = new LinkedHashMap<>()
            Collection<ResolvedDependency> firstLevelDependencies = new ArrayList<>()
            getApplicableEmbedConfigurations(project, variant as LibraryVariant).each { configuration ->
                Collection<ResolvedArtifact> resolvedArtifacts = resolveArtifacts(configuration)
                artifacts.addAll(resolvedArtifacts)
                artifacts.addAll(dealUnResolveArtifacts(configuration, variant as LibraryVariant,
                        resolvedArtifacts, syntheticArtifactSelections))
                firstLevelDependencies.addAll(configuration.resolvedConfiguration.firstLevelModuleDependencies)
            }

            // Collect embed project map using ProjectDependency (same as hook script)
            Map<String, Project> embedProjectsMap = new HashMap<>()
            getApplicableEmbedConfigurations(project, variant as LibraryVariant).each { configuration ->
                configuration.dependencies.each { dep ->
                    if (dep instanceof ProjectDependency) {
                        Project p = dep.dependencyProject
                        embedProjectsMap.put(p.path, p)
                    }
                }
            }

            if (!artifacts.isEmpty()) {
                Collection<FlattenedEmbedNode> flattenedEmbedNodes =
                        flattenedEmbedNodesByVariant.get(variant.name) ?: Collections.emptyList()
                Collection<FlattenedEmbedArtifact> flattenedEmbedArtifacts =
                        flattenedEmbedArtifactsByVariant.get(variant.name) ?: Collections.emptyList()
                def processor = new VariantProcessor(project, variant, embedProjectsMap, flattenedEmbedNodes,
                        flattenedEmbedArtifacts, syntheticArtifactSelections)
                processor.processVariant(artifacts, firstLevelDependencies)
            }
        }
    }

    static Collection<Configuration> getApplicableEmbedConfigurations(Project project, LibraryVariant variant) {
        LinkedHashSet<Configuration> configurations = new LinkedHashSet<>()
        [CONFIG_NAME,
         variant.buildType.name + CONFIG_SUFFIX,
         variant.flavorName ? variant.flavorName + CONFIG_SUFFIX : null,
         variant.name + CONFIG_SUFFIX].findAll { it != null }.each { name ->
            Configuration configuration = project.configurations.findByName(name)
            if (configuration != null) {
                configurations.add(configuration)
            }
        }
        return configurations
    }

    static Collection<Configuration> getNonEmptyApplicableEmbedConfigurations(Project project, LibraryVariant variant) {
        return getApplicableEmbedConfigurations(project, variant).findAll { !it.dependencies.isEmpty() }
    }

    private void registerProjectsEvaluatedHandler() {
        Project root = project.rootProject
        String handlerKey = FatAarPlugin.name + '.projectsEvaluatedHandlerRegistered'
        if (root.extensions.extraProperties.has(handlerKey)) {
            return
        }
        root.extensions.extraProperties.set(handlerKey, true)
        project.gradle.projectsEvaluated {
            Collection<FatAarPlugin> plugins = root.allprojects.collect { candidate ->
                candidate.plugins.findPlugin(FatAarPlugin)
            }.findAll { it != null }
            Set<FatAarPlugin> processed = new LinkedHashSet<>()

            // 先处理被 embed 的子模块：其变体选择与插件应用情况要先就绪，父模块的图校验才有一致视图
            plugins.each { processPluginAfterDependencies(it, processed, new LinkedHashSet<FatAarPlugin>()) }
        }
    }

    private static void processPluginAfterDependencies(FatAarPlugin plugin,
                                                       Set<FatAarPlugin> processed,
                                                       Set<FatAarPlugin> active) {
        if (processed.contains(plugin) || !active.add(plugin)) {
            return
        }
        plugin.embedConfigurations.each { configuration ->
            configuration.dependencies.findAll { it instanceof ProjectDependency }.each { ProjectDependency dependency ->
                FatAarPlugin childPlugin = dependency.dependencyProject.plugins.findPlugin(FatAarPlugin)
                if (childPlugin != null) {
                    processPluginAfterDependencies(childPlugin, processed, active)
                }
            }
        }
        active.remove(plugin)
        // 必须先于任何解析：图校验会解析各节点（含本模块）的 embed 配置，
        // 配置一旦解析就不能再改 transitive。
        plugin.applyTransitiveEmbedSetting()
        plugin.validateNestedEmbedGraphs()
        plugin.doAfterEvaluate()
        processed.add(plugin)
    }

    /**
     * embed 配置默认 transitive=false；开启 fataar.transitive 时改由 POM 传播传递依赖。
     * 必须在配置被解析之前执行（见 processPluginAfterDependencies 的调用顺序）。
     */
    private void applyTransitiveEmbedSetting() {
        embedConfigurations.each {
            if (project.fataar.transitive) {
                it.transitive = true
            }
        }
    }

    private void validateNestedEmbedGraphs() {
        project.android.libraryVariants.all { variant ->
            NestedEmbedGraphValidator validator = new NestedEmbedGraphValidator(project, variant as LibraryVariant)
            Collection<FlattenedEmbedNode> flattened = validator.validate()
            flattenedEmbedNodesByVariant.put(variant.name, flattened)
            flattenedEmbedArtifactsByVariant.put(variant.name, validator.embeddedArtifacts)
        }
    }

    private void createConfigurations() {
        Configuration embedConf = project.configurations.create(CONFIG_NAME)
        createConfiguration(embedConf)
        FatUtils.logInfo("Creating configuration embed")

        project.android.buildTypes.all { buildType ->
            String configName = buildType.name + CONFIG_SUFFIX
            Configuration configuration = project.configurations.create(configName)
            createConfiguration(configuration)
            FatUtils.logInfo("Creating configuration " + configName)
        }

        project.android.productFlavors.all { flavor ->
            String configName = flavor.name + CONFIG_SUFFIX
            Configuration configuration = project.configurations.create(configName)
            createConfiguration(configuration)
            FatUtils.logInfo("Creating configuration " + configName)
            project.android.buildTypes.all { buildType ->
                String variantName = flavor.name + buildType.name.capitalize()
                String variantConfigName = variantName + CONFIG_SUFFIX
                Configuration variantConfiguration = project.configurations.create(variantConfigName)
                createConfiguration(variantConfiguration)
                FatUtils.logInfo("Creating configuration " + variantConfigName)
            }
        }
    }

    private void checkAndroidPlugin() {
        if (!project.plugins.hasPlugin('com.android.library')) {
            throw new GradleException('fat-aar-plugin must be applied in project that' +
                    ' has android library plugin!')
        }
    }

    private void createConfiguration(Configuration embedConf) {
        embedConf.visible = false
        embedConf.transitive = false
        project.gradle.addListener(new EmbedResolutionListener(project, embedConf))
        embedConfigurations.add(embedConf)
    }

    private Collection<ResolvedArtifact> resolveArtifacts(Configuration configuration) {
        def set = new ArrayList()
        if (configuration != null) {
            configuration.resolvedConfiguration.resolvedArtifacts.each { artifact ->
                if (ARTIFACT_TYPE_AAR == artifact.type || ARTIFACT_TYPE_JAR == artifact.type) {
                    //
                } else {
                    throw new GradleException('Only support embed aar and jar dependencies!')
                }
                set.add(artifact)
            }
        }
        return set
    }

    private Collection<ResolvedArtifact> dealUnResolveArtifacts(Configuration configuration,
                                                                LibraryVariant variant,
                                                                Collection<ResolvedArtifact> artifacts,
                                                                Map<String, SelectedVariantArtifact> syntheticArtifactSelections) {
        def artifactList = new ArrayList()
        configuration.resolvedConfiguration.firstLevelModuleDependencies.each { dependency ->
            def match = artifacts.any { artifact ->
                dependency.moduleName == artifact.moduleVersion.id.name
            }

            if (!match) {
                Project producer = findEmbeddedProject(configuration, dependency)
                SelectedVariantArtifact selectedArtifact = FlavorArtifact.selectVariantArtifact(producer, variant)
                if (selectedArtifact == null && producer != null) {
                    FatUtils.logError("[$variant.name]Can not resolve :$dependency.moduleName")
                }
                def flavorArtifact = FlavorArtifact.createFlavorArtifact(project, selectedArtifact, dependency)
                if (flavorArtifact != null) {
                    syntheticArtifactSelections.put(normalizedPath(selectedArtifact.outputFile), selectedArtifact)
                    artifactList.add(flavorArtifact)
                }
            }
        }
        return artifactList
    }

    private static String normalizedPath(File file) {
        return file.absoluteFile.toPath().normalize().toString()
    }

    private static Project findEmbeddedProject(Configuration configuration, ResolvedDependency resolvedDependency) {
        Collection<ProjectDependency> candidates = configuration.dependencies.findAll { dependency ->
            dependency instanceof ProjectDependency && dependency.name == resolvedDependency.moduleName
        }
        if (candidates.isEmpty()) {
            return null
        }
        if (candidates.size() > 1) {
            String projectPaths = candidates.collect { it.dependencyProject.path }.join(', ')
            throw new GradleException("Cannot select embedded project for resolved dependency " +
                    "'${resolvedDependency.moduleName}': declared project dependencies ${projectPaths} have the same module name.")
        }
        return candidates.first().dependencyProject
    }
}

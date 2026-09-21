package com.kezong.fataar

import com.android.build.gradle.api.LibraryVariant
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.ResolvedArtifact

class NestedEmbedGraphValidator {

    private final Project rootProject
    private final LibraryVariant rootVariant
    private final Set<String> completed = new LinkedHashSet<>()
    private final Set<String> visitedEdges = new LinkedHashSet<>()
    private final Map<String, String> firstParentByNode = new LinkedHashMap<>()
    private final Map<String, List<String>> firstPathByNode = new LinkedHashMap<>()
    private final Collection<FlattenedEmbedNode> flattenedNodes = new LinkedHashSet<>()
    private final Map<String, String> selectedVariantByProject = new LinkedHashMap<>()
    private final Map<String, List<String>> pathByProject = new LinkedHashMap<>()
    private final Collection<FlattenedEmbedArtifact> embeddedArtifacts = new LinkedHashSet<>()
    private final Map<String, FlattenedEmbedArtifact> embeddedArtifactsByModule = new LinkedHashMap<>()

    NestedEmbedGraphValidator(Project rootProject, LibraryVariant rootVariant) {
        this.rootProject = rootProject
        this.rootVariant = rootVariant
    }

    Collection<FlattenedEmbedNode> validate() {
        String rootKey = variantKey(rootProject, rootVariant)
        walk(rootProject, rootVariant, [rootKey])
        return flattenedNodes
    }

    private void walk(Project parent, LibraryVariant requestedVariant, List<String> activePath) {
        String parentKey = variantKey(parent, requestedVariant)
        FatAarPlugin.getApplicableEmbedConfigurations(parent, requestedVariant).each { configuration ->
            // 本节点声明的「产物级」依赖（原生 AAR / 远程 AAR/JAR）与 project 节点一同扁平，
            // 否则它们会随「中间模块不再逐层合并」而从最终产物里消失。
            collectEmbeddedArtifacts(parent, configuration)
            configuration.dependencies.findAll { it instanceof ProjectDependency }.each { ProjectDependency dependency ->
                Project child = dependency.dependencyProject
                if (!child.plugins.hasPlugin('com.android.library')) {
                    return
                }

                SelectedVariantArtifact selection = FlavorArtifact.selectVariantArtifact(child, requestedVariant)
                if (selection == null) {
                    throw configurationException("Cannot select an Android library variant for nested embed: " +
                            "parent '${parent.path}', child '${child.path}', requested variant '${requestedVariant.name}'.")
                }

                String childKey = variantKey(child, selection.variant)
                String edgeKey = "${parentKey}->${childKey}"
                if (!visitedEdges.add(edgeKey)) {
                    return
                }
                if (activePath.contains(childKey)) {
                    List<String> cycle = new ArrayList<>(activePath)
                    cycle.add(childKey)
                    throw configurationException("Nested embed project dependency cycle detected: ${cycle.join(' -> ')}")
                }

                Collection<Configuration> childConfigurations =
                        FatAarPlugin.getNonEmptyApplicableEmbedConfigurations(child, selection.variant)
                if (!childConfigurations.isEmpty() && !child.plugins.hasPlugin('com.kezong.fat-aar')) {
                    String names = childConfigurations.collect { it.name }.join(', ')
                    throw configurationException("Invalid nested embed configuration: parent '${parent.path}', " +
                            "child '${child.path}', requested variant '${requestedVariant.name}', " +
                            "selected variant '${selection.variant.name}', configurations '${names}'. " +
                            "应用 com.kezong.fat-aar 或改用 implementation/api。")
                }

                List<String> childPath = new ArrayList<>(activePath)
                childPath.add(childKey)
                String previousVariant = selectedVariantByProject.get(child.path)
                if (previousVariant != null && previousVariant != selection.variant.name) {
                    throw configurationException("Nested embed selects conflicting variants for project " +
                            "'${child.path}': '${pathByProject.get(child.path).join(' -> ')}' selects " +
                            "'${previousVariant}', '${childPath.join(' -> ')}' selects '${selection.variant.name}'. " +
                            "A flattened fat AAR can contain only one variant of each descendant.")
                }

                registerPath(parentKey, childKey, activePath)
                // 每个后代各登记一次：消费根要收集它的自有 class（薄产物不含子树 class）
                flattenedNodes.add(new FlattenedEmbedNode(child, requestedVariant, selection, parent == rootProject))
                selectedVariantByProject.put(child.path, selection.variant.name)
                pathByProject.put(child.path, childPath)

                if (childConfigurations.isEmpty()) {
                    // 叶子模块自身没有 embed：它已是扁平节点，消费根会收集它的自有 class。
                    return
                }
                if (completed.add(childKey)) {
                    walk(child, selection.variant, childPath)
                }
            }
        }
    }

    /**
     * 收集某个节点声明的「产物级」依赖：原生 AAR 模块（无 android 插件、仅用 artifacts.add 挂载 aar 的项目）、远程 AAR/JAR。
     * Android library project 依赖不在此列——它们由 FlattenedEmbedNode 承担。
     */
    private void collectEmbeddedArtifacts(Project owner, Configuration configuration) {
        Set<String> projectLibraryNames = new LinkedHashSet<>()
        configuration.dependencies.findAll { it instanceof ProjectDependency }.each { ProjectDependency dependency ->
            Project dependencyProject = dependency.dependencyProject
            if (dependencyProject != null && dependencyProject.plugins.hasPlugin('com.android.library')) {
                projectLibraryNames.add(dependency.name)
            }
        }

        configuration.resolvedConfiguration.resolvedArtifacts.each { ResolvedArtifact artifact ->
            String type = artifact.type
            if (type != FatAarPlugin.ARTIFACT_TYPE_AAR && type != FatAarPlugin.ARTIFACT_TYPE_JAR) {
                return
            }
            if (projectLibraryNames.contains(artifact.moduleVersion.id.name)) {
                return
            }
            FlattenedEmbedArtifact node = new FlattenedEmbedArtifact(owner, artifact)
            FlattenedEmbedArtifact previous = embeddedArtifactsByModule.get(node.moduleKey)
            if (previous != null) {
                if (normalizedPath(previous.file) != normalizedPath(node.file)) {
                    FatUtils.logAnytime("[fat-aar][flatten] '${node.moduleKey}' 被多个节点以不同版本声明：" +
                            "'${previous.file}'（${previous.owner.path}）与 '${node.file}'（${owner.path}），" +
                            "保留先到的一份。请对齐版本，否则最终产物可能出现重复类/资源。")
                }
                return
            }
            embeddedArtifactsByModule.put(node.moduleKey, node)
            embeddedArtifacts.add(node)
        }
    }

    Collection<FlattenedEmbedArtifact> getEmbeddedArtifacts() {
        return embeddedArtifacts
    }

    private static String normalizedPath(File file) {
        return file == null ? '' : file.absoluteFile.toPath().normalize().toString()
    }

    private void registerPath(String parentKey, String childKey, List<String> activePath) {
        String firstParent = firstParentByNode.get(childKey)
        if (firstParent == null) {
            firstParentByNode.put(childKey, parentKey)
            List<String> firstPath = new ArrayList<>(activePath)
            firstPath.add(childKey)
            firstPathByNode.put(childKey, firstPath)
            return
        }
        if (firstParent != parentKey) {
            List<String> secondPath = new ArrayList<>(activePath)
            secondPath.add(childKey)
            throw configurationException("Nested embed graph shares descendant '${childKey}' through " +
                    "'${firstPathByNode.get(childKey).join(' -> ')}' and '${secondPath.join(' -> ')}'. " +
                    "The same module would be merged twice into the ancestor fat AAR. " +
                    "Keep exactly one embed path to it and use compileOnly for the other parents.")
        }
    }

    private static String variantKey(Project project, LibraryVariant variant) {
        return "${project.path}@${variant.name}"
    }

    private static GradleException configurationException(String message) {
        // GradleException renders reliably across Gradle versions. ProjectConfigurationException's
        // (String, Throwable)/(String, Iterable) constructors both misbehave with a null cause on
        // Gradle 7+ (NPE inside DefaultMultiCauseException / ProjectConfigurationException).
        return new GradleException(message)
    }
}

package com.kezong.fataar

import com.android.build.gradle.api.LibraryVariant
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.ProjectConfigurationException
import org.gradle.api.Task
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.tasks.TaskProvider

class NestedEmbedGraphValidator {

    private final Project rootProject
    private final LibraryVariant rootVariant
    private final Collection<NestedEmbedNode> nodes = new LinkedHashSet<>()
    private final Set<String> completed = new LinkedHashSet<>()
    private final Set<String> visitedEdges = new LinkedHashSet<>()
    private final Map<String, String> firstParentByNode = new LinkedHashMap<>()
    private final Map<String, List<String>> firstPathByNode = new LinkedHashMap<>()

    NestedEmbedGraphValidator(Project rootProject, LibraryVariant rootVariant) {
        this.rootProject = rootProject
        this.rootVariant = rootVariant
    }

    Collection<NestedEmbedNode> validate() {
        String rootKey = variantKey(rootProject, rootVariant)
        walk(rootProject, rootVariant, [rootKey])
        return nodes
    }

    private void walk(Project parent, LibraryVariant requestedVariant, List<String> activePath) {
        String parentKey = variantKey(parent, requestedVariant)
        FatAarPlugin.getApplicableEmbedConfigurations(parent, requestedVariant).each { configuration ->
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
                if (childConfigurations.isEmpty()) {
                    // The child has no embeds of its own: it merges directly into this
                    // parent's bundle as an ordinary embedded library. Register the path
                    // anyway, so a leaf reached from two different parents within one
                    // closure is reported as duplicated content instead of silently
                    // being merged twice into the ancestor fat AAR.
                    registerPath(parentKey, childKey, activePath)
                    return
                }
                if (!child.plugins.hasPlugin('com.kezong.fat-aar')) {
                    String names = childConfigurations.collect { it.name }.join(', ')
                    throw configurationException("Invalid nested embed configuration: parent '${parent.path}', " +
                            "child '${child.path}', requested variant '${requestedVariant.name}', " +
                            "selected variant '${selection.variant.name}', configurations '${names}'. " +
                            "应用 com.kezong.fat-aar 或改用 implementation/api。")
                }

                TaskProvider reBundleTask = findReBundleTask(child, selection.variant)
                File finalAarFile = finalAarFile(child, selection.variant, selection.outputFile)
                if (reBundleTask == null) {
                    String names = childConfigurations.collect { it.name }.join(', ')
                    String expectedTask = "reBundleAar${selection.variant.name.capitalize()}"
                    throw configurationException("Nested fat AAR cannot produce a final AAR: parent '${parent.path}', " +
                            "child '${child.path}', requested variant '${requestedVariant.name}', " +
                            "selected variant '${selection.variant.name}', configurations '${names}', " +
                            "expected task '${expectedTask}', source AAR '${selection.outputFile.absolutePath}', " +
                            "expected final AAR '${finalAarFile.absolutePath}'. Ensure the child module's nested AAR build produces " +
                            "a final AAR; do not downgrade it to a thin AAR.")
                }
                registerPath(parentKey, childKey, activePath)
                nodes.add(new NestedEmbedNode(parent, child, requestedVariant.name, selection, reBundleTask, finalAarFile))

                if (completed.add(childKey)) {
                    List<String> childPath = new ArrayList<>(activePath)
                    childPath.add(childKey)
                    walk(child, selection.variant, childPath)
                }
            }
        }
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

    private static File finalAarFile(Project project, LibraryVariant variant, File sourceAarFile) {
        Task task = project.tasks.findByName("reBundleAar${variant.name.capitalize()}")
        if (task != null && task.hasProperty('archiveFile')) {
            try {
                return task.archiveFile.get().asFile
            } catch (Exception ignored) {
                // The task may not have configured its archive property yet.
            }
        }
        return DirectoryManager.getFinalAarFile(project, variant, sourceAarFile)
    }

    private static TaskProvider findReBundleTask(Project project, LibraryVariant variant) {
        String taskName = "reBundleAar${variant.name.capitalize()}"
        Task task = project.tasks.findByName(taskName)
        return task == null ? null : project.tasks.named(taskName)
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

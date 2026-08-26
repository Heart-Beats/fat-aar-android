package com.kezong.fataar

import com.android.build.gradle.api.LibraryVariant
import com.android.builder.model.ProductFlavor
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.ModuleVersionIdentifier
import org.gradle.api.artifacts.ResolvedArtifact
import org.gradle.api.artifacts.ResolvedDependency
import org.gradle.api.artifacts.component.ComponentArtifactIdentifier
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.internal.artifacts.DefaultModuleVersionIdentifier
import org.gradle.api.internal.tasks.TaskDependencyContainer
import org.gradle.api.internal.tasks.TaskDependencyResolveContext
import org.gradle.api.tasks.TaskDependency
import org.gradle.api.tasks.TaskProvider
import org.gradle.internal.DisplayName
import org.gradle.internal.Factory
import org.gradle.internal.component.model.DefaultIvyArtifactName

import javax.annotation.Nullable

/**
 * FlavorArtifact
 */
class FlavorArtifact {

    // since 6.8.0
    private static final String CLASS_PreResolvedResolvableArtifact = "org.gradle.api.internal.artifacts.PreResolvedResolvableArtifact";
    // since 6.8.0
    private static final String CLASS_CalculatedValueContainer = "org.gradle.internal.model.CalculatedValueContainer"

    private static final String CLASS_DefaultResolvedArtifact = "org.gradle.api.internal.artifacts.DefaultResolvedArtifact"

    static ResolvedArtifact createFlavorArtifact(Project project, LibraryVariant variant, ResolvedDependency unResolvedArtifact) {
        Project artifactProject = getArtifactProject(project, unResolvedArtifact)
        SelectedVariantArtifact selectedArtifact = selectVariantArtifact(artifactProject, variant)
        if (selectedArtifact == null) {
            FatUtils.logError("[$variant.name]Can not resolve :$unResolvedArtifact.moduleName")
            return null
        }

        ModuleVersionIdentifier identifier = createModuleVersionIdentifier(unResolvedArtifact)
        TaskProvider bundleProvider = selectedArtifact.bundleTask
        File artifactFile = selectedArtifact.outputFile
        DefaultIvyArtifactName artifactName = createArtifactName(artifactFile)
        Factory<File> fileFactory = new Factory<File>() {
            @Override
            File create() {
                return artifactFile
            }
        }
        ComponentArtifactIdentifier artifactIdentifier = createComponentIdentifier(artifactFile)
        if (FatUtils.compareVersion(project.gradle.gradleVersion, "6.0.0") >= 0) {
            TaskDependencyContainer taskDependencyContainer = new TaskDependencyContainer() {
                @Override
                void visitDependencies(TaskDependencyResolveContext taskDependencyResolveContext) {
                    taskDependencyResolveContext.add(createTaskDependency(bundleProvider.get()))
                }
            }
            if (FatUtils.compareVersion(project.gradle.gradleVersion, "6.8.0") >= 0) {
                Object fileCalculatedValue = Class.forName(CLASS_CalculatedValueContainer).newInstance(new DisplayName(){
                    @Override
                    String getCapitalizedDisplayName() {
                        return artifactFile.name
                    }

                    @Override
                    String getDisplayName() {
                        return artifactFile.name
                    }
                }, artifactFile)
                return Class.forName(CLASS_PreResolvedResolvableArtifact).newInstance(
                        identifier,
                        artifactName,
                        artifactIdentifier,
                        fileCalculatedValue,
                        taskDependencyContainer,
                        null
                )
            } else {
                return Class.forName(CLASS_DefaultResolvedArtifact)
                        .newInstance(identifier, artifactName, artifactIdentifier, taskDependencyContainer, fileFactory)
            }
        } else {
            TaskDependency taskDependency = createTaskDependency(bundleProvider.get())
            return Class.forName(CLASS_DefaultResolvedArtifact)
                    .newInstance(identifier, artifactName, artifactIdentifier, taskDependency, fileFactory)
        }
    }

    private static ModuleVersionIdentifier createModuleVersionIdentifier(ResolvedDependency unResolvedArtifact) {
        return new DefaultModuleVersionIdentifier(
                unResolvedArtifact.getModuleGroup(),
                unResolvedArtifact.getModuleName(),
                unResolvedArtifact.getModuleVersion()
        )
    }

    private static DefaultIvyArtifactName createArtifactName(File artifactFile) {
        return new DefaultIvyArtifactName(artifactFile.getName(), "aar", "")
    }

    private static ComponentArtifactIdentifier createComponentIdentifier(final File artifactFile) {
        return new ComponentArtifactIdentifier() {
            @Override
            ComponentIdentifier getComponentIdentifier() {
                return null
            }

            @Override
            String getDisplayName() {
                return artifactFile.name
            }
        }
    }

    private static Project getArtifactProject(Project project, ResolvedDependency unResolvedArtifact) {
        def matchedProjects = project.getRootProject().getAllprojects().findAll { p ->
            unResolvedArtifact.moduleName == p.name
        }
        if (matchedProjects.size() > 1) {
            def projectPaths = matchedProjects.collect { p -> p.path }.join(", ")
            throw new GradleException("Can not resolve embedded project '$unResolvedArtifact.moduleName': multiple projects have this name: $projectPaths")
        }
        return matchedProjects.isEmpty() ? null : matchedProjects.first()
    }

    private static File createArtifactFile(Project project, Task bundle) {
        File output
        if (FatUtils.compareVersion(project.gradle.gradleVersion, "5.1") >= 0) {
            output = new File(bundle.getDestinationDirectory().getAsFile().get(), bundle.getArchiveFileName().get())
        } else {
            output = new File(bundle.destinationDir, bundle.archiveName)
        }
        return output
    }

    public static SelectedVariantArtifact selectVariantArtifact(Project producer, LibraryVariant consumerVariant) {
        if (producer == null) {
            return null
        }

        def producerVariants = producer.android.libraryVariants

        // 1. find same flavor
        def sameFlavorVariant = producerVariants.find { producerVariant ->
            consumerVariant.name == producerVariant.name
        }
        SelectedVariantArtifact selectedArtifact = selectArtifact(producer, sameFlavorVariant)
        if (selectedArtifact != null) {
            return selectedArtifact
        }

        // 2. find buildType
        def buildTypeVariant = producerVariants.find { producerVariant ->
            producerVariant.name == consumerVariant.buildType.name
        }
        selectedArtifact = selectArtifact(producer, buildTypeVariant)
        if (selectedArtifact != null) {
            return selectedArtifact
        }

        // 3. find missingStrategies
        ProductFlavor flavor = consumerVariant.productFlavors.isEmpty() ? consumerVariant.mergedFlavor : consumerVariant.productFlavors.first()
        try {
            def missingStrategyVariant = producerVariants.find { producerVariant ->
                ProductFlavor producerFlavor = producerVariant.productFlavors.isEmpty() ?
                        producerVariant.mergedFlavor : producerVariant.productFlavors.first()
                flavor.missingDimensionStrategies.find { entry ->
                    String toDimension = entry.getKey()
                    String toFlavor = entry.getValue().getFallbacks().first()
                    return toDimension == producerFlavor.dimension
                            && toFlavor == producerFlavor.name
                            && consumerVariant.buildType.name == producerVariant.buildType.name
                }
            }
            return selectArtifact(producer, missingStrategyVariant)
        } catch (Exception ignore) {
            return null
        }
    }

    private static SelectedVariantArtifact selectArtifact(Project producer, LibraryVariant variant) {
        if (variant == null) {
            return null
        }
        try {
            TaskProvider bundleTask = VersionAdapter.getBundleTaskProvider(producer, variant.name as String)
            return new SelectedVariantArtifact(producer, variant, bundleTask, createArtifactFile(producer, bundleTask.get()))
        } catch (Exception ignore) {
            return null
        }
    }

    private static TaskDependency createTaskDependency(Task bundleTask) {
        return new TaskDependency() {
            @Override
            Set<? extends Task> getDependencies(@Nullable Task task) {
                def set = new HashSet()
                set.add(bundleTask)
                return set
            }
        }
    }
}

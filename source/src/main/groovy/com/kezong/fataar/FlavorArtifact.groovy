package com.kezong.fataar

import com.android.build.gradle.api.LibraryVariant
import com.android.builder.model.ProductFlavor
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.UnknownTaskException
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

    static ResolvedArtifact createFlavorArtifact(Project consumer,
                                                 SelectedVariantArtifact selectedArtifact,
                                                 ResolvedDependency unResolvedArtifact) {
        if (selectedArtifact == null) {
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
        if (FatUtils.compareVersion(consumer.gradle.gradleVersion, "6.0.0") >= 0) {
            TaskDependencyContainer taskDependencyContainer = new TaskDependencyContainer() {
                @Override
                void visitDependencies(TaskDependencyResolveContext taskDependencyResolveContext) {
                    taskDependencyResolveContext.add(createTaskDependency(bundleProvider.get()))
                }
            }
            if (FatUtils.compareVersion(consumer.gradle.gradleVersion, "6.8.0") >= 0) {
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
        if (producer == null || !producer.plugins.hasPlugin('com.android.library')) {
            return null
        }

        Collection<LibraryVariant> producerVariants = producer.android.libraryVariants

        // A. Exact variant names still require exact flavor-dimension compatibility.
        SelectedVariantArtifact selectedArtifact = selectArtifact(producer, producerVariants.find { producerVariant ->
            consumerVariant.name == producerVariant.name && exactFlavorsAreCompatible(consumerVariant, producerVariant)
        })
        if (selectedArtifact != null) {
            return selectedArtifact
        }

        LinkedHashSet<String> buildTypes = new LinkedHashSet<>()
        buildTypes.add(consumerVariant.buildType.name)
        buildTypes.addAll(getMatchingFallbacks(consumerVariant.buildType))

        // B. Prefer exact flavor compatibility across all build-type candidates.
        for (String buildType : buildTypes) {
            selectedArtifact = selectCompatibleArtifact(producer, producerVariants, consumerVariant, buildType, true)
            if (selectedArtifact != null) {
                return selectedArtifact
            }
        }

        // C. Only then consider flavor and missing-dimension fallbacks.
        for (String buildType : buildTypes) {
            selectedArtifact = selectCompatibleArtifact(producer, producerVariants, consumerVariant, buildType, false)
            if (selectedArtifact != null) {
                return selectedArtifact
            }
        }
        return null
    }

    private static SelectedVariantArtifact selectCompatibleArtifact(Project producer,
                                                                     Collection<LibraryVariant> producerVariants,
                                                                     LibraryVariant consumerVariant,
                                                                     String buildType,
                                                                     boolean exactFlavorsOnly) {
        Collection<LibraryVariant> compatibleVariants = producerVariants.findAll { producerVariant ->
            producerVariant.buildType.name == buildType &&
                    (exactFlavorsOnly ? exactFlavorsAreCompatible(consumerVariant, producerVariant) :
                            fallbackFlavorsAreCompatible(consumerVariant, producerVariant))
        }
        for (LibraryVariant producerVariant : compatibleVariants.sort { left, right ->
            flavorPreference(consumerVariant, left) <=> flavorPreference(consumerVariant, right)
        }) {
            SelectedVariantArtifact selectedArtifact = selectArtifact(producer, producerVariant)
            if (selectedArtifact != null) {
                return selectedArtifact
            }
        }
        return null
    }

    private static List<Integer> flavorPreference(LibraryVariant consumerVariant, LibraryVariant producerVariant) {
        Map<String, ProductFlavor> consumerFlavors = flavorsByDimension(variantFlavors(consumerVariant))
        Map<String, ProductFlavor> producerFlavors = flavorsByDimension(variantFlavors(producerVariant))
        List<Integer> preference = new ArrayList<>()
        producerFlavors.keySet().sort().each { String dimension ->
            ProductFlavor consumerFlavor = consumerFlavors.get(dimension)
            ProductFlavor producerFlavor = producerFlavors.get(dimension)
            if (consumerFlavor != null) {
                preference.add(flavorFallbackIndex(consumerFlavor.name, getMatchingFallbacks(consumerFlavor), producerFlavor.name))
            } else {
                preference.add(missingDimensionFallbackIndex(consumerVariant, producerFlavor))
            }
        }
        return preference
    }

    private static int flavorFallbackIndex(String name, Collection<String> fallbacks, String candidate) {
        if (name == candidate) {
            return 0
        }
        int index = fallbacks.indexOf(candidate)
        return index < 0 ? Integer.MAX_VALUE : index + 1
    }

    private static int missingDimensionFallbackIndex(LibraryVariant consumerVariant, ProductFlavor producerFlavor) {
        for (ProductFlavor consumerFlavor : strategyFlavors(consumerVariant)) {
            try {
                def strategy = consumerFlavor.missingDimensionStrategies.get(producerFlavor.dimension)
                if (strategy != null) {
                    int index = strategy.fallbacks.indexOf(producerFlavor.name)
                    if (index >= 0) {
                        return index + 1
                    }
                }
            } catch (MissingPropertyException | MissingMethodException ignore) {
                // Older AGP model objects may not expose missingDimensionStrategies.
            }
        }
        return Integer.MAX_VALUE
    }

    private static boolean exactFlavorsAreCompatible(LibraryVariant consumerVariant, LibraryVariant producerVariant) {
        Map<String, ProductFlavor> consumerFlavors = flavorsByDimension(variantFlavors(consumerVariant))
        Map<String, ProductFlavor> producerFlavors = flavorsByDimension(variantFlavors(producerVariant))
        for (Map.Entry<String, ProductFlavor> consumerEntry : consumerFlavors.entrySet()) {
            ProductFlavor producerFlavor = producerFlavors.get(consumerEntry.key)
            if (producerFlavor != null && producerFlavor.name != consumerEntry.value.name) {
                return false
            }
        }
        return producerExtraDimensionsAreAccepted(consumerVariant, consumerFlavors, producerFlavors)
    }

    private static boolean fallbackFlavorsAreCompatible(LibraryVariant consumerVariant, LibraryVariant producerVariant) {
        Map<String, ProductFlavor> consumerFlavors = flavorsByDimension(variantFlavors(consumerVariant))
        Map<String, ProductFlavor> producerFlavors = flavorsByDimension(variantFlavors(producerVariant))
        for (Map.Entry<String, ProductFlavor> consumerEntry : consumerFlavors.entrySet()) {
            ProductFlavor producerFlavor = producerFlavors.get(consumerEntry.key)
            if (producerFlavor != null && consumerEntry.value.name != producerFlavor.name &&
                    !getMatchingFallbacks(consumerEntry.value).contains(producerFlavor.name)) {
                return false
            }
        }
        return producerExtraDimensionsAreAccepted(consumerVariant, consumerFlavors, producerFlavors)
    }

    private static boolean producerExtraDimensionsAreAccepted(LibraryVariant consumerVariant,
                                                               Map<String, ProductFlavor> consumerFlavors,
                                                               Map<String, ProductFlavor> producerFlavors) {
        return producerFlavors.every { String dimension, ProductFlavor producerFlavor ->
            return consumerFlavors.containsKey(dimension) ||
                    matchesMissingDimensionStrategy(consumerVariant, producerFlavor)
        }
    }

    private static Collection<ProductFlavor> variantFlavors(LibraryVariant variant) {
        Collection<ProductFlavor> flavors = variant.productFlavors ?: Collections.emptyList()
        if (!flavors.isEmpty()) {
            return flavors
        }
        return variant.mergedFlavor == null ? Collections.emptyList() : [variant.mergedFlavor]
    }

    private static Map<String, ProductFlavor> flavorsByDimension(Collection<ProductFlavor> flavors) {
        Map<String, ProductFlavor> byDimension = new LinkedHashMap<>()
        flavors.each { ProductFlavor flavor ->
            if (flavor != null && flavor.dimension != null) {
                byDimension.put(flavor.dimension, flavor)
            }
        }
        return byDimension
    }

    private static Collection<ProductFlavor> strategyFlavors(LibraryVariant variant) {
        Collection<ProductFlavor> flavors = new ArrayList<>(variantFlavors(variant))
        if (variant.mergedFlavor != null && !flavors.contains(variant.mergedFlavor)) {
            flavors.add(variant.mergedFlavor)
        }
        return flavors
    }

    private static boolean matchesMissingDimensionStrategy(LibraryVariant consumerVariant, ProductFlavor producerFlavor) {
        return strategyFlavors(consumerVariant).any { ProductFlavor consumerFlavor ->
            try {
                def strategy = consumerFlavor.missingDimensionStrategies.get(producerFlavor.dimension)
                return strategy != null && strategy.fallbacks.contains(producerFlavor.name)
            } catch (MissingPropertyException | MissingMethodException ignore) {
                return false
            }
        }
    }

    private static List<String> getMatchingFallbacks(Object flavorOrBuildType) {
        if (flavorOrBuildType == null) {
            return Collections.emptyList()
        }
        try {
            def fallbacks = flavorOrBuildType.getMatchingFallbacks()
            return fallbacks == null ? Collections.emptyList() : fallbacks.collect { it.toString() }
        } catch (MissingMethodException | MissingPropertyException ignore) {
            return Collections.emptyList()
        }
    }

    private static SelectedVariantArtifact selectArtifact(Project producer, LibraryVariant variant) {
        if (variant == null) {
            return null
        }
        TaskProvider bundleTask
        try {
            bundleTask = VersionAdapter.getBundleTaskProvider(producer, variant.name as String)
        } catch (UnknownTaskException ignore) {
            return null
        }
        try {
            File outputFile = createArtifactFile(producer, bundleTask.get())
            return new SelectedVariantArtifact(producer, variant, bundleTask, outputFile)
        } catch (Exception exception) {
            throw new GradleException("Can not resolve bundle output for project '$producer.path', variant '$variant.name', task '$bundleTask.name'", exception)
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

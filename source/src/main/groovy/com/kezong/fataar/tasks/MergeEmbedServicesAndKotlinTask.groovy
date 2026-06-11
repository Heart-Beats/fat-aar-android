package com.kezong.fataar.tasks

import com.kezong.fataar.AndroidArchiveLibrary
import com.kezong.fataar.FatUtils
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction

/**
 * 严格对照 fit-aar_hook_fix.gradle 实现的 SPI services and Kotlin metadata 合并任务
 */
class MergeEmbedServicesAndKotlinTask extends DefaultTask {

    @Internal
    Collection<AndroidArchiveLibrary> androidArchiveLibraries

    @Internal
    Object variant

    @TaskAction
    void merge() {
        def mergedServicesDir = project.file("${project.buildDir}/intermediates/merged_services/${variant.name}")
        def extractedKotlinModulesDir = project.file("${project.buildDir}/intermediates/extracted_kotlin_modules/${variant.name}")

        if (mergedServicesDir.exists()) mergedServicesDir.deleteDir()
        if (extractedKotlinModulesDir.exists()) extractedKotlinModulesDir.deleteDir()
        mergedServicesDir.mkdirs()
        extractedKotlinModulesDir.mkdirs()

        FatUtils.logAnytime("[SPI/Kotlin][${variant.name}] Starting merge ...")

        // SPI service deduplication container
        Map<String, Set<String>> serviceMap = new HashMap<>()

        // A. Process local dependency chain (main project + all sub-modules)
        def allLocalProjects = [project] + androidArchiveLibraries.findAll { it.embedProject != null }.collect { it.embedProject }
        FatUtils.logAnytime("[SPI/Kotlin][${variant.name}] Local projects: ${allLocalProjects.collect { it.path }.join(', ')}")

        for (proj in allLocalProjects) {
            // A-1. Scan static resources in source sets
            if (proj.hasProperty('android') && proj.android.hasProperty('sourceSets')) {
                proj.android.sourceSets.main.resources.srcDirs.each { resDir ->
                    def servicesFolder = project.file("${resDir}/META-INF/services")
                    if (servicesFolder.exists() && servicesFolder.isDirectory()) {
                        servicesFolder.listFiles().each { file ->
                            if (file.isFile()) {
                                def interfaceName = file.name
                                if (!serviceMap.containsKey(interfaceName)) serviceMap.put(interfaceName, new HashSet<String>())
                                file.readLines('UTF-8').each { line ->
                                    def trimmed = line.trim()
                                    if (!trimmed.isEmpty() && !trimmed.startsWith('#')) serviceMap.get(interfaceName).add(trimmed)
                                }
                            }
                        }
                    }
                }
            }

            // A-2. Scan build intermediates
            def subBuildDir = proj.buildDir
            if (subBuildDir.exists()) {
                subBuildDir.eachFileRecurse { file ->
                    if (file.isFile()) {
                        // SPI files
                        if (file.path.contains("META-INF" + File.separator + "services" + File.separator) &&
                                !file.path.contains("intermediates" + File.separator + "merged_services")) {
                            def interfaceName = file.name
                            if (!serviceMap.containsKey(interfaceName)) serviceMap.put(interfaceName, new HashSet<String>())
                            file.readLines('UTF-8').each { line ->
                                def trimmed = line.trim()
                                if (!trimmed.isEmpty() && !trimmed.startsWith('#')) serviceMap.get(interfaceName).add(trimmed)
                            }
                        }
                        // Scan JAR files (e.g. R.jar or transitive dependencies)
                        else if (file.name.endsWith(".jar") && !file.path.contains("intermediates" + File.separator + "merged_services")) {
                            project.zipTree(file).each { jarFile ->
                                if (jarFile.path.contains("META-INF/services/")) {
                                    def interfaceName = jarFile.name
                                    if (!serviceMap.containsKey(interfaceName)) serviceMap.put(interfaceName, new HashSet<String>())
                                    jarFile.readLines('UTF-8').each { line ->
                                        def trimmed = line.trim()
                                        if (!trimmed.isEmpty() && !trimmed.startsWith('#')) serviceMap.get(interfaceName).add(trimmed)
                                    }
                                }
                                if (proj != project && jarFile.name.endsWith(".kotlin_module")) {
                                    project.copy { from jarFile into extractedKotlinModulesDir }
                                }
                            }
                        }

                        // Kotlin metadata isolation: only from sub-projects
                        if (proj != project && file.name.endsWith(".kotlin_module")) {
                            project.copy { from file into extractedKotlinModulesDir }
                        }
                    }
                }
            }
        }

        // B. Process remote Maven AAR/JAR embed dependencies
        def remoteLibs = androidArchiveLibraries.findAll { it.embedProject == null && it.rootFolder != null && it.rootFolder.exists() }
        FatUtils.logAnytime("[SPI/Kotlin][${variant.name}] Remote AAR/JAR libs: ${remoteLibs.size()}")
        for (archiveLibrary in remoteLibs) {
            archiveLibrary.rootFolder.eachFileRecurse { file ->
                if (file.isFile()) {
                    if (file.path.contains("META-INF/services/")) {
                        def interfaceName = file.name
                        if (!serviceMap.containsKey(interfaceName)) serviceMap.put(interfaceName, new HashSet<String>())
                        file.readLines('UTF-8').each { line ->
                            def trimmed = line.trim()
                            if (!trimmed.isEmpty() && !trimmed.startsWith('#')) serviceMap.get(interfaceName).add(trimmed)
                        }
                    }
                    // Scan JARs inside AAR
                    else if (file.name.endsWith(".jar")) {
                        project.zipTree(file).each { jarFile ->
                            if (jarFile.path.contains("META-INF/services/")) {
                                def interfaceName = jarFile.name
                                if (!serviceMap.containsKey(interfaceName)) serviceMap.put(interfaceName, new HashSet<String>())
                                jarFile.readLines('UTF-8').each { line ->
                                    def trimmed = line.trim()
                                    if (!trimmed.isEmpty() && !trimmed.startsWith('#')) serviceMap.get(interfaceName).add(trimmed)
                                }
                            }
                            if (jarFile.name.endsWith(".kotlin_module")) {
                                project.copy { from jarFile into extractedKotlinModulesDir }
                            }
                        }
                    }
                    // Always extract from remote third-party
                    if (file.name.endsWith('.kotlin_module')) {
                        project.copy { from file into extractedKotlinModulesDir }
                    }
                }
            }
        }

        // C. Write back merged SPI
        FatUtils.logAnytime("[SPI/Kotlin][${variant.name}] Merged ${serviceMap.size()} SPI file(s), kotlin_modules: ${extractedKotlinModulesDir.listFiles()?.size() ?: 0}")
        serviceMap.each { interfaceName, implementations ->
            def outputFile = project.file("${mergedServicesDir}/META-INF/services/${interfaceName}")
            outputFile.parentFile.mkdirs()
            outputFile.withWriter('UTF-8') { writer ->
                writer.write("# Full Merged SPI by fat-aar robust source plugin\n")
                implementations.each { impl -> writer.write("${impl}\n") }
            }
        }
    }
}

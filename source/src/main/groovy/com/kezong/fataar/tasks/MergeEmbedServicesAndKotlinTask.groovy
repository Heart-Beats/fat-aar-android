package com.kezong.fataar.tasks

import com.kezong.fataar.FatUtils
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile

/**
 * 仅从实际嵌入的 AAR 内容合并 SPI 服务和 Kotlin metadata。
 */
class MergeEmbedServicesAndKotlinTask extends DefaultTask {

    @Internal
    Object variant

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    Collection<File> embeddedAarRoots = Collections.emptyList()

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    Collection<File> localServiceSourceDirectories = Collections.emptyList()

    @OutputDirectory
    File mergedServicesDir

    @OutputDirectory
    File extractedKotlinModulesDir

    @TaskAction
    void merge() {
        if (mergedServicesDir.exists()) {
            mergedServicesDir.deleteDir()
        }
        if (extractedKotlinModulesDir.exists()) {
            extractedKotlinModulesDir.deleteDir()
        }
        mergedServicesDir.mkdirs()
        extractedKotlinModulesDir.mkdirs()

        FatUtils.logAnytime("[SPI/Kotlin][${variant.name}] Starting merge ...")
        Map<String, Set<String>> services = new TreeMap<>()
        Map<String, File> kotlinSources = new LinkedHashMap<>()

        for (File sourceDirectory : localServiceSourceDirectories) {
            mergeServicesDirectory(new File(sourceDirectory, "META-INF/services"), services)
        }
        for (File root : embeddedAarRoots) {
            mergeServicesDirectory(new File(root, "META-INF/services"), services)
            mergeJar(new File(root, "classes.jar"), services, kotlinSources)
            File libsDirectory = new File(root, "libs")
            if (libsDirectory.isDirectory()) {
                Set<File> embeddedJars = project.fileTree(libsDirectory).matching { include '**/*.jar' }.files
                for (File jar : embeddedJars) {
                    mergeJar(jar, services, kotlinSources)
                }
            }
        }

        for (Map.Entry<String, Set<String>> service : services.entrySet()) {
            File outputFile = project.file("${mergedServicesDir}/META-INF/services/${service.key}")
            outputFile.parentFile.mkdirs()
            outputFile.withWriter('UTF-8') { writer ->
                writer.write("# Full merged SPI by fat-aar\n")
                service.value.each { String implementation -> writer.write("${implementation}\n") }
            }
        }
        FatUtils.logAnytime("[SPI/Kotlin][${variant.name}] Merged ${services.size()} SPI file(s), " +
                "kotlin_modules: ${kotlinSources.size()}")
    }

    private void mergeServicesDirectory(File directory, Map<String, Set<String>> services) {
        if (!directory.isDirectory()) {
            return
        }
        for (File serviceFile : project.fileTree(directory).files) {
            mergeServiceLines(serviceFile.name, serviceFile.getText('UTF-8'), services)
        }
    }

    private void mergeJar(File jar, Map<String, Set<String>> services, Map<String, File> kotlinSources) {
        if (!jar.isFile()) {
            return
        }
        ZipFile zip = null
        try {
            zip = new ZipFile(jar)
            Enumeration entries = zip.entries()
            while (entries.hasMoreElements()) {
                def entry = entries.nextElement()
                if (entry.directory) {
                    continue
                }
                if (entry.name.startsWith('META-INF/services/')) {
                    InputStream input = zip.getInputStream(entry)
                    try {
                        mergeServiceLines(entry.name.substring('META-INF/services/'.length()), input.getText('UTF-8'), services)
                    } finally {
                        input.close()
                    }
                } else if (entry.name.startsWith('META-INF/') && entry.name.endsWith('.kotlin_module')) {
                    File extracted = new File(extractedKotlinModulesDir, entry.name.substring('META-INF/'.length()))
                    extracted.parentFile.mkdirs()
                    File previous = kotlinSources.get(entry.name)
                    InputStream input = zip.getInputStream(entry)
                    try {
                        byte[] currentBytes = input.bytes
                        if (previous != null) {
                            if (!Arrays.equals(previous.bytes, currentBytes)) {
                                throw new GradleException("Conflicting Kotlin metadata '${entry.name}' from " +
                                        "'${previous.absolutePath}' and '${jar.absolutePath}'.")
                            }
                            continue
                        }
                        Files.write(extracted.toPath(), currentBytes)
                    } finally {
                        input.close()
                    }
                    kotlinSources.put(entry.name, extracted)
                }
            }
        } finally {
            if (zip != null) {
                zip.close()
            }
        }
    }

    private static void mergeServiceLines(String interfaceName, String text, Map<String, Set<String>> services) {
        Set<String> implementations = services.get(interfaceName)
        if (implementations == null) {
            implementations = new TreeSet<String>()
            services.put(interfaceName, implementations)
        }
        text.readLines().each { String line ->
            String trimmed = line.trim()
            if (!trimmed.isEmpty() && !trimmed.startsWith('#')) {
                implementations.add(trimmed)
            }
        }
    }
}

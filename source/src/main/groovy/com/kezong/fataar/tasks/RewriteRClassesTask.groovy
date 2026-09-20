package com.kezong.fataar.tasks

import com.kezong.fataar.FatUtils
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

import javassist.bytecode.ClassFile
import javassist.bytecode.ConstPool

import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * 把「会被打进本次 fat aar 的全部包」的 R 引用改写到本模块 applicationId。
 * 每个 class 先做 /R$ 字节预筛，绝大多数 class 不引用 R，可直接原样写出。
 */
class RewriteRClassesTask extends DefaultTask {

    private static final byte[] R_MARKER = '/R$'.getBytes(StandardCharsets.UTF_8)

    private static final List<String> RESOURCE_TYPES = [
            "anim", "animator", "array", "attr", "bool", "color", "dimen",
            "drawable", "font", "fraction", "id", "integer", "interpolator", "layout", "menu", "mipmap",
            "navigation", "plurals", "raw", "string", "style", "styleable", "transition", "xml",
    ].asImmutable()

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    File mergedClassesJar

    @Input
    String targetPackage

    /**
     * 兜底：某些产物只在 R.txt 里出现符号。包名从 AAR Manifest 读取，必须在 explode 之后才能取，
     * 因此用 Provider 惰性求值，避免配置期读取尚不存在的 Manifest。
     */
    @Input
    Provider<Set<String>> extraRepackagedPackages = null

    @OutputFile
    File outputJar

    @TaskAction
    void rewrite() {
        if (!mergedClassesJar.isFile()) {
            throw new GradleException("Merged classes.jar is missing: '${mergedClassesJar.absolutePath}'.")
        }
        Set<String> packages = new LinkedHashSet<>()
        if (extraRepackagedPackages != null && extraRepackagedPackages.present) {
            packages.addAll(extraRepackagedPackages.get())
        }
        ZipFile scanner = new ZipFile(mergedClassesJar)
        try {
            Enumeration entries = scanner.entries()
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement() as ZipEntry
                if (!entry.directory && entry.name.endsWith('.class')) {
                    int slash = entry.name.lastIndexOf('/')
                    if (slash > 0) {
                        packages.add(entry.name.substring(0, slash).replace('/', '.'))
                    }
                }
            }
        } finally {
            scanner.close()
        }

        Map<String, String> table = buildTransformTable(packages)
        outputJar.parentFile.mkdirs()
        ZipFile input = new ZipFile(mergedClassesJar)
        ZipOutputStream output = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(outputJar)))
        long rewritten = 0L
        long copied = 0L
        try {
            Enumeration entries = input.entries()
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement() as ZipEntry
                if (entry.directory) {
                    continue
                }
                byte[] bytes = input.getInputStream(entry).bytes
                byte[] result = bytes
                if (entry.name.endsWith('.class') && indexOf(bytes, R_MARKER) >= 0) {
                    result = rename(entry.name, bytes, table)
                    rewritten++
                } else {
                    copied++
                }
                ZipEntry outEntry = new ZipEntry(entry.name)
                if (entry.time > 0L) {
                    outEntry.time = entry.time
                }
                output.putNextEntry(outEntry)
                output.write(result)
                output.closeEntry()
            }
        } finally {
            output.close()
            input.close()
        }
        FatUtils.logAnytime("[fat-aar][R] '${targetPackage}': rewritten ${rewritten} class(es), " +
                "passthrough ${copied} entr(ies) over ${packages.size()} package(s)")
    }

    private Map<String, String> buildTransformTable(Collection<String> packages) {
        String target = targetPackage.replace('.', '/')
        Map<String, String> table = new LinkedHashMap<>()
        RESOURCE_TYPES.each { String resource ->
            String targetClass = target + '/R$' + resource
            packages.each { String pkg ->
                if (pkg != null && !pkg.isEmpty()) {
                    table.put(pkg.replace('.', '/') + '/R$' + resource, targetClass)
                }
            }
        }
        return table
    }

    private static byte[] rename(String entryName, byte[] bytes, Map<String, String> table) {
        ClassFile classFile = new ClassFile(new DataInputStream(new ByteArrayInputStream(bytes)))
        ConstPool constPool = classFile.getConstPool()
        constPool.renameClass(table)
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(bytes.length)
        DataOutputStream data = new DataOutputStream(buffer)
        classFile.write(data)
        data.flush()
        return buffer.toByteArray()
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        if (needle.length == 0 || haystack.length < needle.length) {
            return -1
        }
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer
                }
            }
            return i
        }
        return -1
    }
}

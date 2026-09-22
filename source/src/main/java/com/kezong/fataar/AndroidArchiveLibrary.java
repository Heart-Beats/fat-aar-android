package com.kezong.fataar;

import org.gradle.api.Project;
import org.gradle.api.artifacts.ResolvedArtifact;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;

public class AndroidArchiveLibrary {

    private final Project mProject;

    private final ResolvedArtifact mArtifact;

    private final String mModuleKey;

    private final String mName;

    private final String mVariantName;

    private File mAarFile;

    private boolean mDirect;

    private String mPackageName;

    /**
     * 当此 archive 来源于本地 Project 依赖（embed project(':xxx')）时，
     * 保存该子项目的真实引用，用于跨项目任务依赖声明。
     * 对于远程 Maven AAR 依赖，此字段为 null。
     */
    private Project mEmbedProject;

    public AndroidArchiveLibrary(Project project, ResolvedArtifact artifact, String variantName) {
        if (!"aar".equals(artifact.getType())) {
            throw new IllegalArgumentException("artifact must be aar type!");
        }
        mProject = project;
        mArtifact = artifact;
        mModuleKey = sanitize(artifact.getModuleVersion().getId().getGroup()
                + "__" + artifact.getModuleVersion().getId().getName()
                + "__" + artifact.getModuleVersion().getId().getVersion());
        mName = artifact.getModuleVersion().getId().getName();
        mVariantName = variantName;
        mAarFile = artifact.getFile();
    }

    /**
     * 合成来源：扁平图里的深层节点没有 ResolvedArtifact，只有「项目 + 选中变体 + 薄产物」。
     *
     * @param project     消费根项目
     * @param moduleKey   解包目录与任务名使用的模块标识
     * @param name        模块名，用于日志与报错
     * @param variantName 选中变体名
     * @param aarFile     该节点的产物文件（薄 aar，或原生/远程 aar）
     */
    public AndroidArchiveLibrary(Project project, String moduleKey, String name, String variantName, File aarFile) {
        mProject = project;
        mArtifact = null;
        mModuleKey = sanitize(moduleKey);
        mName = name;
        mVariantName = variantName;
        mAarFile = aarFile;
    }

    private static String sanitize(String value) {
        return value == null ? "unknown" : value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    public Project getProject() {
        return mProject;
    }

    public String getModuleKey() {
        return mModuleKey;
    }

    public String getName() {
        return mName;
    }

    /**
     * Gradle 任务名安全标识，用于 explode 任务命名。
     *
     * @return 仅含字母、数字与下划线的模块标识
     */
    public String getTaskKey() {
        return mModuleKey.replaceAll("[^A-Za-z0-9]", "_");
    }

    public File getRootFolder() {
        File explodedRootDir = mProject.file(
                mProject.getBuildDir() + "/intermediates" + "/exploded-aar/");
        return mProject.file(explodedRootDir + "/" + mModuleKey + "/" + mVariantName);
    }

    public File getAidlFolder() {
        return new File(getRootFolder(), "aidl");
    }

    public File getAssetsFolder() {
        return new File(getRootFolder(), "assets");
    }

    public File getLibsFolder() {
        return new File(getRootFolder(), "libs");
    }

    public File getClassesJarFile() {
        return new File(getRootFolder(), "classes.jar");
    }

    public Collection<File> getLocalJars() {
        List<File> localJars = new ArrayList<>();
        File[] jarList = getLibsFolder().listFiles();
        if (jarList != null) {
            for (File jars : jarList) {
                if (jars.isFile() && jars.getName().endsWith(".jar")) {
                    localJars.add(jars);
                }
            }
        }

        return localJars;
    }

    public File getJniFolder() {
        return new File(getRootFolder(), "jni");
    }

    public File getResFolder() {
        return new File(getRootFolder(), "res");
    }

    public File getManifest() {
        return new File(getRootFolder(), "AndroidManifest.xml");
    }

    public File getLintJar() {
        return new File(getRootFolder(), "lint.jar");
    }

    public File getProguardRules() {
        return new File(getRootFolder(), "proguard.txt");
    }

    public File getSymbolFile() {
        return new File(getRootFolder(), "R.txt");
    }

    public synchronized String getPackageName() {
        if (mPackageName == null) {
            File manifestFile = getManifest();
            if (manifestFile.exists()) {
                try {
                    DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
                    Document doc = dbf.newDocumentBuilder().parse(manifestFile);
                    Element element = doc.getDocumentElement();
                    mPackageName = element.getAttribute("package");
                } catch (Exception e) {
                    e.printStackTrace();
                }
            } else {
                throw new RuntimeException(getName() + " module's AndroidManifest not found");
            }
        }
        return mPackageName;
    }

    public File getDataBindingFolder() {
        return new File(getRootFolder(), "data-binding");
    }

    public File getDataBindingLogFolder() {
        return new File(getRootFolder(), "data-binding-base-class-log");
    }

    public File getAarFile() {
        return mAarFile;
    }

    public void setAarFile(File aarFile) {
        this.mAarFile = aarFile;
    }

    /**
     * true 表示它是消费根的直接子模块：其薄产物已继承整棵子树的非类内容。
     *
     * @return 直接子模块时为 true，深层节点或产物级节点为 false
     */
    public boolean isDirect() {
        return mDirect;
    }

    public void setDirect(boolean direct) {
        this.mDirect = direct;
    }

    public Project getEmbedProject() {
        return mEmbedProject;
    }

    public void setEmbedProject(Project embedProject) {
        this.mEmbedProject = embedProject;
    }
}

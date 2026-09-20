# fat-aar-android
[![license](https://img.shields.io/badge/license-MIT-blue.svg)](https://github.com/kezong/fat-aar-android/blob/master/LICENSE)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/com.github.kezong/fat-aar/badge.svg)](https://maven-badges.herokuapp.com/maven-central/com.github.kezong/fat-aar)

>**因为我不再从事研发工作，所以该仓库将不再进行维护和更新。<br>**
>**你可以尝试按底下的步骤引入该插件，如果它在新的gradle版本中无法工作，你可以fork或者下载该仓库并且修改它，该项目的代码并不是很复杂。**
>
>**P.S. 希望Google官方能够尽快支持该功能**

该插件提供了将library以及它依赖的library一起打包成一个完整aar的解决方案，支持AGP 3.0及以上。（目前测试的版本范围是AGP 3.0 - 7.1.0，Gradle 4.9 - 7.3）

## 如何使用

#### 第一步: Apply classpath
##### 添加以下代码到你工程根目录下的`build.gradle`文件中:
For Maven Central (The lastest release is available on [Maven Central](https://maven-badges.herokuapp.com/maven-central/com.github.kezong/fat-aar)):
```groovy
buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath 'com.github.kezong:fat-aar:1.3.8'
    }
}
```

#### 第二步: Add plugin
添加以下代码到你的主library的`build.gradle`中:
```groovy
apply plugin: 'com.kezong.fat-aar'
```

#### 第三步: Embed dependencies
- `embed`你所需要的工程, 用法类似`implementation`

代码所示：
```groovy
dependencies {
    implementation fileTree(dir: 'libs', include: '*.jar')
    // java dependency
    embed project(path: ':lib-java', configuration: 'default')
    // 直接依赖的本地 AAR 工程
    embed project(path: ':lib-aar', configuration: 'default')
    // 仅在 flavor1 打包的本地完整 AAR
    flavor1Embed project(path: ':lib-aar-local', configuration: 'default')
    // local full aar dependency, just build in debug
    debugEmbed(name: 'lib-aar-local2', ext: 'aar')
    // remote jar dependency
    embed 'com.google.guava:guava:20.0'
    // remote aar dependency
    embed 'com.facebook.fresco:fresco:1.12.0'
    // don't want to embed in
    implementation('androidx.appcompat:appcompat:1.2.0')
}
```

### 第四步: 执行assemble命令

- 在你的工程目录下执行assemble指令，其中lib-main为你主library的工程名称，你可以根据不同的flavor以及不同的buildType来决定执行具体的assemble指令
```shell script
# assemble all 
./gradlew :lib-main:assemble

# assemble debug
./gradlew :lib-main:assembleDebug

# assemble flavor
./gradlew :lib-main:assembleFlavor1Debug
```
最终合并产物位于 `build/outputs/fat-aar/<variant>/`，与 AGP 生成的原始 source AAR 分离，并会在日志中打印其路径。

### 构建性能诊断

需要分析 fat AAR 构建耗时时，可临时开启只读诊断：

```shell script
./gradlew :lib-main:assembleFlavor1Debug -PfataarDiagnostics=true
```

诊断报告写入每个启用插件的模块目录 `build/intermediates/fat-aar/diagnostics/<variant>.json`，包含各阶段任务耗时、是否命中 `UP-TO-DATE`、嵌入 AAR 的大小等信息。默认不开启诊断，不会注册监听器，也不会改变任务输入、输出、执行顺序或最终归档内容。

诊断本身也必须可被扣除成本，因此：

- `mergeClasses` 被拆为 `mergeClasses.cleanup`、`mergeClasses.merge.unpackClassesJar`、`mergeClasses.merge.copyToJavac`、`mergeClasses.merge.writeIndex` 与总计 `mergeClasses.merge`；产物体积与类数在同一次索引遍历中顺带统计，不会为诊断额外遍历产物目录。
- 归档默认只读 zip 中央目录（代价与归档大小无关）。需要 `classes.jar` 的类数时再追加 `-PfataarDiagnosticsDeep=true`：该模式会解压每个 `classes.jar` 统计类数，代价与归档内容成正比。
- 每条归档记录带 `scanMillis`，用于把扫描自身耗时从 `explode` 任务时长里扣除。

### 多级依赖

#### 本地依赖

本地工程支持分层嵌套 `embed`。当 Android 子工程在当前选中变体存在适用的 `embed`、`<buildType>Embed`、`<flavor>Embed` 或 `<variant>Embed` 声明时，该子工程必须应用 `com.kezong.fat-aar`。

```groovy
// :lib-main/build.gradle
dependencies {
    embed project(path: ':lib-aar', configuration: 'default')
}

// :lib-aar/build.gradle
apply plugin: 'com.kezong.fat-aar'

dependencies {
    embed project(path: ':lib-aar2', configuration: 'default')
}
```

对于 `lib-main -> lib-aar -> lib-aar2`，`lib-aar` 会先生成自己的最终 fat AAR，`lib-main` 再消费该最终 AAR。因此最终 `lib-main` AAR 同时包含三层内容，并可递归扩展到更深层级。若子工程存在当前变体适用的 `embed*` 声明却没有应用插件，构建会在配置阶段失败；若该子工程应保持普通 Android Library，请改用 `implementation` 或 `api`。

最终 fat AAR 位于 `build/outputs/fat-aar/<variant>/`，与 AGP 生成的原始 source AAR 分离。

#### 远程依赖

如果你想将所有远程依赖在pom中声明的依赖项同时打入在最终产物里的话，你需要在`build.gradle`中将transitive值改为true，例如：
```groovy
fataar {
    /**
     * transitive 为 true 时，会嵌入本地 JAR 工程与远程库在 POM 中声明的传递依赖。
     * 它不控制本地 Android Library 的嵌套 embed 行为。
     * transitive 为 false 时，只嵌入第一层依赖
     * Default value is false
     * @since 1.3.0
     */
    transitive = true
}
```

如果你将transitive的值改成了true，并且想忽略pom文件中的某一个依赖项，你可以添加`exclude`关键字，例如：
```groovy
embed('com.facebook.fresco:fresco:1.11.0') {
    // exclude any group or module
    exclude(group:'com.facebook.soloader', module:'soloader')
    // exclude all dependencies
    transitive = false
}
```

**更多使用方式可参考 [example](./example).**

## 关于 AAR 文件
AAR是Android提供的一种官方文件形式；
该文件本身是一个Zip文件，并且包含Android里所有的元素；
可以参考 [aar文件详解][2].

**支持功能列表:**

- [x] 支持flavor配置
- [x] AndroidManifest合并
- [x] Classes合并
- [x] Jar合并
- [x] Res合并
- [x] Assets合并
- [x] Jni合并
- [x] R.txt合并
- [x] R.class合并
- [x] DataBinding合并
- [x] Proguard合并
- [x] Kotlin module合并

## Gradle版本支持

| Version | Gradle Plugin | Gradle |
| :--------: | :--------:|:-------:|
| 1.0.1 | 3.1.0 - 3.2.1 | 4.4 - 6.0 |
| 1.1.6 | 3.1.0 - 3.4.1 | 4.4 - 6.0 |
| 1.1.10| 3.0.0 - 3.4.1 | 4.1 - 6.0 |
| 1.2.6 | 3.0.0 - 3.5.0 | 4.1 - 6.0 |
| 1.2.8 | 3.0.0 - 3.5.9 | 4.1 - 6.8 |
| 1.2.11 - 1.2.14 | 3.0.0 - 3.6.9 | 4.1 - 6.8 |
| 1.2.15 - 1.2.16 | 3.0.0 - 4.0.2 | 4.1 - 6.8 |
| 1.2.17 | 3.0.0 - 4.0.2 | 4.9 - 6.8 |
| 1.2.18+ | 3.0.0 - 4.1.0 | 4.9 - 6.8 |
| 1.3.+ | 3.0.0 - 4.1.0 | 4.9 - 6.8 |
| 1.3.4 | 3.0.0 - 4.1.0 | 4.9+ |
| 1.3.6 | 3.0.0 - 4.2.0 | 4.9+ |
| 1.3.8 | 3.0.0+ | 4.9+ |

[Gradle Plugin和所需求的Gradle版本官方文档](https://developer.android.google.cn/studio/releases/gradle-plugin.html)

## 更新日志
- [1.3.8](<https://github.com/kezong/fat-aar-android/releases/tag/v1.3.8>)
  - Fix the issue that plugin cannot be used in jdk 1.8 [#371](https://github.com/kezong/fat-aar-android/issues/371)
- [1.3.7](<https://github.com/kezong/fat-aar-android/releases/tag/v1.3.7>)
  - Fix productFlavor detection in embed submodules [#348](https://github.com/kezong/fat-aar-android/issues/348)
  - Support missingDimensionStrategy without productFlavors in current project. [#343](https://github.com/kezong/fat-aar-android/issues/343)
- [1.3.5](<https://github.com/kezong/fat-aar-android/releases/tag/v1.3.5>)
  - 修复在仅有jar工程时jar无法合并的问题. [#255](https://github.com/kezong/fat-aar-android/issues/255) [#288](https://github.com/kezong/fat-aar-android/issues/288)
  - 修复在使用Gradle 6.0-6.8时的编译错误. [#277](https://github.com/kezong/fat-aar-android/issues/277)
- [1.3.4](<https://github.com/kezong/fat-aar-android/releases/tag/v1.3.4>)
  - 支持Gradle 6.8 [#274](https://github.com/kezong/fat-aar-android/issues/274)
- [1.3.3](<https://github.com/kezong/fat-aar-android/releases/tag/v1.3.3>)
  - 修复异常"Can not find task bundleDebugAar". [#84](https://github.com/kezong/fat-aar-android/issues/84)
  - 修复当工程解析失败时产生的异常.
  - 当AndroidManifest合并时抛出异常.
- [1.3.1](<https://github.com/kezong/fat-aar-android/releases/tag/v1.3.1>)
  - R.class合并采用Transform，解决大部分R class找不到的问题.
  - 支持consumerProguardFiles合并
  - 支持kotlin_module合并，支持top-level机制
  - 支持flavor中missingDimensionStrategy
  - 修复依赖的flavor产物更名后无法找到的问题
  - 修复AGP 3.0 - 3.1 Jar包无法合并的问题
  - 修复某些情况下AGP版本获取不到的问题
- [1.2.20](<https://github.com/kezong/fat-aar-android/releases/tag/v1.2.20>)
  - 修复获取产物名时的空指针异常. [#214](https://github.com/kezong/fat-aar-android/issues/214)
  - r-classes.jar重命名，加上包名作为前缀.
- [1.2.19](<https://github.com/kezong/fat-aar-android/releases/tag/v1.2.19>)
  - 支持embed没有class.jar的aar [#157](https://github.com/kezong/fat-aar-android/issues/158)
  - 支持embed没有AndroidManifest.xml的aar [#206](https://github.com/kezong/fat-aar-android/issues/206)
  - 修复上传至maven时，R.class未包含进aar的BUG [#200](https://github.com/kezong/fat-aar-android/issues/200)
- [1.2.18](<https://github.com/kezong/fat-aar-android/releases/tag/v1.2.18>)
  - 适配gradle plugin 4.1.0 [#201](https://github.com/kezong/fat-aar-android/issues/201)
- [1.2.17](<https://github.com/kezong/fat-aar-android/releases/tag/v1.2.17>)
  - 支持databinding合并 [#25](https://github.com/kezong/fat-aar-android/issues/25) [#67](https://github.com/kezong/fat-aar-android/issues/67) [#142](https://github.com/kezong/fat-aar-android/issues/142)
  - Use Gradle's configuration avoidance APIs [#195](https://github.com/kezong/fat-aar-android/issues/195)
  - Support incremental build [#199](https://github.com/kezong/fat-aar-android/issues/199) [#185](https://github.com/kezong/fat-aar-android/issues/185)
  - Fix wrong directory for aar's jar libs [#154](https://github.com/kezong/fat-aar-android/issues/154)
- [1.2.16](<https://github.com/kezong/fat-aar-android/releases/tag/v1.2.16>)
  - 修复gradle plugin版本不在根目录下就找不到的问题 [#172](https://github.com/kezong/fat-aar-android/issues/172)
  - 修复在gradle plugin 4.0构建的产物中有可能styleable资源找不到的问题 [#163](https://github.com/kezong/fat-aar-android/issues/163)
- [1.2.15](<https://github.com/kezong/fat-aar-android/releases/tag/v1.2.15>)
  - 支持gradle plugin 4.0.0 [#147](https://github.com/kezong/fat-aar-android/issues/147)
  - 修复在Android Studio 4.0.0上embed的库无法直接索引源码的问题 [#148](https://github.com/kezong/fat-aar-android/issues/148)
  - 修复lint编译错误 [#152](https://github.com/kezong/fat-aar-android/issues/152)
- [1.2.12](<https://github.com/kezong/fat-aar-android/releases/tag/v1.2.12>)
  - 添加对buildType以及flavor的支持，例如debugEmbed以及flavorEmbed. [#135](https://github.com/kezong/fat-aar-android/issues/135) [#137](https://github.com/kezong/fat-aar-android/issues/137)
  - 修复一些编译时的warning.
- [1.2.11](<https://github.com/kezong/fat-aar-android/releases/tag/v1.2.11>)
  - 修复在gradle plugin 3.6.0下编译variants会error的情况 [#126](https://github.com/kezong/fat-aar-android/issues/126)
  - 修复在gradle plugin 3.6.0下编译出来的aar，在编译apk时会出现资源符号对象找不到的问题
- [1.2.9](<https://github.com/kezong/fat-aar-android/releases/tag/v1.2.8>)
  - 适配gradle plugin 3.6.1 [#120](https://github.com/kezong/fat-aar-android/issues/120)
- [1.2.8](<https://github.com/kezong/fat-aar-android/releases/tag/v1.2.8>)
  - 适配gradle6.0+版本 [#97](https://github.com/kezong/fat-aar-android/issues/97)
- [1.2.7](<https://github.com/kezong/fat-aar-android/releases/tag/v1.2.7>)
  - 修复在3.5.0中androidmafest合并报错的问题 [#62](https://github.com/kezong/fat-aar-android/issues/62) [#65](https://github.com/kezong/fat-aar-android/issues/65)
- [1.2.6](<https://github.com/kezong/fat-aar-android/releases/tag/v1.2.6>)
  - 适配gradle plugin 3.5.0 [#53](https://github.com/kezong/fat-aar-android/issues/53)[#58](https://github.com/kezong/fat-aar-android/issues/58)
- [1.2.5](<https://github.com/kezong/fat-aar-android/releases/tag/v1.2.5>)
  - 修复任务名称重复导致编译错误的问题 [#48](https://github.com/kezong/fat-aar-android/issues/48)
  - 如果开启minifyEnabled，所有的jar包将合入classes.jar文件
- [1.2.4](<https://github.com/kezong/fat-aar-android/releases/tag/v1.2.4>)
  - 修复在windows平台上，jni和asset无法打入aar的bug [#11](https://github.com/kezong/fat-aar-android/issues/37) [#37](https://github.com/kezong/fat-aar-android/issues/35)
- [1.2.3](<https://github.com/kezong/fat-aar-android/releases/tag/v1.2.3>)
  - 修复未直接依赖的R类找不到的问题 [#11](https://github.com/kezong/fat-aar-android/issues/11) [#35](https://github.com/kezong/fat-aar-android/issues/35)
  - 不再需要为需要`embed`的依赖项主动添加`compileOnly`
  - `embed`的transitive默认值设置成false
- [1.1.11](<https://github.com/kezong/fat-aar-android/releases/tag/v1.1.11>)
  - 修复gradle plugin version有可能判断错误的问题 [#28](https://github.com/kezong/fat-aar-android/issues/28)
  - 修复LibraryManifestMerger.java中出现的build warning [#29](https://github.com/kezong/fat-aar-android/issues/29)
  - 优化resource、assets、jni等的合并规则，不再合并至"main"，而是合并至"variant" [#27](https://github.com/kezong/fat-aar-android/issues/27)
  - 修复重复build造成release包下jni丢失的问题 [#27](https://github.com/kezong/fat-aar-android/issues/27)
- [1.1.10](<https://github.com/kezong/fat-aar-android/releases/tag/v1.1.10>)
  - 修复使用gradle plugin 3.0.1时的jar合并错误 [#24](https://github.com/kezong/fat-aar-android/issues/24)
  - 修复无法正常rebuild（同时使用clean assemble）的问题 [#24](https://github.com/kezong/fat-aar-android/issues/24)
- [1.1.8](<https://github.com/kezong/fat-aar-android/releases/tag/v1.1.8>)
  - 弃用旧接口，处理编译时输出的warning [#10](https://github.com/kezong/fat-aar-android/issues/10)
  - 将AndroidManifest的合并规则由Application改为Library [#21](https://github.com/kezong/fat-aar-android/issues/21) [#23](https://github.com/kezong/fat-aar-android/issues/23)
- [1.1.7](<https://github.com/kezong/fat-aar-android/releases/tag/v1.1.7>)
  - 修复直接publish至maven时，aar的R文件未合并的问题 [#7](https://github.com/kezong/fat-aar-android/issues/7)
- [1.1.6](<https://github.com/kezong/fat-aar-android/releases/tag/v1.1.6>)
  - 适配gradle plugin 3.3.0+ [#4](https://github.com/kezong/fat-aar-android/issues/4) [#9](https://github.com/kezong/fat-aar-android/issues/9)
  - 适配gadle 4.10.0+ [#8](https://github.com/kezong/fat-aar-android/issues/8)
  - 支持子module的flavor编译
  - 修复子module的class文件编译不实时更新的问题
- [1.0.3](<https://github.com/kezong/fat-aar-android/releases/tag/v1.0.3>)
  - 修复assets未合并的问题
- [1.0.1](<https://github.com/kezong/fat-aar-android/releases/tag/v1.0.1>)
  - 支持gradle plugin 3.1.0 - 3.2.1
  - 支持资源合并，R文件合并
  
## 常见问题

- **Application无法直接依赖embed工程：** application无法直接依赖你的embed工程，必须依赖你embed工程所编译生成的aar文件
  - 为了调试方便，你可以在选择在打包aar时，在主library工程中使用`embed`，需要直接运行app时，采用`implementation`或者`api`

- **资源冲突：** 如果library和module中含有同名的资源(比如 `string/app_name`)，编译将会报`duplication resources`的相关错误，有两种方法可以解决这个问题：
  - 考虑将library以及module中的资源都加一个前缀来避免资源冲突； 
  - 在`gradle.properties`中添加`android.disableResourceValidation=true`可以忽略资源冲突的编译错误，程序会采用第一个找到的同名资源作为实际资源.

- **关于混淆**
  - 如果`minifyEnabled`设置为true，编译时会根据proguard规则过滤工程中没有引用到的类，导致App集成时找不到对象，因为大多数AAR都是提供接口的SDK，建议大家仔细梳理proguard文件。

## 致谢
* [android-fat-aar][1]
* [fat-aar-plugin][4]

[1]: https://github.com/adwiv/android-fat-aar
[2]: https://developer.android.com/studio/projects/android-library.html#aar-contents
[3]: https://developer.android.com/studio/releases/gradle-plugin.html
[4]: https://github.com/Vigi0303/fat-aar-plugin

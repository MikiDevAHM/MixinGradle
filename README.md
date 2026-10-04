![MixinGradle Logo](docs/logo.png?raw=true)

**MixinGradle** is a [Gradle](http://gradle.org/) plugin which simplifies the build-time complexity of working with the **[SpongePowered Mixin](/SpongePowered/Mixin)** framework for Java and Kotlin. It currently only supports usage with **[ForgeGradle](MinecraftForge/ForgeGradle)**.

### Features

**MixinGradle** automates the following tasks:

* Locating (via **ForgeGradle**) and supplying input SRG files to the [Mixin](/SpongePowered/Mixin) [Annotation Processor](https://github.com/SpongePowered/Mixin/wiki/Using-the-Mixin-Annotation-Processor)
* Providing processing options to the [Annotation Processor](https://github.com/SpongePowered/Mixin/wiki/Using-the-Mixin-Annotation-Processor)
* Supplying the same processing options to **kapt**, so that the processor also runs over Kotlin sources
* Contributing the generated [reference map (refmap)](https://github.com/SpongePowered/Mixin/wiki/Introduction-to-Mixins---Obfuscation-and-Mixins#511-the-mixin-reference-map-refmap) to the corresponding sourceSet compile task outputs
* Merging the refmaps generated for a sourceSet which contains both Java and Kotlin sources
* Contributing the generated SRG files to appropriate **ForgeGradle** `reobf` tasks

### Using MixinGradle

To use **MixinGradle** you *must* be using **[ForgeGradle](MinecraftForge/ForgeGradle)**. To configure the plugin for your build:

1. Add a source repository to your `buildScript -> dependencies` block:

 ```groovy
buildscript {
        repositories {
            <add source repository here>
        }
        dependencies {
            ...
            classpath 'org.spongepowered:MixinGradle:0.6-SNAPSHOT'
        }
}
 ```
2. Apply the plugin:
 
 ```groovy
 apply plugin: 'org.spongepowered.mixin'
 ```
 
3. Create your `mixin` block, specify which sourceSets to process and provide refmap resource names for each one, the generated refmap will be added to the compiler task outputs automatically.
 
 ```groovy
mixin {
        add sourceSets.main, "main.refmap.json"
        add sourceSets.another, "another.refmap.json"
}
 ```
  
4. Alternatively, you can simply specify the `ext.refMap` property directly on your sourceSet:
 
 ```groovy
sourceSets {
        main {
            ext.refMap = "main.refmap.json"
        }
        another {
            ext.refMap = "another.refmap.json"
        }
}
 ```
 
5. You can define other mixin AP options in the `mixin` block, for example `disableTargetValidator` and `disableTargetExport` can be configured either by setting them as boolean properties:
 
 ```groovy
 mixin {
        disableTargetExport = true
        disableTargetValidator = true
 }
 ```
 
 or simply issuing them as directives:
 
 ```groovy
 mixin {
        disableTargetExport
        disableTargetValidator
 }
 ```
 
 You can also set the default obfuscation environment for generated refmaps, this is the obfuscation environment which will be contributed to the refmap's `mappings` node:
 
```groovy
 mixin {
        // Specify "notch" or "searge" here
        defaultObfuscationEnv notch
 }
 ```
 
 ### Kotlin support

Mixin sources written in Kotlin are processed by **kapt**, since the Kotlin compiler does not run annotation processors itself. **MixinGradle** detects the Kotlin plugins and configures the **kapt** task for each sourceSet in the same way that it configures the `JavaCompile` task, so the same `mixin` block works for both languages and the plugins may be applied in any order.

To use Kotlin sources, apply the **kapt** plugin and declare the **Mixin** annotation processor (and its own dependencies) on the `kapt` configuration:

 ```groovy
 apply plugin: 'org.jetbrains.kotlin.jvm'
 apply plugin: 'kotlin-kapt'
 apply plugin: 'org.spongepowered.mixin'
 
 dependencies {
        kapt 'org.spongepowered:mixin:0.7.11-SNAPSHOT'
        kapt 'org.ow2.asm:asm-debug-all:5.0.3'
        kapt 'com.google.guava:guava:17.0'
        compileOnly 'org.spongepowered:mixin:0.7.11-SNAPSHOT'
 }
 ```

The **Mixin** POM does not declare ASM or Guava, so they must be supplied explicitly. The versions above are the ones this plugin builds against.

If a sourceSet contains **both** Java and Kotlin sources, the processor is declared for both configurations, because javac runs the processor over the Java sources and **kapt** runs it over the Kotlin ones:

 ```groovy
 dependencies {
        annotationProcessor 'org.spongepowered:mixin:0.7.11-SNAPSHOT'
        kapt 'org.spongepowered:mixin:0.7.11-SNAPSHOT'
        // ...plus the ASM and Guava dependencies for each of them
 }
 ```

**MixinGradle** merges the refmaps produced by the two processors into the single refmap named for the sourceSet, and contributes the SRG files from both to the `reobf` task. When a sourceSet contains Kotlin sources but **kapt** is not applied, a warning is issued because the processor would otherwise silently skip them.

 ### Building MixinGradle
**MixinGradle** can of course be built using [Gradle](http://gradle.org/). To perform a build simply execute:

    gradle

To add the compiled jar to your local maven repository, run:

    gradle build install




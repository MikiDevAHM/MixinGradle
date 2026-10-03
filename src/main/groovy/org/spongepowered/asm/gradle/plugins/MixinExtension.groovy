/*
 * This file is part of MixinGradle, licensed under the MIT License (MIT).
 *
 * Copyright (c) SpongePowered <https://www.spongepowered.org>
 * Copyright (c) contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.spongepowered.asm.gradle.plugins

import com.google.common.io.Files
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.PackageScope
import org.gradle.api.InvalidUserDataException
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.tasks.Jar
import org.spongepowered.asm.gradle.plugins.meta.Imports

import java.util.Map.Entry

import static org.spongepowered.asm.gradle.plugins.ReobfMappingType.NOTCH
import static org.spongepowered.asm.gradle.plugins.ReobfMappingType.SEARGE

/**
 * Extension object for mixin configuration, actually manages the configuration
 * of the mixin annotation processor and extensions to sourcesets 
 */
class MixinExtension {

    /**
     * Id of the Kotlin Gradle plugin. Only ever used as a string so that this
     * plugin does not need a compile time dependency on it.
     */
    private static final String KOTLIN_PLUGIN_ID = 'org.jetbrains.kotlin.jvm'
    
    /**
     * Id of the plugin which adds kapt, kapt is what actually runs the
     * annotation processor for Kotlin sources.
     */
    private static final String KAPT_PLUGIN_ID = 'kotlin-kapt'
    
    /**
     * Id which the Kotlin Gradle plugin uses for the options of the kapt
     * subplugin. Options contributed under this id are handed to the annotation
     * processor.
     */
    private static final String KAPT_SUBPLUGIN_ID = 'org.jetbrains.kotlin.kapt3'
    
    /**
     * Name of the class which holds the compiler subplugin options of a Kotlin
     * compile task. It is created reflectively so that the Kotlin Gradle plugin
     * is not needed on this plugin's classpath.
     */
    private static final String COMPILER_PLUGIN_CONFIG_CLASS = 'org.jetbrains.kotlin.gradle.plugin.CompilerPluginConfig'
    
    /**
     * Name of the class which represents a single compiler subplugin option. It
     * is created reflectively so that the Kotlin Gradle plugin is not needed on
     * this plugin's classpath.
     */
    private static final String SUBPLUGIN_OPTION_CLASS = 'org.jetbrains.kotlin.gradle.plugin.SubpluginOption'
    
    class ReobfTask {
        final Project project
        final Object taskWrapper
        
        ReobfTask(Project project, Object taskWrapper) {
            this.project = project
            this.taskWrapper = taskWrapper
        }
        
        Jar getJar() {
            this.project.tasks[this.taskWrapper.name]
        }
        
        String getName() {
            this.taskWrapper.name
        }
    }
    
    /**
     * Cached reference to containing project 
     */
    private final Project project
    
    /**
     * Until we add some sourcesets, we will assume that the user hasn't
     * configured the plugin in any way (hasn't added a refMap setting on the
     * sourceSet or added any sourceSets in the mixin block). If this is the
     * case we will attach to all sourceSets in <tt>project.afterEvaluate</tt>
     * as our default behaviour. This flag is set to false as soon as 
     * {@link #add} is called from any source. 
     */
    private boolean applyDefault = true
    
    /**
     * Avoid adding duplicates by adding sourceSets to this collection
     */
    private Set<SourceSet> sourceSets = []
    
    /**
     * Mapping of refMap names to sourceSet names, used to avoid refMap
     * conflicts (or at least notify the user that a conflict has occurred)
     */
    private Map<String, String> refMaps = [:]
    
    /**
     * AP tokens, if this map is empty then the tokens argument will be omitted,
     * otherwise it will be compiled to a semicolon-separated list and passed
     * into the 
     */
    private Map<String, String> tokens = [:]
    
    /**
     * Reobf tasks we will target
     */
    Set<ReobfTask> reobfTasks = []
    
    /**
     * If a refMap overlap is detected a warning will be output, however there
     * are situations where a refMap overlap may be desired (for example if
     * different sourceSets are going into different jars) and thus the warning
     * can be ignored. Setting this value to true will suppress the warning.
     */
    boolean disableRefMapWarning
    
    /**
     * Disables the target validator in the mixin annotation processor.
     */
    boolean disableTargetValidator
    
    /**
     * Disables the target export in the mixin annotation processor, only
     * useful if multiple compile tasks need to be separated from the point of
     * view of the mixin AP. 
     */
    boolean disableTargetExport
    
    /**
     * Disables the overwrite checking functionality which raises warnings when
     * overwrite methods are not appropriately decorated 
     */
    boolean disableOverwriteChecker
    
    /**
     * Sets the overwrite checker error level, the default is to raise WARNING
     * however this can be set to ERROR in order to cause missing decorations
     * to be treated as errors 
     */
    Object overwriteErrorLevel
    
    /**
     * The default obfuscation environment to use when generating refMaps. This
     * is the obfuscation set which will end up in the <tt>mappings</tt> node
     * in the generated refMap. 
     */
    Object defaultObfuscationEnv
    
    /**
     * By default we will attempt to find the SRG file to feed to the AP by
     * querying the <tt>genSrgs</tt> task, however the user can override the
     * file by setting this argument 
     */
    Object reobfSrgFile
    
    /**
     * By default we will attempt to find the SRG file to feed to the AP by
     * querying the <tt>genSrgs</tt> task, however the user can override the
     * file by setting this argument 
     */
    Object reobfNotchSrgFile
    
    /**
     * Additional Searge SRG files to supply to the annotation processor. LTP.
     */
    private List<Object> extraSrgFiles = []
    
    /**
     * Additional Notch SRG files to supply to the annotation processor. LTP.
     */
    private List<Object> extraNotchFiles = []
    
    /**
     * Configurations to scan for dependencies when running AP
     */
    private Set<Object> importConfigs = []
    
    /**
     * Additional libraries to scan when running AP
     */
    private Set<Object> importLibs = []
    
    /**
     * Set when the Kotlin Gradle plugin has been applied to the project,
     * regardless of the order in which it was applied relative to this plugin.
     */
    boolean kotlinApplied
    
    /**
     * Set when the kapt plugin has been applied to the project. Without kapt
     * there is nothing to run the annotation processor for Kotlin sources.
     */
    boolean kaptApplied
    
    /**
     * ctor
     * 
     * @param project reference to the containing project
     */
    MixinExtension(Project project) {
        this.project = project
        this.init()
    }
    
    /**
     * Set up the project by extending relevant objects and adding the
     * <tt>afterEvaluate</tt> handler
     */
    private void init() {
        Project project = this.project
        def sourceSets = this.sourceSets
        
        // Detect the Kotlin plugins through withPlugin rather than by inspecting
        // the applied plugin set, so that the result does not depend on whether
        // the Kotlin plugins were applied before or after this one. Both are
        // referenced purely by id, so the Kotlin Gradle plugin never has to be on
        // the buildscript classpath for this plugin to work.
        project.pluginManager.withPlugin(KOTLIN_PLUGIN_ID) {
            this.kotlinApplied = true
        }
        
        project.pluginManager.withPlugin(KAPT_PLUGIN_ID) {
            this.kaptApplied = true
            this.enableJavacAnnotationProcessing()
        }
        
        this.project.afterEvaluate {
            // Gather reobf jars for processing
            project.reobf.each { reobfTaskWrapper ->
                this.reobfTasks += new ReobfTask(project, reobfTaskWrapper)
            }

            // Search for sourceSets with a refmap property and configure them
            project.sourceSets.each { set ->
                if (set.ext.has("refMap")) {
                    this.configure(set)
                }
            }

            // Search for upstream projects and add our jars to their target set.
            // The 'compile' configuration only exists on Gradle < 7, so look it
            // up defensively instead of assuming it is there.
            def compileConfiguration = project.configurations.findByName('compile')
            if (compileConfiguration != null) {
                compileConfiguration.allDependencies.withType(ProjectDependency) { upstream ->
                    def mixinExt = upstream.dependencyProject.extensions.findByName("mixin")
                    if (mixinExt) {
                        project.reobf.each { reobfTaskWrapper ->
                            mixinExt.reobfTasks += new ReobfTask(project, reobfTaskWrapper)
                        }
                    }
                }
            }

            
            this.applyDefault()
        }

        SourceSet.metaClass.getRefMap = {
            delegate.ext.refMap
        }
        
        SourceSet.metaClass.setRefMap = { value ->
            delegate.ext.refMap = value
        }
        
        AbstractArchiveTask.metaClass.getRefMaps = {
            if (!delegate.ext.has('refMaps')) {
                delegate.ext.refMaps = project.files()
            }
            delegate.ext.refMaps
        }
        
        AbstractArchiveTask.metaClass.setRefMaps = { value ->
            delegate.ext.refMaps = value
        }
    }
    
    /**
     * Directive version of {@link #disableRefMapWarning}
     */
    void disableRefMapWarning() {
        this.disableRefMapWarning = true
    }
    
    /**
     * Directive version of {@link #disableTargetValidator}
     */
    void disableTargetValidator() {
        this.disableTargetValidator = true
    }
    
    /**
     * Directive version of {@link #disableTargetExport}
     */
    void disableTargetExport() {
        this.disableTargetExport = true
    }
    
    /**
     * Directive version of {@link #disableOverwriteChecker}
     */
    void disableOverwriteChecker() {
        this.disableOverwriteChecker = true
    }
    
    /**
     * Directive version of {@link #disableOverwriteChecker}
     */
    void overwriteErrorLevel(Object errorLevel) {
        this.overwriteErrorLevel = errorLevel
    }
    
    /**
     * Convenience getter so that "notch" can be used unqoted 
     */
    String getNotch() {
        NOTCH
    }
    
    /**
     * Convenience getter so that "searge" can be used unqoted 
     */
    String getSearge() {
        SEARGE
    }
    
    /**
     * Convenience getter so that "srg" can be used unqoted 
     */
    String getSrg() {
        SEARGE
    }
    
    /**
     * Getter for reobfSrgFile, fetch from the <tt>genSrgs</tt> task if not configured
     */
    Object getReobfSrgFile() {
        this.reobfSrgFile != null ? project.file(this.reobfSrgFile) : project.tasks.genSrgs.mcpToSrg
    }
    
    /**
     * Getter for reobfSrgFile, fetch from the <tt>genSrgs</tt> task if not configured
     */
    Object getReobfNotchSrgFile() {
        this.reobfNotchSrgFile != null ? project.file(this.reobfNotchSrgFile) : project.tasks.genSrgs.mcpToNotch
    }
    
    /**
     * Adds an additional SRG file for the AP to consume. The environment for
     * the SRG file must be specified
     * 
     * @param type Obfuscation type to add mapping to
     * @param file Object which resolves to a file
     */
    void extraSrgFile(String type, Object file) {
        type = type.toUpperCase()
        if (SEARGE.matches(type)) {
            this.extraSrgFiles += file
        } else if (NOTCH.matches(type)) {
            this.extraNotchFiles += file
        } else {
            throw new InvalidUserDataException("Invalid obfuscation type '${type}' specified for extraSrgFile")
        }
    }
    
    /**
     * Adds a boolean token with the value true
     * 
     * @param name boolean token name
     */
    void token(Object name) {
        token(name, "true")
    }
    
    /**
     * Adds a token with the specified value
     * 
     * @param name Token name
     * @param value Token value
     */
    void token(Object name, Object value) {
        this.tokens.put(name.toString().trim(), value.toString().trim())
    }
    
    void importConfig(Object config) {
        if (config == null) {
            throw new InvalidUserDataException("Cannot import from null config")
        }
        this.importConfigs += config
    }
    
    void importLibrary(Object lib) {
        if (lib == null) {
            throw new InvalidUserDataException("Cannot import null library")
        }
        this.importLibs += lib
    }
    
    /**
     * Add multiple tokens in one go by providing a map
     * 
     * @param map map of tokens to add
     * @return fluent interface
     */
    MixinExtension tokens(Map<String, ?> map) {
        for (Entry<String, ?> entry : map) {
            this.tokens.put(entry.key.trim(), entry.value.toString().trim())
        }
    }
    
    /**
     * Return current tokens as an unmodifyable map
     */
    Map<String, String> getTokens() {
        Collections.unmodifiableMap(this.tokens)
    }
    
    /**
     * Internal method which compiles the token map to an AP argument
     */
    @PackageScope String getTokenArgument() {
        def arg = '-Atokens='
        def first = true
        for (Entry<String, String> token : this.tokens) {
            if (token.value.indexOf(';') > -1) {
                throw new InvalidUserDataException(sprintf('Invalid token value \'%s\' for token \'%s\'', token.value, token.key))
            }
            if (!first) arg <<= ";"
            first = false
            arg <<= token.key << "=" << token.value
        }
        arg
    }
    
    @PackageScope String getSrgsArgument(String argName, List<Object> list) {
        if (list.size() < 1) {
            return ""
        }
        def arg = "-A${argName}="
        def first = true
        for (String entry : list) {
            if (!first) arg <<= ";"
            first = false
            arg <<= project.file(entry).toString()
        }
        arg
    }
    
    /**
     * Handle the default behaviour of adding all sourceSets if no sourceSets
     * were explicitly added
     */
    @PackageScope void applyDefault() {
        if (this.applyDefault) {
            this.applyDefault = false
            project.logger.info "No sourceSets added for mixin processing, applying defaults"
            this.disableRefMapWarning = true
            project.sourceSets.each { set ->
                if (!set.ext.has("refMap")) {
                    set.ext.refMap = "mixin.refmap.json"
                }
                this.configure(set)
            }
        }
    }
    
    /**
     * Add a sourceSet for mixin processing by name, the sourceSet must exist
     * and define the <tt>refMap</tt> property.
     * 
     * @param set SourceSet name
     */
    void add(String set) {
        this.add(project.sourceSets[set])
    }
    
    /**
     * Add a sourceSet for mixin processing, the sourceSet must define the
     * <tt>refMap</tt> property.
     * 
     * @param set SourceSet to add
     */
    void add(SourceSet set) {
        try {
            set.getRefMap()
        } catch (e) {
            throw new InvalidUserDataException(sprintf('No \'refMap\' defined on %s', set))
        }
        
        project.afterEvaluate {
            this.configure(set)
        }
    }
    
    /**
     * Add a sourceSet by name for mixin processing and specify the refMap name,
     * the SourceSet must exist
     * 
     * @param set SourceSet name
     * @param refMapName RefMap name
     */
    void add(String set, Object refMapName) {
        SourceSet sourceSet = project.sourceSets.findByName(set)
        if (sourceSet == null) {
            throw new InvalidUserDataException(sprintf('No \'refMap\' defined on %s', set))
        }
        sourceSet.ext.refMap = refMapName
        project.afterEvaluate {
            this.configure(sourceSet)
        }
    }
    
    /**
     * Add a sourceSet for mixin processing and specify the refMap name, the
     * SourceSet must exist
     * 
     * @param set SourceSet to add
     * @param refMapName RefMap name
     */
    void add(SourceSet set, Object refMapName) {
        set.ext.refMap = refMapName.toString()
        project.afterEvaluate {
            this.configure(set)
        }
    }
    
    /**
     * Configure a sourceSet for mixin processing and specify the refMap name,
     * the SourceSet must exist
     * 
     * @param set SourceSet to add
     */
    void configure(SourceSet set) {
        // Captured in a local because it is referenced from the closures below
        Project project = this.project
        
        // Check whether this sourceSet was already added
        if (this.sourceSets.contains(set)) {
            project.logger.info "Not adding {} to mixin processor, sourceSet already added", set
            return
        }
        this.sourceSets.add(set)
        
        project.logger.info "Adding {} to mixin processor", set
        
        // Get the sourceSet's compile task
        def compileTask = project.tasks[set.compileJavaTaskName]
        if (!(compileTask instanceof JavaCompile)) {
            throw new InvalidUserDataException(sprintf('Cannot add non-java %s to mixin processor', set))
        }
        
        // Don't perform default behaviour, a sourceSet has been added manually
        this.applyDefault = false
        
        // For closures below
        def refMaps = this.refMaps
        
        // Every task which runs the Mixin annotation processor for this
        // sourceSet. For a pure Java sourceSet this is just the JavaCompile
        // task, but Kotlin sources are compiled by kapt (which runs javac, and
        // therefore the annotation processor, itself) so that task needs to be
        // configured in exactly the same way as the Java one.
        def processorTasks = this.getProcessorTasks(set)
        
        this.warnOnMissingKapt(set, processorTasks)
        
        // ForgeGradle only makes the Java compilation depend on the task which
        // deobfuscates the Minecraft jar, so kapt has to be given the same
        // dependency explicitly. Without this, kapt runs before the deobfuscated
        // jar (and the SRG files derived from it) are available.
        processorTasks.each { procTask ->
            if (this.isKaptTask(procTask)) {
                this.dependOnDeobfuscatedJar(procTask)
            }
        }
        
        // Refmap file, per processing task. Each task gets its own file (in its
        // own temporary directory) so that multiple processors cannot clobber
        // each other's output, they get merged into a single refmap once all of
        // the processing tasks have run.
        def refMapFiles = []
        
        // Srg files, per processing task
        def srgFiles = [:]
        
        processorTasks.each { procTask ->
            def procTmpDir = this.getTemporaryDir(procTask)
            def procRefMapFile = project.file("${procTmpDir}/${procTask.name}-refmap.json")
            def procSrgFiles = [
                (SEARGE): project.file("${procTmpDir}/mcp-srg.srg"),
                (NOTCH): project.file("${procTmpDir}/mcp-notch.srg")
            ]
            
            refMapFiles += procRefMapFile
            srgFiles[procTask] = procSrgFiles
            
            // Add our vars as extension properties to the sourceSet and compile
            // tasks, this will allow them to be used in the build script if needed
            procTask.ext.outSrgFile = procSrgFiles[SEARGE]
            procTask.ext.outNotchFile = procSrgFiles[NOTCH]
            procTask.ext.refMapFile = procRefMapFile
            procTask.ext.refMap = set.ext.refMap.toString()
            set.ext.refMapFile = procRefMapFile
            
            // Closure to prepare AP environment before compile task runs
            procTask.doFirst {
                if (!this.disableRefMapWarning && refMaps[procTask.ext.refMap] && refMaps[procTask.ext.refMap] != set.name) {
                    project.logger.warn "Potential refmap conflict. Duplicate refmap name {} specified for sourceSet {}, already defined for sourceSet {}",
                        procTask.ext.refMap, set.name, refMaps[procTask.ext.refMap]
                } else {
                    refMaps[procTask.ext.refMap] = set.name
                }
                
                procRefMapFile.delete()
                procSrgFiles.each {
                    it.value.delete()
                }
                this.applyCompilerArgs(procTask)
            }
        }
        
        // The refmaps are generated with generic names, they get merged into an
        // artefact-specific name ready for inclusion into the target jar. We
        // can't use rename in the jar spec because there may be multiple
        // refmaps with the same source name
        File artefactSpecificRefMap = new File(this.getTemporaryDir(compileTask), compileTask.ext.refMap)
        
        // Closure to allocate generated AP resources once compile task
        // is completed
        this.reobfTasks.each { reobfTask ->
            reobfTask.taskWrapper.task.doFirst {
                try {
                    def mapped = false
                    [reobfTask.taskWrapper.mappingType, this.defaultObfuscationEnv.toString()].each { arg ->
                        ReobfMappingType.each { type ->
                            def available = this.getSrgFiles(srgFiles, type)
                            if (type.matches(arg) && !mapped && available.size() > 0) {
                                this.addMappings(reobfTask, type, available)
                                mapped = true
                            }
                        }
                    }
    
                    // No mapping set was matched, so add the searge mappings
                    def searge = this.getSrgFiles(srgFiles, SEARGE)
                    if (!mapped && searge.size() > 0) {
                        this.addMappings(reobfTask, SEARGE, searge)
                    }
                } catch (MissingPropertyException ex) {
                    if (ex.property == "mappingType") {
                        throw new InvalidUserDataException("Could not determine mapping type for obf task, ensure ForgeGradle up to date.")
                    } else {
                        throw ex
                    }
                }
            }
        }
        
        // Add the refmap to all reobf'd jars
        this.reobfTasks.each { reobfTask ->
            def jarTask = reobfTask.jar
            
            // Merge the refmaps generated by each of the processing tasks into
            // the artefact-specific refmap. This is done in a doFirst on the jar
            // task so that every processing task has completed (and therefore
            // written its refmap) by the time that the jar is assembled.
            jarTask.doFirst {
                this.mergeRefMaps(refMapFiles, artefactSpecificRefMap)
            }
            
            jarTask.getRefMaps().files.add(artefactSpecificRefMap)
            jarTask.from(artefactSpecificRefMap)
        }
        
    }
    
    /**
     * Attempts to contribute mappings of the specified type to the supplied task.
     * A sourceSet can have more than one task running the annotation processor
     * (for example when it contains both Java and Kotlin sources), in which case
     * every SRG file generated by one of them is contributed.
     * 
     * @param reobfTask a <tt>ReobfTask</tt> instance
     * @param type Mapping type to add
     * @param srgFiles SRG mapping files to add to the task
     */
    @PackageScope void addMappings(ReobfTask reobfTask, ReobfMappingType type, List<File> srgFiles) {
        if (srgFiles.size() == 0) {
            return
        }
        
        for (File srgFile : srgFiles) {
            project.logger.info "Contributing {} ({}) mappings to {} in {}", type, srgFile, reobfTask.name, reobfTask.project
            reobfTask.taskWrapper.extraFiles(srgFile)
        }
    }
    
    /**
     * Callback from the <tt>compileTask.doFirst</tt> closure, configures the
     * annotation processor arguments based on the settings configured in this
     * extension.
     * 
     * @param procTask Task running the annotation processor to modify, this is
     *        either a <tt>JavaCompile</tt> task or a kapt task
     */
    @PackageScope void applyCompilerArgs(Object procTask) {
        // The arguments are built up as javac-style arguments first. They are
        // then handed to whichever task is actually running the annotation
        // processor, since javac and kapt accept them in different ways.
        def arguments = [
            "-AreobfSrgFile=${this.getReobfSrgFile().canonicalPath}",
            "-AreobfNotchSrgFile=${this.getReobfNotchSrgFile().canonicalPath}",
            "-AoutSrgFile=${procTask.outSrgFile.canonicalPath}",
            "-AoutNotchSrgFile=${procTask.outNotchFile.canonicalPath}",
            "-AoutRefMapFile=${procTask.refMapFile.canonicalPath}"
        ]
        
        if (this.disableTargetValidator) {
            arguments += '-AdisableTargetValidator=true'
        }
        
        if (this.disableTargetExport) {
            arguments += '-AdisableTargetExport=true'
        }
        
        if (this.disableOverwriteChecker) {
            arguments += '-AdisableOverwriteChecker=true'
        }
        
        if (this.overwriteErrorLevel != null) {
            arguments += '-AoverwriteErrorLevel=${this.overwriteErrorLevel.toString().trim()}'
        }
        
        if (this.defaultObfuscationEnv != null) {
            arguments += "-AdefaultObfuscationEnv=${this.defaultObfuscationEnv.toLowerCase()}"
        }
        
        if (this.tokens.size() > 0) {
            arguments += this.tokenArgument
        }
        
        if (this.extraSrgFiles.size() > 0) {
            arguments += this.getSrgsArgument("reobfSrgFiles", this.extraSrgFiles)
        }
        
        if (this.extraNotchFiles.size() > 0) {
            arguments += this.getSrgsArgument("reobfNotchSrgFiles", this.extraNotchFiles)
        }

        File importsFile = this.generateImportsFile(procTask)
        if (importsFile != null) {
            arguments += "-AdependencyTargetsFile=${importsFile.canonicalPath}"
        }
        
        if (this.isKaptTask(procTask)) {
            // kapt does not run javac directly, it runs the annotation processor
            // in its own worker and hands the processor options to it as plain
            // 'key=value' pairs instead of as '-Akey=value' compiler arguments.
            // They are contributed as subplugin options for the kapt plugin id,
            // which is the mechanism kapt itself uses for the options from its
            // own 'arguments' block, so that both keys and values survive.
            def config = Class.forName(COMPILER_PLUGIN_CONFIG_CLASS).getDeclaredConstructor().newInstance()
            
            for (String argument : arguments) {
                def separator = argument.indexOf('=')
                def key = argument.substring(2, separator)
                def value = argument.substring(separator + 1)
                
                config.addPluginArgument(KAPT_SUBPLUGIN_ID, this.newSubpluginOption(key, value))
            }
            
            procTask.kaptPluginOptions.add(config)
        } else {
            procTask.options.compilerArgs += arguments
        }
    }
    
    /**
     * Creates a compiler subplugin option, reflectively so that the Kotlin
     * Gradle plugin is not needed on this plugin's classpath.
     * 
     * @param key Option key
     * @param value Option value
     * @return new subplugin option instance
     */
    private Object newSubpluginOption(String key, String value) {
        return Class.forName(SUBPLUGIN_OPTION_CLASS).getDeclaredConstructor(String, String).newInstance(key, value)
    }

    /**
     * Asks kapt to leave the annotation processing of Java compilation enabled.
     *
     * By default kapt disables annotation processing on the Java compile task,
     * because it expects every processor to be declared through the 'kapt'
     * configuration and run over the Kotlin sources. That would leave any Java
     * sources in a mixed sourceSet completely unprocessed, so the processors
     * have to be declared for both 'annotationProcessor' and 'kapt' in that case.
     * Since this plugin configures the Java compile task with the Mixin
     * processor options either way, kapt is asked to keep javac's annotation
     * processing enabled so that the two work together.
     */
    @PackageScope void enableJavacAnnotationProcessing() {
        // Located dynamically because the Kotlin Gradle plugin is not a
        // dependency of this plugin, and skipped entirely when kapt does not
        // expose the setting.
        def kaptExtension = project.extensions.findByName('kapt')
        if (kaptExtension == null || !kaptExtension.hasProperty('keepJavacAnnotationProcessors')) {
            return
        }
        
        kaptExtension.keepJavacAnnotationProcessors = true
    }
    
    /**
     * Gets every task which runs the Mixin annotation processor for the given
     * sourceSet. For a Java-only sourceSet this is just the JavaCompile task,
     * but Kotlin sources are handled by kapt (which runs the annotation
     * processor itself, since the Kotlin compiler does not), so the kapt task is
     * included as well when kapt is available.
     * 
     * @param set SourceSet to inspect
     * @return list of tasks which run the annotation processor
     */
    @PackageScope List<Object> getProcessorTasks(SourceSet set) {
        def processorTasks = []
        processorTasks += project.tasks[set.compileJavaTaskName]
        
        Object kaptTask = this.getKaptTask(set)
        if (kaptTask != null) {
            processorTasks += kaptTask
        }
        
        return processorTasks
    }
    
    /**
     * Locates the kapt task for the given sourceSet, without requiring the
     * Kotlin Gradle plugin to be on the classpath. Returns null when kapt is not
     * applied, the task does not exist, or the task does not look like a kapt
     * task.
     * 
     * @param set SourceSet to inspect
     * @return the kapt task for the sourceSet or null
     */
    @PackageScope Object getKaptTask(SourceSet set) {
        if (!this.kaptApplied) {
            return null
        }
        
        // kapt names its tasks after the Kotlin compilation rather than after
        // the Gradle sourceSet, so the task for the 'main' sourceSet is called
        // 'kaptKotlin' rather than 'kaptMain'. The tasks are therefore matched
        // up using the source set name that each kapt task reports for itself.
        for (Object task : project.tasks) {
            if (!this.isKaptTask(task)) {
                continue
            }
            
            if (this.getKaptSourceSetName(task) == set.name) {
                return task
            }
        }
        
        return null
    }
    
    /**
     * Gets the name of the sourceSet which the given kapt task processes.
     * 
     * @param kaptTask kapt task to inspect
     * @return name of the sourceSet the task processes, or null if unavailable
     */
    @PackageScope String getKaptSourceSetName(Object kaptTask) {
        if (!kaptTask.hasProperty('sourceSetName')) {
            return null
        }
        
        try {
            return kaptTask.sourceSetName.getOrNull()
        } catch (MissingPropertyException ex) {
            return null
        }
    }
    
    /**
     * Determines whether the given task is a kapt task, that is a task which
     * runs the annotation processor for Kotlin sources. This is done by feature
     * detection rather than by checking the task class, so that the Kotlin
     * Gradle plugin does not need to be present on the classpath.
     * 
     * @param task Task to inspect
     * @return true if the task is a kapt task
     */
    @PackageScope boolean isKaptTask(Object task) {
        // javac takes its processor options as compiler arguments, kapt
        // collects them as compiler subplugin options instead, so a task which
        // supports the latter is a kapt task. Only the tasks which actually run
        // the processor have this property, the kapt stub generation task (which
        // only runs the Kotlin compiler) does not.
        return !(task instanceof JavaCompile) && task.hasProperty('kaptPluginOptions')
    }
    
    /**
     * Warns if the given sourceSet contains Kotlin sources but kapt is not
     * available to run the annotation processor over them. Without this the
     * build fails later on with a much less obvious error, since Mixin
     * annotations in Kotlin sources would simply be ignored.
     * 
     * @param set SourceSet to inspect
     * @param processorTasks Tasks which run the annotation processor
     */
    @PackageScope void warnOnMissingKapt(SourceSet set, List<Object> processorTasks) {
        if (!this.kotlinApplied) {
            return
        }
        
        if (processorTasks.any { this.isKaptTask(it) }) {
            return
        }
        
        if (this.getKotlinSourceDirs(set).size() == 0) {
            return
        }
        
        project.logger.warn "SourceSet {} contains Kotlin sources but the kapt plugin is not applied, " +
            "the Mixin annotation processor will not be run over them. Apply the 'kotlin-kapt' plugin " +
            "and add the Mixin annotation processor to the 'kapt' configuration.", set.name
    }
    
    /**
     * Gets the source directories which the Kotlin plugin contributes to the
     * given sourceSet. Returns an empty list when the sourceSet has no Kotlin
     * sources, or when the Kotlin plugin is not applied.
     * 
     * @param set SourceSet to inspect
     * @return list of Kotlin source directories
     */
    @PackageScope List<File> getKotlinSourceDirs(SourceSet set) {
        Project project = this.project
        
        // The Kotlin plugin adds a source directory set called 'kotlin' to each
        // sourceSet. It is looked up dynamically because the Kotlin plugin is not
        // a dependency of this plugin.
        def kotlinSourceSet = set.extensions.findByName('kotlin')
        if (kotlinSourceSet == null) {
            return []
        }
        
        try {
            return kotlinSourceSet.srcDirs.collect { project.file(it) }
        } catch (MissingPropertyException ex) {
            return []
        }
    }
    
    /**
     * Makes the given task depend on the ForgeGradle task which deobfuscates the
     * Minecraft jar, if such a task is present.
     * 
     * @param kaptTask Task to add the dependency to
     */
    @PackageScope void dependOnDeobfuscatedJar(Object kaptTask) {
        def deobfTask = project.tasks.findByName('deobfMcMCP')
        if (deobfTask == null) {
            return
        }
        
        kaptTask.dependsOn(deobfTask)
    }
    
    /**
     * Gets the directory which the given task should use for its intermediate
     * outputs. Tasks which do not have a temporary directory of their own (kapt
     * tasks, for example) get one allocated under the build directory.
     * 
     * @param task Task to get the temporary directory for
     * @return directory for the task's intermediate files
     */
    @PackageScope File getTemporaryDir(Object task) {
        if (task.hasProperty('temporaryDir')) {
            return task.temporaryDir
        }
        
        return new File(project.buildDir, "tmp/${task.name}")
    }
    
    /**
     * Collects the SRG files of the given type which were generated by any of
     * the annotation processor tasks for a sourceSet. A sourceSet can have
     * several of these (one per task) when it mixes Java and Kotlin sources.
     * 
     * @param srgFiles SRG files, keyed by the task which generated them
     * @param type Mapping type to collect
     * @return list of existing SRG files of the requested type
     */
    @PackageScope List<File> getSrgFiles(Map<Object, Map> srgFiles, ReobfMappingType type) {
        return srgFiles.values().collect { it[type] }.findAll { it != null && it.exists() }
    }
    
    /**
     * Merges the refmaps generated by each of the annotation processor tasks for
     * a sourceSet into a single refmap. A sourceSet which mixes Java and Kotlin
     * sources produces one refmap per task, and only a merged refmap describes
     * every mapping in the sourceSet.
     * 
     * @param refMapFiles Refmaps generated by the annotation processor tasks
     * @param target File to write the merged refmap to
     */
    @PackageScope void mergeRefMaps(List<File> refMapFiles, File target) {
        def generated = refMapFiles.findAll { it.exists() }
        if (generated.size() == 0) {
            target.delete()
            return
        }
        
        def slurper = new JsonSlurper()
        def merged = [:]
        
        for (File refMapFile : generated) {
            def current = slurper.parse(refMapFile)
            if (!(current instanceof Map)) {
                continue
            }
            merged = this.mergeRefMapNodes(merged, current)
        }
        
        if (merged.isEmpty()) {
            project.logger.warn "No refmap content was generated for {}, no refmap will be included in the artefact", target.name
            target.delete()
            return
        }
        
        target.parentFile.mkdirs()
        target.newWriter().withWriter { writer ->
            writer.write(JsonOutput.prettyPrint(JsonOutput.toJson(merged)))
        }
        
        project.logger.info "Merged refmap data from {} annotation processor task(s) into {}", generated.size(), target
    }
    
    /**
     * Recursively merges the nodes of one refmap into another. Only the mappings
     * node (class to member to mapping) needs merging, but this is done
     * generically so that the structure of the refmap does not have to be known
     * here.
     * 
     * @param target Node to merge into
     * @param source Node to merge from
     * @return the merged node
     */
    private Map mergeRefMapNodes(Map target, Map source) {
        for (Entry entry : source.entrySet()) {
            def value = entry.value
            if (value instanceof Map) {
                def existing = target[entry.key]
                target[entry.key] = this.mergeRefMapNodes(existing instanceof Map ? new LinkedHashMap(existing) : [:], value)
            } else if (!target.containsKey(entry.key)) {
                target[entry.key] = value
            }
        }
        
        return target
    }
    

    /**
     * Generates an "imports" file given the currently specified imports. If the
     * import set is empty then null is returned, otherwise generates and
     * returns a {@link File} which contains the generated import mappings.
     * 
     * @param compileTask Compile task for context
     * @return generated imports file or null if no imports in scope
     */
    private File generateImportsFile(Object compileTask) {
        File importsFile = new File(this.getTemporaryDir(compileTask), "mixin.imports.json")
        importsFile.delete()
        
        Set<File> libs = []
        
        for (Object cfg : this.importConfigs) {
            def config = (cfg instanceof Configuration) ? cfg : project.configurations.findByName(cfg.toString())
            if (config != null) {
                for (File file : config.files) {
                    libs += file
                }
            }
        }

        for (Object lib : this.importLibs) {
            libs += project.file(lib)
        }

        if (libs.size() == 0) {
            return null;
        }
        
        importsFile.newOutputStream().withStream { stream ->
            PrintWriter writer = new PrintWriter(stream);
            for (File lib : libs) {
                Imports[lib].appendTo(writer)
            }
            writer.flush()
        }
        
        return importsFile
    }
}

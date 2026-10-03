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

import groovy.json.JsonSlurper
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

import static org.junit.Assert.assertEquals
import static org.junit.Assert.assertFalse
import static org.junit.Assert.assertTrue

/**
 * Covers the refmap merging and SRG collection performed for a sourceSet which
 * is processed by more than one annotation processor task, i.e. one containing
 * both Java and Kotlin sources.
 */
class MixinExtensionTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder()

    /**
     * A refmap as produced by javac for a Java mixin, matching the shape
     * written by the Mixin annotation processor.
     */
    private static final String JAVA_REFMAP = '''\
        {
            "mappings": {
                "com/example/jmixin/mixin/JavaMixin": {
                    "updateEntityActionState": "func_70626_be",
                    "handler": "func_xxxx_yy"
                }
            },
            "data": {
                "searge": {},
                "notch": {}
            }
        }
        '''

    /**
     * A refmap as produced by kapt for a Kotlin mixin, using the same target
     * member as the Java mixin so that the two refmaps genuinely overlap.
     */
    private static final String KOTLIN_REFMAP = '''\
        {
            "mappings": {
                "com/example/ktmixin/mixin/KotlinMixin": {
                    "updateEntityActionState": "func_70626_be",
                    "kotlinHandler": "func_aaaa_bb"
                }
            },
            "data": {
                "searge": {},
                "notch": {}
            }
        }
        '''

    /**
     * A refmap which describes the same class as the Java refmap, with a
     * different mapping for the shared member plus one of its own.
     */
    private static final String OVERLAPPING_REFMAP = '''\
        {
            "mappings": {
                "com/example/jmixin/mixin/JavaMixin": {
                    "updateEntityActionState": "func_zzzz_yy",
                    "secondOnly": "func_second_only"
                }
            },
            "data": {
                "searge": {},
                "notch": {}
            }
        }
        '''

    private MixinExtension createExtension() {
        Project project = ProjectBuilder.builder().build()
        return new MixinExtension(project)
    }

    private File writeRefMap(String name, String contents) {
        File file = this.temporaryFolder.newFile(name)
        file.setText(contents, 'UTF-8')
        return file
    }

    /**
     * The core of the mixed-sourceSet support: a refmap for the Java mixin and
     * one for the Kotlin mixin must merge into a single refmap which describes
     * both classes, rather than one overwriting the other.
     */
    @Test
    void mergesRefMapsFromBothProcessorTasks() {
        MixinExtension extension = this.createExtension()
        File javaRefMap = this.writeRefMap('main-java.refmap.json', JAVA_REFMAP)
        File kotlinRefMap = this.writeRefMap('main-kotlin.refmap.json', KOTLIN_REFMAP)
        File target = new File(this.temporaryFolder.root, 'merged/main.refmap.json')

        extension.mergeRefMaps([javaRefMap, kotlinRefMap], target)

        assertTrue 'the merged refmap should have been written', target.exists()

        def merged = new JsonSlurper().parse(target)
        assertEquals 'both mixin classes should be described by the merged refmap', 2,
                merged.mappings.size()
        assertEquals 'func_70626_be',
                merged.mappings['com/example/jmixin/mixin/JavaMixin'].updateEntityActionState
        assertEquals 'func_70626_be',
                merged.mappings['com/example/ktmixin/mixin/KotlinMixin'].updateEntityActionState

        // Members which are unique to each mixin must survive the merge too.
        assertEquals 'func_xxxx_yy',
                merged.mappings['com/example/jmixin/mixin/JavaMixin'].handler
        assertEquals 'func_aaaa_bb',
                merged.mappings['com/example/ktmixin/mixin/KotlinMixin'].kotlinHandler
    }

    /**
     * Both processor tasks can describe the same class, because kapt is handed
     * the Java sources of a sourceSet as well as javac being handed them, so
     * merging must cope with overlapping refmaps. Where the two disagree about
     * a member the mapping merged first wins, as both compute the same mapping
     * for the same target, but members which only one of them describes must
     * still be carried over.
     */
    @Test
    void keepsFirstMappingWhenRefMapsOverlap() {
        MixinExtension extension = this.createExtension()
        File javaRefMap = this.writeRefMap('main-java.refmap.json', JAVA_REFMAP)
        File overlapping = this.writeRefMap('main-overlapping.refmap.json', OVERLAPPING_REFMAP)
        File target = new File(this.temporaryFolder.root, 'merged/main.refmap.json')

        extension.mergeRefMaps([javaRefMap, overlapping], target)

        def merged = new JsonSlurper().parse(target)
        assertEquals 'the mapping merged first must win',
                'func_70626_be',
                merged.mappings['com/example/jmixin/mixin/JavaMixin'].updateEntityActionState
        assertEquals 'a member only the second refmap describes must survive',
                'func_second_only',
                merged.mappings['com/example/jmixin/mixin/JavaMixin'].secondOnly
        assertEquals 'the shared member from the first refmap must survive',
                'func_xxxx_yy',
                merged.mappings['com/example/jmixin/mixin/JavaMixin'].handler
    }

    /**
     * Non-mapping nodes must be carried across the merge, otherwise the
     * top-level refmap structure the processor relies on would be lost.
     */
    @Test
    void preservesNonMappingNodesWhenMerging() {
        MixinExtension extension = this.createExtension()
        File javaRefMap = this.writeRefMap('main-java.refmap.json', JAVA_REFMAP)
        File kotlinRefMap = this.writeRefMap('main-kotlin.refmap.json', KOTLIN_REFMAP)
        File target = new File(this.temporaryFolder.root, 'merged/main.refmap.json')

        extension.mergeRefMaps([javaRefMap, kotlinRefMap], target)

        def merged = new JsonSlurper().parse(target)
        assertTrue 'the data node should survive the merge', merged.containsKey('data')
        assertTrue 'the searge environment should survive the merge',
                merged.data.containsKey('searge')
        assertTrue 'the notch environment should survive the merge',
                merged.data.containsKey('notch')
    }

    /**
     * A sourceSet with a single processor task must produce the same refmap as
     * it always did, i.e. merging must not change the Java-only case.
     */
    @Test
    void mergesSingleRefMapUnchanged() {
        MixinExtension extension = this.createExtension()
        File javaRefMap = this.writeRefMap('main-java.refmap.json', JAVA_REFMAP)
        File target = new File(this.temporaryFolder.root, 'merged/main.refmap.json')

        extension.mergeRefMaps([javaRefMap], target)

        def merged = new JsonSlurper().parse(target)
        assertEquals 1, merged.mappings.size()
        assertEquals 'func_70626_be',
                merged.mappings['com/example/jmixin/mixin/JavaMixin'].updateEntityActionState
        assertTrue merged.containsKey('data')
    }

    /**
     * Tasks which did not produce a refmap must not break the merge.
     */
    @Test
    void skipsMissingRefMapFiles() {
        MixinExtension extension = this.createExtension()
        File kotlinRefMap = this.writeRefMap('main-kotlin.refmap.json', KOTLIN_REFMAP)
        File absent = new File(this.temporaryFolder.root, 'absent.refmap.json')
        File target = new File(this.temporaryFolder.root, 'merged/main.refmap.json')

        extension.mergeRefMaps([absent, kotlinRefMap], target)

        assertTrue target.exists()
        def merged = new JsonSlurper().parse(target)
        assertEquals 'only the generated refmap should contribute', 1, merged.mappings.size()
        assertTrue merged.mappings.containsKey('com/example/ktmixin/mixin/KotlinMixin')
    }

    /**
     * When no processor task produced a refmap the target must be removed, so
     * that a stale refmap from a previous build is not packaged.
     */
    @Test
    void deletesTargetWhenNoRefMapWasGenerated() {
        MixinExtension extension = this.createExtension()
        File stale = this.temporaryFolder.newFile('main.refmap.json')
        stale.setText('{"mappings":{"stale":"value"}}', 'UTF-8')

        extension.mergeRefMaps([], stale)

        assertFalse 'a stale refmap should not survive when nothing generated one', stale.exists()
    }

    /**
     * A refmap which is not a JSON object must be ignored rather than causing a
     * failure, since a processor task is free to leave something else behind.
     */
    @Test
    void ignoresRefMapWhichIsNotAnObject() {
        MixinExtension extension = this.createExtension()
        File malformed = this.writeRefMap('malformed.refmap.json', '[1, 2, 3]')
        File target = this.temporaryFolder.newFile('main.refmap.json')

        extension.mergeRefMaps([malformed], target)

        assertFalse 'no refmap content means no refmap in the artefact', target.exists()
    }

    /**
     * Both the javac and the kapt SRG outputs must reach the reobf task,
     * otherwise a Kotlin mixin's mappings would be missing at reobfuscation.
     */
    @Test
    void collectsSrgFilesFromEveryProcessorTask() {
        MixinExtension extension = this.createExtension()
        File javacSearge = this.temporaryFolder.newFile('mcp-srg.srg')
        File javacNotch = this.temporaryFolder.newFile('mcp-notch.srg')
        File kaptSearge = this.temporaryFolder.newFile('kapt-mcp-srg.srg')
        File kaptNotch = this.temporaryFolder.newFile('kapt-mcp-notch.srg')

        def javacTask = this.temporaryFolder.newFile('javac')
        def kaptTask = this.temporaryFolder.newFile('kapt')
        def srgFiles = [
                javacTask: [(ReobfMappingType.SEARGE): javacSearge, (ReobfMappingType.NOTCH): javacNotch],
                kaptTask: [(ReobfMappingType.SEARGE): kaptSearge, (ReobfMappingType.NOTCH): kaptNotch]
        ]

        assertEquals([javacSearge, kaptSearge],
                extension.getSrgFiles(srgFiles, ReobfMappingType.SEARGE))
        assertEquals([javacNotch, kaptNotch],
                extension.getSrgFiles(srgFiles, ReobfMappingType.NOTCH))
    }

    /**
     * A processor task which produced no SRG for an environment must not be
     * contributed as a missing file.
     */
    @Test
    void collectsOnlySrgFilesWhichExist() {
        MixinExtension extension = this.createExtension()
        File javacSearge = this.temporaryFolder.newFile('mcp-srg.srg')
        File absentNotch = new File(this.temporaryFolder.root, 'mcp-notch.srg')

        def javacTask = this.temporaryFolder.newFile('javac')
        def kaptTask = this.temporaryFolder.newFile('kapt')
        def srgFiles = [
                javacTask: [(ReobfMappingType.SEARGE): javacSearge, (ReobfMappingType.NOTCH): absentNotch],
                kaptTask: [:]
        ]

        assertEquals([javacSearge], extension.getSrgFiles(srgFiles, ReobfMappingType.SEARGE))
        assertEquals([], extension.getSrgFiles(srgFiles, ReobfMappingType.NOTCH))
    }
}

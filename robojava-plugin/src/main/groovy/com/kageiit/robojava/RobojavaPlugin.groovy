package com.kageiit.robojava

import com.android.build.gradle.AppPlugin
import com.android.build.gradle.LibraryPlugin
import com.squareup.javawriter.JavaWriter
import groovy.json.StringEscapeUtils
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.dsl.RepositoryHandler
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test

import static javax.lang.model.element.Modifier.FINAL
import static javax.lang.model.element.Modifier.PUBLIC
import static javax.lang.model.element.Modifier.STATIC

class RobojavaPlugin implements Plugin<Project> {

    Project robojavaProject
    Project androidProject

    public static final String COBERTURA_PLUGIN_CLASS_NAME = "net.saliman.gradle.plugin.cobertura.CoberturaPlugin"

    @Override
    void apply(Project project) {
        robojavaProject = project

        //validation
        if (!robojavaProject.hasProperty('androidProject')) {
            throw new IllegalStateException("ext.androidProject must be defined before applying plugin.")
        }

        androidProject = robojavaProject.rootProject.project(":${robojavaProject.ext.androidProject}")

        def plugins = androidProject.plugins
        def hasAppPlugin = plugins.hasPlugin(AppPlugin)
        def hasLibraryPlugin = plugins.hasPlugin(LibraryPlugin)

        def androidPlugin
        if (hasAppPlugin) {
            androidPlugin = plugins.getPlugin(AppPlugin)
        } else if (hasLibraryPlugin) {
            androidPlugin = plugins.getPlugin(LibraryPlugin)
        } else {
            throw new IllegalStateException("${androidProject.name} is not an android project.")
        }

        robojavaProject.apply plugin: 'java'
        robojavaProject.repositories { RepositoryHandler repositoryHandler ->
            repositoryHandler.addAll(androidProject.repositories)
        }

        def android = androidProject.android
        def variants = hasAppPlugin ? android.applicationVariants : android.libraryVariants

        def variant = null
        def flavor = ""
        def hasFlavor = false
        if (robojavaProject.hasProperty("variant")) {
            variant = variants.find({ it.name == robojavaProject.variant })
            flavor = variant.productFlavors.collect { it.name.capitalize() }[0]
            hasFlavor = !"".equals(flavor)
        }
        // fall back to testBuildType
        if (variant == null) {
            variant = variants.find({ it.name == android.testBuildType })
        }
        // fall back to debug
        if (variant == null) {
            variant = "debug"
        }

        //detect source directories
        def flavorTestSources = []
        def flavorTestResources = []
        if (hasFlavor) {
            flavorTestSources = android.sourceSets["androidTest${flavor}"]["java"].srcDirs.asList()
            flavorTestResources = android.sourceSets["androidTest${flavor}"].resources.srcDirs.asList()
        }

        robojavaProject.sourceSets.test.java.srcDirs = android.sourceSets.androidTest.java.srcDirs.asList() + flavorTestSources
        robojavaProject.sourceSets.test.resources.srcDirs = android.sourceSets.androidTest.resources.srcDirs.asList() + flavorTestResources

        //detect configurations
        def androidCompile = addConfiguration("compile")
        def androidTestCompile = addConfiguration("androidTestCompile")
        def androidFlavorCompile = addConfiguration("${variant.name}Compile")

        def androidFlavorTestCompile = null
        if (hasFlavor) {
            androidFlavorTestCompile = addConfiguration("androidTest${flavor}Compile")
        }
        //add dependencies in the right order
        robojavaProject.dependencies {
            compile 'junit:junit:4.12'
            compile variant.javaCompile.outputs.files
            compile variant.javaCompile.classpath
            compile androidCompile
            compile androidFlavorCompile
            compile robojavaProject.files(androidPlugin.getBootClasspath())
            testCompile androidTestCompile
            if (hasFlavor) {
                testCompile androidFlavorTestCompile
            }
        }


        def processedManifestPath = variant.outputs[0].processManifest.manifestOutputFile.absolutePath
        def processedResourcesPath = variant.mergeResources.outputDir.absolutePath
        def processedAssetsPath = variant.mergeAssets.outputDir.absolutePath

        //configure test task
        robojavaProject.tasks.withType(Test) {
            outputs.upToDateWhen { false }
            scanForTestClasses = false
            include "**/*Test.class"
        }

        androidProject.tasks.withType(JavaCompile) {
            it.doFirst {
                writeProperties(processedManifestPath, processedResourcesPath, processedAssetsPath)
            }
        }

        //configure cobertura gradle plugin if applied
        try {
            if (robojavaProject.plugins.hasPlugin(Class.forName(COBERTURA_PLUGIN_CLASS_NAME))) {
                def flavorSources = []
                if (hasFlavor) {
                    flavorSources = android.sourceSets[flavor.toLowerCase()]["java"].srcDirs.asList()
                }
                robojavaProject.cobertura {
                    coverageDirs = variant.javaCompile.outputs.files.collect { it.toString() }
                    coverageSourceDirs = android.sourceSets.main.java.srcDirs.asList() + flavorSources
                    auxiliaryClasspath += variant.javaCompile.classpath
                    coverageExcludes = [".*\\.package-info.*", ".*\\.R.*", ".*BuildConfig.*"]
                }

            }
        } catch (ClassNotFoundException ignored) {
        }

        //write metadata useful for custom test runner
        writeProperties(processedManifestPath, processedResourcesPath, processedAssetsPath)
    }

    def addConfiguration(String name) {
        def configuration = androidProject.getConfigurations().getByName(name).copyRecursive()
        robojavaProject.configurations.add(configuration)
        return configuration
    }

    def writeProperties(String manifest, String resources, String assets) {
        def roboJavaOutputDir = new File("${androidProject.buildDir}/generated-sources/com/kageiit/robojava")
        roboJavaOutputDir.mkdirs()
        def robojavaConfigFile = new File(roboJavaOutputDir, "RobojavaConfig.java")
        JavaWriter writer = new JavaWriter(new FileWriter(robojavaConfigFile))
        writer.emitSingleLineComment("Code generated by Robojava plugin. Do not modify!")
                .emitPackage("com.kageiit.robojava")
                .beginType("RobojavaConfig", "class", EnumSet.of(PUBLIC, FINAL))
        if (new File(manifest).exists()) {
            writer.emitField("String", "MANIFEST", EnumSet.of(PUBLIC, STATIC, FINAL),
                    "\"" + StringEscapeUtils.escapeJava(manifest) + "\"")
        }
        if (new File(resources).exists()) {
            writer.emitField("String", "RESOURCES", EnumSet.of(PUBLIC, STATIC, FINAL),
                    "\"" + StringEscapeUtils.escapeJava(resources) + "\"")
        }
        if (new File(assets).exists()) {
            writer.emitField("String", "ASSETS", EnumSet.of(PUBLIC, STATIC, FINAL),
                    "\"" + StringEscapeUtils.escapeJava(assets) + "\"")
        }
        writer.endType().close();
    }
}

package com.fongmi.gradle

import org.gradle.api.Project

class AbiApkPackaging {

    private static final Map<String, String> ABI_PAIRS = ['arm64-v8a': 'armeabi-v7a', 'armeabi-v7a': 'arm64-v8a'].asImmutable()

    static void configure(Project project, Object apkArtifact) {
        def android = project.extensions.getByName('android')
        def components = project.extensions.getByName('androidComponents')
        configureAbis(project, android)
        components.onVariants(components.selector().withBuildType('release')) { variant ->
            def device = configureOutputFileNames(variant)
            if (android.signingConfigs.findByName('release')?.storeFile != null) {
                configureFinalizer(project, android, components, variant, apkArtifact)
                configureReleaseExport(project, variant, device)
            } else {
                project.tasks.matching { it.name == "assemble${variant.name.capitalize()}" }.configureEach {
                    doFirst { throw new org.gradle.api.GradleException('Configure the permanent release signing key in local.properties before packaging a family release.') }
                }
            }
        }
    }

    static String otherAbi(String abi) {
        return ABI_PAIRS[abi]
    }

    private static void configureAbis(Project project, def android) {
        def abis = project.providers.gradleProperty('apkAbis').getOrElse('armeabi-v7a,arm64-v8a').split(',').collect { it.trim() }
        if (abis.isEmpty() || abis.any { !ABI_PAIRS.containsKey(it) }) {
            throw new org.gradle.api.GradleException('Unsupported apkAbis: ' + abis)
        }
        android.splits.abi {
            enable = true
            reset()
            include(*abis)
            universalApk = false
        }
    }

    private static String configureOutputFileNames(def variant) {
        def flavors = variant.productFlavors.collectEntries { [(it.first): it.second] }
        def device = flavors['device'] ?: 'device'
        variant.outputs.each { output ->
            def abi = output.filters.find { it.filterType.name() == 'ABI' }?.identifier?.replace('-', '_') ?: 'universal'
            output.outputFileName.set("${device}-${abi}.apk")
        }
        return device
    }

    private static void configureFinalizer(Project project, def android, def components, def variant, Object apkArtifact) {
        def windows = System.getProperty('os.name').toLowerCase(Locale.ROOT).contains('windows')
        def buildToolsDir = components.sdkComponents.sdkDirectory.get().dir("build-tools/${android.buildToolsVersion}").asFile
        def signingConfig = android.signingConfigs.release
        def finalizeTask = project.tasks.register("finalize${variant.name.capitalize()}Apks", FinalizeApksTask) { task ->
            task.zipalignFile.set(new File(buildToolsDir, windows ? 'zipalign.exe' : 'zipalign'))
            task.apksignerJar.set(new File(buildToolsDir, 'lib/apksigner.jar'))
            task.javaExecutable.set(new File(System.getProperty('java.home'), windows ? 'bin/java.exe' : 'bin/java'))
            task.signingStoreFile.set(signingConfig.storeFile)
            task.keyAlias.set(signingConfig.keyAlias)
            task.storePassword.set(signingConfig.storePassword)
            task.keyPassword.set(signingConfig.keyPassword)
        }
        def request = variant.artifacts.use(finalizeTask)
                .wiredWithDirectories({ task -> task.inputDirectory }, { task -> task.outputDirectory })
                .toTransformMany(apkArtifact)
        finalizeTask.configure { task ->
            task.transformationRequest.set(request)
        }
    }

    private static void configureReleaseExport(Project project, def variant, String device) {
        def taskName = "assemble${variant.name.capitalize()}"
        def apkDirectory = project.layout.buildDirectory.dir("outputs/apk/${device}/release").get().asFile
        project.tasks.matching { it.name == taskName }.configureEach {
            doLast {
                project.copy {
                    from project.fileTree(dir: apkDirectory, include: "${device}-*.apk")
                    into project.rootProject.file('Release/apk')
                    eachFile { it.path = it.name }
                    includeEmptyDirs = false
                }
            }
        }
    }
}

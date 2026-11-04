plugins{
    java
}

sourceSets.main{
    java.setSrcDirs(listOf("src"))
}
sourceSets.test{
    java.setSrcDirs(listOf("test"))
}

// Mindustry version to depend on.
// Valid values:
// - latest: depend on the latest release of mindustry
// - be: depend on the very latest commit of mindustry
// - v<number>: depend on a specific version
val mindustryVersion = "v160.7"
val isWindows = System.getProperty("os.name").lowercase().contains("windows")
val sdkRoot: String? = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
val projectName = project.name

repositories{
    mavenCentral()

    //Downloads the dependencies JAR file from Mindustry releases; does not use any real repository. Surprisingly, this is the most reliable option.
    ivy{
        url = uri("https://github.com/")
        patternLayout{
            val pattern = when(mindustryVersion){
                "latest" -> "/[organisation]/[module]/releases/[revision]/download/dependencies.jar" //latest stable release
                "be" -> "/[organisation]/[module]/releases/download/master/[revision].jar" //latest commit (BE)
                else -> "/[organisation]/[module]/releases/download/[revision]/dependencies.jar" //specific release
            }
            val path = pattern.substring(0, pattern.lastIndexOf('/'))

            artifact("$path/[classifier].jar")
            artifact(pattern.replaceFirst(Regex("""\.jar$"""), "(-[classifier]).jar"))
        }
        metadataSources{ artifact() }

        content{
            if(mindustryVersion == "be"){
                //BE artifact version is always 'latest'
                includeVersion("Anuken", "MindustryBuilds", "latest")
            }else{
                includeVersion("Anuken", "Mindustry", mindustryVersion)
            }
        }
    }
}

java{
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies{
    // Optional offline override, e.g. -PmindustryJar=/path/to/v160.7/dependencies.jar.
    val localJar = providers.gradleProperty("mindustryJar").orNull
    if(localJar != null) compileOnly(files(localJar))
    else compileOnly(if(mindustryVersion == "be") "Anuken:MindustryBuilds:latest" else "Anuken:Mindustry:$mindustryVersion")
    testImplementation(files(configurations.compileClasspath))
}

tasks.withType<JavaCompile>().configureEach{
    options.release.set(17)
    options.encoding = "UTF-8"
}

val regressionTests = listOf("autobuild.RemotePortsTest", "autobuild.PlanningLifecycleTest", "autobuild.plan.GridRouterTest", "autobuild.plan.LatticeSearchTest")
val plannerTests = listOf("autobuild.PlannerTest", "autobuild.DefenseTest")
// These suites have main methods and explicit assertions, not JUnit annotations.
tasks.test { enabled = false }
(regressionTests + plannerTests).forEach { entry ->
    val taskName = entry.substringAfterLast('.').replaceFirstChar { it.lowercase() }
    val testTask = tasks.register<JavaExec>(taskName){
        group = "verification"
        dependsOn(tasks.testClasses)
        classpath = sourceSets.test.get().runtimeClasspath
        mainClass.set(entry)
        maxHeapSize = "1g"
    }
    tasks.check { dependsOn(testTask) }
}
tasks.register("regressionTest"){
    group = "verification"
    dependsOn(regressionTests.map { it.substringAfterLast('.').replaceFirstChar { c -> c.lowercase() } })
}

tasks.jar{
    archiveFileName.set("${projectName}Desktop.jar")

    from(configurations.runtimeClasspath.map{ files ->
        files.map{ if(it.isDirectory) it else zipTree(it) }
    })

    from(projectDir){
        include("mod.hjson")
        include("icon.png")
    }

    from("assets/"){
        include("**")
    }
}

val jarAndroid = tasks.register("jarAndroid"){
    dependsOn(tasks.jar)

    doLast{
        val sdk = sdkRoot?.let{ File(it) }
        if(sdk == null || !sdk.exists()){
            throw GradleException("No valid Android SDK found. Ensure that ANDROID_HOME is set to your Android SDK directory.")
        }

        val platformRoot = File(sdk, "platforms").listFiles()
            ?.sortedDescending()
            ?.firstOrNull{ File(it, "android.jar").exists() }
            ?: throw GradleException("No android.jar found. Ensure that you have an Android platform installed.")

        //collect dependencies needed for desugaring
        val classpath = (configurations.compileClasspath.get().files +
                configurations.runtimeClasspath.get().files +
                File(platformRoot, "android.jar"))

        val d8 = if(isWindows) "d8.bat" else "d8"

        val command = buildList{
            add(d8)
            classpath.forEach{ addAll(listOf("--classpath", it.path)) }
            addAll(listOf("--min-api", "21"))
            addAll(listOf("--output", "${projectName}Android.jar"))
            add("${projectName}Desktop.jar")
        }

        //dex and desugar files - this requires d8 in your PATH
        val exitCode = ProcessBuilder(command)
            .directory(layout.buildDirectory.dir("libs").get().asFile)
            .inheritIO()
            .start()
            .waitFor()

        if(exitCode != 0) throw GradleException("d8 failed with exit code $exitCode")
    }
}

tasks.register<Jar>("deploy"){
    dependsOn(jarAndroid, tasks.jar)
    archiveFileName.set("$projectName.jar")

    val libs = layout.buildDirectory.dir("libs")
    from(provider{
        listOf(
            zipTree(libs.get().file("${projectName}Desktop.jar")),
            zipTree(libs.get().file("${projectName}Android.jar"))
        )
    })

    doLast{
        libs.get().file("${projectName}Android.jar").asFile.delete()
    }
}

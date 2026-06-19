import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import versioning.BuildConfig

plugins {
    id("com.gradleup.shadow")
}

tasks.named<ShadowJar>("shadowJar") {
    minimize {
        // adventure's DataComponentValueConverter gson provider is only referenced via
        // ServiceLoader, so minimize() strips it and adventure's static init then throws
        // (ServiceConfigurationError) on enable. Keep the gson serializer's classes.
        exclude(dependency("net.kyori:adventure-text-serializer-gson:.*"))
    }
    archiveFileName = "${rootProject.name}-${project.name}-${rootProject.version}.jar"
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    if (BuildConfig.relocate) {
        if (BuildConfig.shadePE) {
            relocate("io.github.retrooper.packetevents", "ac.grim.grimac.shaded.io.github.retrooper.packetevents")
            relocate("com.github.retrooper.packetevents", "ac.grim.grimac.shaded.com.github.retrooper.packetevents")
            relocate("net.kyori", "ac.grim.grimac.shaded.kyori") // use PE's built-in adventure instead when not shading PE
        }
        relocate("club.minnced", "ac.grim.grimac.shaded.discord-webhooks")
        relocate("org.slf4j", "ac.grim.grimac.shaded.slf4j") // Required by discord-webhooks
        relocate("github.scarsz.configuralize", "ac.grim.grimac.shaded.configuralize")
        relocate("com.github.puregero", "ac.grim.grimac.shaded.com.github.puregero")
        relocate("com.google.code.gson", "ac.grim.grimac.shaded.gson")
        relocate("alexh", "ac.grim.grimac.shaded.maps")
        relocate("it.unimi.dsi.fastutil", "ac.grim.grimac.shaded.fastutil")
        relocate("okhttp3", "ac.grim.grimac.shaded.okhttp3")
        relocate("okio", "ac.grim.grimac.shaded.okio")
        relocate("org.yaml.snakeyaml", "ac.grim.grimac.shaded.snakeyaml")
        relocate("org.json", "ac.grim.grimac.shaded.json")
        relocate("org.intellij", "ac.grim.grimac.shaded.intellij")
        relocate("org.jetbrains", "ac.grim.grimac.shaded.jetbrains")
        relocate("org.incendo", "ac.grim.grimac.shaded.incendo")
        relocate("io.leangen.geantyref", "ac.grim.grimac.shaded.geantyref") // Required by cloud
        relocate("com.zaxxer", "ac.grim.grimac.shaded.zaxxer") // Database history
    }
    mergeServiceFiles()

    // smile-core drags in ~70mb of openblas natives; tree boost doesnt need them
    exclude("org/bytedeco/**")
    exclude("smile/math/blas/**")

    when (BuildConfig.nativeTarget) {
        BuildConfig.NativeTarget.WINDOWS -> {
            exclude("org/sqlite/native/Linux/**")
            exclude("org/sqlite/native/Linux-Android/**")
            exclude("org/sqlite/native/Linux-Musl/**")
            exclude("org/sqlite/native/Mac/**")
            exclude("org/sqlite/native/FreeBSD/**")
        }
        BuildConfig.NativeTarget.LINUX -> {
            exclude("org/sqlite/native/Windows/**")
            exclude("org/sqlite/native/Mac/**")
            exclude("org/sqlite/native/FreeBSD/**")
        }
        BuildConfig.NativeTarget.MACOS -> {
            exclude("org/sqlite/native/Windows/**")
            exclude("org/sqlite/native/Linux/**")
            exclude("org/sqlite/native/Linux-Android/**")
            exclude("org/sqlite/native/Linux-Musl/**")
            exclude("org/sqlite/native/FreeBSD/**")
        }
        BuildConfig.NativeTarget.ALL -> Unit
    }
}

tasks.named("assemble") {
    dependsOn(tasks.named("shadowJar"))
}

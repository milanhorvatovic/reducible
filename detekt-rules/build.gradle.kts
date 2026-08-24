plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinter)
}

kotlin {
    explicitApi()
    jvmToolchain(17)
}

dependencies {
    compileOnly(libs.detekt.api)
    testImplementation(libs.detekt.test)
    testImplementation(libs.kotlin.test)
}

tasks.test {
    useJUnitPlatform()
}

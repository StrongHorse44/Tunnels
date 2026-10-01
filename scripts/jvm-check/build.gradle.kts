plugins { alias(libs.plugins.kotlin.jvm) apply false }
subprojects {
    layout.buildDirectory.set(rootProject.layout.buildDirectory.dir("out/${project.name}"))
}

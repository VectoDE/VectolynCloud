plugins {
    id("vectora.java-conventions")
}

dependencies {
    api(platform(libs.jackson.bom))
    api(libs.jackson.databind)
    implementation(libs.jackson.dataformat.yaml)
}

plugins {
    id("vectora.java-conventions")
}

dependencies {
    api(project(":Vectora-Protocol"))
    api(project(":VectoraCloud:cloud-config"))
    implementation(platform(libs.netty.bom))
    implementation(libs.netty.transport)
    implementation(libs.netty.handler)
    implementation(libs.netty.codec.http)
}

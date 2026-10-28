plugins {
    id("vectora.java-conventions")
}

dependencies {
    api(project(":VectoraCloud:cloud-config"))
    // Managed mode downloads the MariaDB server through the shared downloader.
    implementation(project(":VectoraCloud:cloud-download"))
    implementation(libs.mariadb.java.client)
}

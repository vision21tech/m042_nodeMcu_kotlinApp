plugins{
    id("java-library")

}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
}

//sourceCompatibility = "1.8"
//targetCompatibility = "1.8"

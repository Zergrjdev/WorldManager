plugins {
    java
}

group = "dev.worldmanager"
version = "1.0.0"

repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Paper's version scheme is <mcver>.build.<buildNo>. If 26.3 has a hotfix, use e.g. 26.3.1.build.+
    compileOnly("io.papermc.paper:paper-api:26.3.build.+")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

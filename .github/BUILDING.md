# Building YJHack

Requirements:

- Java 21
- Internet access for the first Gradle/Fabric dependency download

From the repository root:

```bash
cd Dev
./gradlew clean test build --warning-mode all
```

On Windows:

```bat
cd Dev
gradlew.bat clean test build --warning-mode all
```

The pinned Gradle version is 8.14.4. The bootstrap scripts verify the official distribution
SHA-256 before extraction when the historical wrapper jar is absent.

A successful build produces `YJHack-1.21.5.jar` in the repository root. The copy happens only
after `check` and `remapJar` succeed, so a failed test cannot publish a replacement jar.

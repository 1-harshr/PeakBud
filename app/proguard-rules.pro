# R8 rules for release builds. Libraries (Room, CameraX, Compose, Firebase) ship their own rules.

# Keep file names and line numbers so crash reports point at real code. Crashlytics de-obfuscates names
# using the mapping file that its Gradle plugin uploads for each release build.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

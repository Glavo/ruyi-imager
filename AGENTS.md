# Repository Instructions

These rules apply to code and workflow changes in this repository.

## Java Code

- Annotate every Java class with JetBrains `@NotNullByDefault`.
- Mark every nullable type use, field, parameter, return value, local variable, or generic type argument with `@Nullable`; do not use `Optional`.
- Prefer Java `record` types when they fit the data model.
- Mark immutable collections, arrays, and NIO buffers with `@Unmodifiable`; mark immutable or read-only views with `@UnmodifiableView`.
- Put array immutability annotations on the array dimensions, for example `String @Unmodifiable []` or `int @Unmodifiable [] @Unmodifiable []`.
- Document every class, field, and method with `///` Markdown-style Javadoc.
- Document record components with `@param` entries in the record documentation, not standalone component comments.
- Keep documentation accurate and specific; add implementation comments only for non-obvious logic.

## Gradle

- Run Gradle with workspace-local state, preferably `./gradlew -g .gradle-user-home ...`.
- Ensure Gradle commands include `GRADLE_USER_HOME=.gradle-user-home`.
- Ensure `GRADLE_OPTS` includes `--enable-native-access=ALL-UNNAMED --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED`.
- Use a ten-minute timeout for Gradle `test` tasks.

## Memory

- Use heap `long[]` pages as the default backing allocation, but store backing as an Unsafe base object plus byte offset.
- Access page backing through `jdk.internal.misc.Unsafe`.

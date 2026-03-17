Remove outdated or deprecated applications from the Android project as outlined in (Issue #32](https://github.com/oist/smartphone-robot-android/issues/32). This includes identifying unused modules, cleaning up related build configurations, deleting obsolete code paths, and ensuring that the remaining project structure is consistent, buildable, and free of legacy artifacts.

Edit (2026/2/26):
Adding to this things that were leftover from the Java2Kotlin refactor that only remained to keep the Java functionality. e.g.
removing get method definitions in favor of built-in kotlin getters for properties getField -> xxx.field
Ensure in the above that scope is maintained (private -> public is bad) unless justified and tested. 

I think this vaguely includes syntactic sugar so I'll lump that in here as well.
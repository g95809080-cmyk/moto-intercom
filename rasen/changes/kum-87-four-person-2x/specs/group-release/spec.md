## ADDED Requirements

### Requirement: Group distribution is maintained as 2.x
The four-person branch SHALL preserve the historical group source and build version 2.0.0+aee3b3f with code8. App source, Gradle and iOS SHALL remain unchanged from the old release.

#### Scenario: Migrate the old release
- **WHEN** the user requests the 1.3 group release become the 2.x line
- **THEN** metadata and assets are backed up, the release points to a new immutable tag and actual 2.0.0 APK, and the old Git tag is retained.

### Requirement: Preview evidence is truthful
Release notes SHALL distinguish source preservation and package validation from real four-device and road acceptance.

#### Scenario: Publish the renumbered package
- **WHEN** automated package gates pass
- **THEN** the package is published as a preview without claiming field issues have been fixed by renumbering.

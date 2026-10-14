## ADDED Requirements

### Requirement: Two product lines retain truthful source identity
The main branch SHALL maintain the reviewed two-person product with 1.x versions. The four-person branch SHALL preserve the historical group source with 2.x versions. Migration SHALL retain parent history and existing tags without force pushes.

#### Scenario: Publish the installed test package
- **WHEN** the user requests publication of 1.2.3+f587472
- **THEN** the original code7 APK and checksum are uploaded unchanged and physical acceptance gaps remain visible.

### Requirement: New packages use a global versionCode sequence
Both branches SHALL use version.properties and SHALL allocate globally increasing Android codes. Republishing identical existing bytes SHALL preserve their original code and hash.

#### Scenario: Create a new group package
- **WHEN** the historical group version is renumbered as 2.0.0
- **THEN** its rebuilt package uses code8 and future newly issued packages use at least code9.

# Re:ANIME CloudStream provider

This module adds Re:ANIME (`https://reanime.cz`) to the CloudStream plugin repository.

## Current implementation

- Anime-card search parsing
- Anime detail metadata and episode extraction
- SUB/DUB episode grouping
- Watch-page iframe and subtitle-track discovery
- Delegation of third-party embed URLs to CloudStream's extractor registry

The site's player can select among multiple server buttons at runtime. Server-specific JavaScript/API resolution still needs validation against live watch pages; an iframe is not assumed to be a direct media URL.

## Build

Windows:

```powershell
.\gradlew.bat ReAnimeProvider:make
```

The generated plugin is in `ReAnimeProvider/build/`.

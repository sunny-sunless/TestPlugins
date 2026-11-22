dependencies {
    // Jsoup and NiceHttp are inherited from the root build.
}

version = 1

cloudstream {
    description = "Re:ANIME provider for anime search, details, episodes and stream extraction."
    authors = listOf("sunny-sunless")
    status = 3
    tvTypes = listOf("Anime", "OVA", "AnimeMovie")
    requiresResources = false
    language = "en"
}

android {
    namespace = "com.reanime"
    buildFeatures {
        buildConfig = true
    }
}

pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }
rootProject.name = "XiaoLiLauncher"
include(":app")
include(":core:transfer-api")
include(":core:transfer-android")
include(":feature:quanlian")

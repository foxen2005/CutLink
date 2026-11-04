plugins {
    id("com.android.application")
    id("com.chaquo.python")
}

android {
    namespace = "cl.digitalfox.cameolink"
    compileSdk = 36

    defaultConfig {
        applicationId = "cl.digitalfox.cameolink"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "0.3.0"
        // La Pad 8 Pro y los teléfonos actuales son arm64; x86_64 solo para el emulador
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Solo la parte portable del bridge entra al APK: sin tests ni lo que depende de Windows
// (usbprint/gpgl/jobs/server/cli) ni de OpenCV (imagetrace). Una sola fuente de verdad: ../../bridge.
val bridgePy = layout.buildDirectory.dir("bridgePy")
val syncBridge by tasks.registering(Sync::class) {
    from("../../bridge") {
        include("__init__.py", "svgpaths.py", "studio3.py", "ordering.py", "dxfpaths.py", "mobile.py",
            "materials.json", "blades.json")
        into("bridge")
    }
    // presets propios del PC (los que guarda el panel de Illustrator), si existen
    val userPresets = File(System.getenv("APPDATA") ?: "", "CameoCut/presets.json")
    if (userPresets.isFile) from(userPresets) { rename { "user_presets.json" }; into("bridge") }
    // archivos de prueba (uno por formato) para el botón "Probar Python"
    from("../../bridge/tests/fixtures") {
        include("circles_6x6.studio3", "square50.svg", "etiquetas_90x50.dxf")
        into("cameo_samples")
    }
    into(bridgePy)
}

chaquopy {
    defaultConfig {
        version = "3.14"
        // mismo Python que el venv del bridge (3.14); el global C:\Python314 está roto
        buildPython("C:/Users/DigitalFox/AppData/Local/Programs/Python/Python314/python.exe")
        pip {
            install("svgelements==1.9.6")
        }
    }
    sourceSets {
        getByName("main") {
            srcDir("src/main/python")
            srcDir(bridgePy)
        }
    }
}

tasks.matching { it.name.contains("Python", ignoreCase = true) && it.name.startsWith("merge") }
    .configureEach { dependsOn(syncBridge) }

dependencies {
    testImplementation("junit:junit:4.13.2")
}

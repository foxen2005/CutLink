plugins {
    id("com.android.application") version "9.2.1" apply false
    // Python en la app: reusa los lectores del bridge (svgpaths, studio3, ordering)
    id("com.chaquo.python") version "17.2.0" apply false
}

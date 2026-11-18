// Programos skaičiavimo patikra be Android: Google GSDC duomenys arba programos ZIP įrašai.
// Paruošimas: python3 prepare.py (nukopijuoja reikalingus app šaltinius į app-src/), tada: gradle run --args="..."
plugins { kotlin("jvm") version "1.9.24"; application }
repositories { mavenCentral() }
kotlin { jvmToolchain(21) }
application { mainClass.set("lt.example.gnss.GsdcKt") }
sourceSets { main { kotlin.srcDirs("src/main/kotlin", "app-src") } }

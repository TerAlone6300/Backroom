package com.backrooms.game
// Entities.kt - Level 11 rules, entities, supplies, noclip destinations. Shared by desktop and Android (pure Kotlin/JVM, no AWT/Android imports).

// ---------- Entities & player state ----------
/** Level 11 Effect and the city's entities: everything to tune in one place. (Provocation is switched off for now, see Game.update.) */
object CityRules {
    /** A Faceling follows a person for this long (seconds of actually following), then loses interest. */
    const val FACELING_FOLLOW_SECONDS = 600.0
    /** Hounds start leading you to a stocked building when you are this close (cells). */
    const val HOUND_GUIDE_RANGE = 16.0
    /** A guiding Hound waits for you when you fall this far behind (cells). */
    const val HOUND_WAIT_DISTANCE = 8.0
    /** Seconds of crowding (or sprinting right past a Hound) before the Level 11 Effect wears off for that entity. Unused while provocation is switched off. */
    const val PROVOKE_SECONDS = 2.5
}

class Entity(var x: Double, var y: Double, val type: Int) {
    var dir = Math.random() * 6.283; var chase = false
    var home: IntArray? = null                 // Smiler in Level 11: footprint of its building [x0, y0, x1, y1]
    var provoked = false; var irr = 0.0        // Level 11 Effect lost / how close it is to being lost
    @Volatile var passive = false              // calm or friendly right now (drawn with the friendly look)
    var followT = 0.0                          // Faceling: seconds spent following you
    var gx = 0; var gy = 0; var hasGoal = false; var goalT = 0.0 // Hound: stocked building it leads you to
    var wx = 0; var wy = 0; var hasWp = false; var routeT = 0.0   // next cell on the way there
}

/** Supplies: 1 = ration (food), 2 = almond water (clears hallucinations, a little food). */
class Supply(val x: Int, val y: Int, val type: Int)
/** A hallucination: looks like an entity, drifts toward you, harmless, gone when you get close. */
class Ghost(var x: Double, var y: Double, val type: Int, var life: Double)

/** Where noclip wall `k` of level `lv` leads. In Explore mode, most of the time (ExploreRules.SAFE_NOCLIP_CHANCE) a Safe & Secure level instead. */
fun noclipDest(lv: Int, k: Int): Int {
    val fixed = LEVEL_SPECS[lv].noclip[k]
    if (!Settings.explore || Math.random() >= ExploreRules.SAFE_NOCLIP_CHANCE) return fixed
    val safe = LEVELS.indices.filter { LEVELS[it].safe && it != lv }
    return if (safe.isEmpty()) fixed else safe[(Math.random() * safe.size).toInt()]
}

/** Supplies are scattered through every level from 1 up. Level 0 keeps them all inside the Manila Room. */
fun supplyAt(lv: Int, x: Int, y: Int): Int {
    if (Settings.explore) return 0 // no hunger in Explore, so no supplies
    if (lv == 0 && !Manila.inside(x + 0.5, y + 0.5)) return 0
    if (isWall(lv, x, y)) return 0
    if (lv == 12 && City.cacheCell(x, y)) return if (h(x, y, 191) >= 0.22) 0 else if (h(x, y, 881) < 0.4) 2 else 1 // stocked building
    val p = if (lv == 0) 0.12 else if (lv == 12) 0.0006 else 0.0010
    if (h(x, y, 880 + lv) >= p) return 0
    return if (h(x, y, 881) < 0.4) 2 else 1
}

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

/** Entity types (Entity.type). Sprite codes from 20 up are items (see Renderer). */
const val E_HOUND = 1; const val E_SMILER = 2; const val E_STEALER = 3; const val E_FACELING = 4
const val E_DEATHMOTH = 5; const val E_CLUMP = 6; const val E_JERRY = 7; const val E_CULTIST = 8
const val E_WARRIOR = 9; const val E_TARANTULA = 10
const val S_RATION = 20; const val S_WATER = 21; const val S_LAMP = 22
/** Monsters Explore mode makes friendly. */
fun isMonster(type: Int) = type == E_HOUND || type == E_SMILER || type == E_STEALER || type == E_FACELING || type == E_CLUMP || type == E_TARANTULA
/** Entities that never hurt you and do not make the lights flicker. */
fun isHarmless(type: Int) = type == E_DEATHMOTH || type == E_JERRY || type == E_CULTIST || type == E_WARRIOR
/** Touching one ends the run (a Faceling only while it hunts you). */
fun isLethal(e: Entity) = isMonster(e.type) && (e.type != E_FACELING || e.chase)

class Entity(var x: Double, var y: Double, val type: Int) {
    var dir = Math.random() * 6.283; var chase = false
    var home: IntArray? = null                 // Smiler in Level 11: footprint of its building [x0, y0, x1, y1]
    var provoked = false; var irr = 0.0        // Level 11 Effect lost / how close it is to being lost
    @Volatile var passive = false              // calm or friendly right now (drawn with the friendly look)
    var followT = 0.0                          // Faceling: seconds spent following you
    var gx = 0; var gy = 0; var hasGoal = false; var goalT = 0.0 // Hound: stocked building it leads you to
    fun copy() = Entity(x, y, type).also { it.dir = dir; it.chase = chase; it.home = home; it.followT = followT }
    var wx = 0; var wy = 0; var hasWp = false; var routeT = 0.0   // next cell on the way there
}

/** Supplies: 1 = ration (food), 2 = almond water (clears hallucinations, a little food). */
class Supply(val x: Int, val y: Int, val type: Int)
/** A hallucination: looks like an entity, drifts toward you, harmless, gone when you get close. */
class Ghost(var x: Double, var y: Double, val type: Int, var life: Double)

/** Where noclip wall `k` of level `lv` leads. In Explore mode, most of the time (ExploreRules.SAFE_NOCLIP_CHANCE) a Safe & Secure level instead. */
fun noclipDest(lv: Int, k: Int): Int {
    val dest = levelSpec(lv).noclip
    if (dest.isEmpty()) return lv
    val fixed = dest[k]
    if (!Settings.explore || Math.random() >= ExploreRules.SAFE_NOCLIP_CHANCE) return fixed
    val safe = LEVELS.filter { it.safe && !it.unlisted && it.id != lv }.map { it.id }
    return if (safe.isEmpty()) fixed else safe[(Math.random() * safe.size).toInt()]
}

/** Supplies are scattered through every level from 1 up. Level 0 keeps them all inside the Manila Room. */
fun supplyAt(lv: Int, x: Int, y: Int): Int {
    if (Settings.explore || lv == LV_GRAVE) return 0 // no hunger in Explore, so no supplies
    if (lv == 0 && !Manila.inside(x + 0.5, y + 0.5)) return 0
    if (isWall(lv, x, y)) return 0
    if (lv == LV_CITY && City.cacheCell(x, y)) return if (h(x, y, 191) >= 0.22) 0 else if (h(x, y, 881) < 0.4) 2 else 1 // stocked building
    val p = if (lv == 0) 0.12 else if (lv == LV_CITY) 0.0006 else 0.0010
    if (h(x, y, 880 + lv) >= p) return 0
    return if (h(x, y, 881) < 0.4) 2 else 1
}

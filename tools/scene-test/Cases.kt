// Cases.kt - the game states the GPU renderer is checked against (shared by CpuDump.kt and SceneDump.kt).
import com.backrooms.game.*

class Case(val name: String, val g: Game, val t: Double, val others: List<DoubleArray>, val hud: Boolean)

private fun mk(name: String, lv: Int, x: Double? = null, y: Double? = null, a: Double = 0.7, hud: Boolean = true, t: Double = 3.7,
               fov: Int = 60, fog: Int = 100, others: Boolean = false, setup: (Game) -> Unit = {}): () -> Case = {
    Settings.fov = fov; Settings.fog = fog
    useWorld(1337)                 // the original world: the same on every run
    val g = Game(); g.enter(lv)
    g.entities.clear(); g.ghosts.clear()   // entities are random: the cases place their own
    if (x != null) { g.x = x; g.y = y!! } else { val p = spiralFree(lv); g.x = p.first; g.y = p.second } // enter() picks a random spot: use a fixed one
    g.a = a; g.showMap = true; g.stamina = 0.62
    setup(g)
    val o = if (others) listOf(doubleArrayOf(g.x + 2.2 * Math.cos(g.a + 0.25), g.y + 2.2 * Math.sin(g.a + 0.25), lv.toDouble())) else emptyList()
    Case(name, g, t, o, hud)
}

/** The free cell nearest to (2, 2), searched in growing rings: the same on every run. */
private fun spiralFree(lv: Int): Pair<Double, Double> {
    for (r in 0..40) for (dy in -r..r) for (dx in -r..r) {
        if (maxOf(Math.abs(dx), Math.abs(dy)) != r) continue
        if (free(lv, 2.5 + dx, 2.5 + dy)) return (2.5 + dx) to (2.5 + dy)
    }
    return 2.5 to 2.5
}

/** A cell next to the first not-quite-solid wall of the level, and the angle that faces it. */
private fun faceNoclipWall(g: Game) {
    val n = NOCLIPS[g.level]?.firstOrNull() ?: return
    for ((dx, dy) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
        val cx = n[0] + dx; val cy = n[1] + dy
        if (free(g.level, cx.toDouble() + 0.5, cy.toDouble() + 0.5)) {
            g.x = cx + 0.5 + dx * 0.9; g.y = cy + 0.5 + dy * 0.9
            g.a = Math.atan2(-dy.toDouble(), -dx.toDouble()); return
        }
    }
}

fun allCases(): List<() -> Case> {
    val out = ArrayList<() -> Case>()
    for (d in LEVELS) if (levelSpec(d.id).world == null) out.add(mk("level_${d.id}", d.id))
    out.add(mk("lv0_fog_off_wide", 0, fog = 0, fov = 110, a = 2.1))
    out.add(mk("lv0_fov_narrow_night", 0, fov = 30, t = 41.2, a = 4.0))
    out.add(mk("lv0_manila_room", 0, Manila.CX + 4.5, Manila.CY + 4.5, a = 0.3))
    out.add(mk("lv0_unstable_wall", 0, setup = ::faceNoclipWall))
    out.add(mk("lv0_look_up", 0, setup = { it.pitch = 0.5; it.bob = 1.3; it.bobAmp = 0.8 }))
    out.add(mk("lv0_look_down", 0, setup = { it.pitch = -0.6; it.bob = 2.1; it.bobAmp = 1.0 }))
    out.add(mk("lv1_monsters", 1, a = 0.0, others = true, setup = { g ->
        val types = listOf(E_HOUND, E_SMILER, E_STEALER, E_FACELING, E_DEATHMOTH, E_CLUMP, E_JERRY, E_CULTIST, E_WARRIOR, E_TARANTULA)
        for ((i, ty) in types.withIndex()) g.entities.add(Entity(g.x + 2.0 + i * 0.55, g.y + (if (i % 2 == 0) -1.0 else 1.0) * (0.3 + 0.15 * i), ty))
        g.ghosts.add(Ghost(g.x + 2.5, g.y + 0.1, E_SMILER, 5.0))
    }))
    out.add(mk("lv1_items_and_players", 1, a = 0.0, others = true, setup = { g ->
        g.supplies = listOf(Supply(g.x.toInt() + 2, g.y.toInt(), S_RATION), Supply(g.x.toInt() + 3, g.y.toInt() + 1, S_WATER), Supply(g.x.toInt() + 3, g.y.toInt() - 1, S_LAMP))
    }))
    out.add(mk("lv2_zone_a", 2, 31.5, 22.5, a = 1.2))
    out.add(mk("lv2_zone_b", 2, 83.5, 61.5, a = 3.0))
    out.add(mk("lv6_lantern", 6, a = 2.0))
    out.add(mk("lv37_water", 37, a = 5.0))
    out.add(mk("fx_danger", 1, a = 0.7, setup = { it.danger = 0.9; it.presence = 0.8 }))
    out.add(mk("fx_halluc_sway", 0, a = 0.7, setup = { it.halluc = 0.9 }))
    out.add(mk("fx_noclip_glitch", 0, a = 0.7, setup = { it.noclip = 0.8 }))
    out.add(mk("fx_near_flash", 1, a = 0.7, setup = { it.near = 1.0; it.flash = 0.3 }))
    out.add(mk("fx_dead_fade", 0, a = 0.7, setup = { it.dead = true; it.deadT = 0.6 }))
    out.add(mk("hud_off", 0, hud = false))
    return out
}

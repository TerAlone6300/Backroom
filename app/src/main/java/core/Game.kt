package com.backrooms.game
// Game.kt - game state and the per-frame update. Shared by desktop and Android (pure Kotlin/JVM, no AWT/Android imports).
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.*

class Game {
    var x = 1.5; var y = 1.5
    @Volatile var a = 0.0
    @Volatile var pitch = 0.0 // -1 (down) .. 1 (up)
    var level = 0; var levelT = 0.0
    var stamina = 1.0; var exhausted = false
    var bob = 0.0; var bobAmp = 0.0
    var showMap = true
    var dead = false; var deadT = 0.0; var danger = 0.0
    var near = 0.0; var manilaNear = 0.0; var manilaT = 0.0
    var presence = 0.0 // 0..1: how close the nearest entity is; lamps stutter more the closer it gets
    var noclip = 0.0; var flash = 0.0; var arrival = "YOU SLIPPED OUT OF REALITY"
    var playSec = 0.0 // total time actually played (paused time does not count), stored in saves
    val entities = CopyOnWriteArrayList<Entity>()
    // food, supplies, hallucinations
    @Volatile var hunger = 1.0; @Volatile var rations = 0; @Volatile var water = 0; var starveT = 0.0
    @Volatile var eatReq = false; @Volatile var drinkReq = false // set by the UI threads, handled in update()
    var lobbyT = 0.0; @Volatile var halluc = 0.0; val ghosts = CopyOnWriteArrayList<Ghost>(); private var ghostCd = 4.0
    @Volatile var supplies: List<Supply> = emptyList()
    private val taken = HashSet<Long>()
    private var scanX = Int.MIN_VALUE; private var scanY = 0
    private class Origin(val level: Int, val x: Double, val y: Double, val a: Double, val pitch: Double, val ents: List<Entity>)
    private var origin: Origin? = null          // Level 139: the dangerous situation you were pulled out of
    @Volatile var hardcoreRun = false        // this run is Hardcore: the difficulty is locked and death leads to The Grave
    private var warnGrave = false
    private var grace = 0.0                      // seconds after returning from the trial in which nothing can catch you
    private var trialT = 0.0; private var warnTrial = false; private var warnReworked = false
    private var warnHungry = false; private var warnHallu = false; private var warnJerry = false
    private val roomsDone = HashSet<Long>(); private var roomT = 0.0
    var notice = ""; @Volatile var noticeT = 0.0; var deathMsg = "YOU WERE CAUGHT"
    fun say(s: String) { notice = s; noticeT = 3.5 }
    init { enter(0) }

    /** Unlock a level for the drawer, unless Explore is set to not save the levels you pass through. */
    private fun remember(lv: Int) { if (!Settings.explore || ExploreRules.SAVE_VISITED_LEVELS) Progress.unlock(lv) }
    private fun cellKey(x: Int, y: Int) = (x.toLong() shl 32) xor (y.toLong() and 0xFFFFFFFFL)
    private fun resetLevelState() { taken.clear(); scanX = Int.MIN_VALUE; supplies = emptyList(); ghosts.clear(); halluc = 0.0; lobbyT = 0.0; ghostCd = 4.0; warnHallu = false; warnJerry = false; roomsDone.clear(); roomT = 0.0; trialT = 0.0; warnTrial = false; warnReworked = false; warnGrave = false }
    /** Supplies within 13 cells (redone whenever you change cell); what the renderer draws and what you can pick up. */
    private fun rescan(cx: Int, cy: Int) {
        scanX = cx; scanY = cy
        val out = ArrayList<Supply>()
        for (dy in -13..13) for (dx in -13..13) { val t = supplyAt(level, cx + dx, cy + dy); if (t != 0 && cellKey(cx + dx, cy + dy) !in taken) out.add(Supply(cx + dx, cy + dy, t)) }
        supplies = out
    }

    /** True when other players cannot see you (Level 0 and Level 201), except inside the Manila Room. */
    fun isolated() = levelDef(level).isolated && !(level == 0 && Manila.inside(x, y))

    private var ncK = 0
    private fun noclipTarget() = noclipDest(level, ncK)

    private fun findFree(cx: Double, cy: Double): Pair<Double, Double> {
        for (r in 0..40) for (dy in -r..r) for (dx in -r..r) {
            if (max(abs(dx), abs(dy)) != r) continue
            val px = floor(cx).toInt() + dx + 0.5; val py = floor(cy).toInt() + dy + 0.5
            if (free(level, px, py, 0.3)) return px to py
        }
        return cx to cy
    }

    fun enter(lv: Int, arrived: String = "YOU SLIPPED OUT OF REALITY") {
        level = lv; levelT = 0.0; dead = false; deadT = 0.0; danger = 0.0; noclip = 0.0; arrival = arrived; resetLevelState()
        if (lv == LV_CITY) City.warm()
        if (lv == LV_CITY) say("LEVEL 11 EFFECT  ·  hostile entities are less likely to attack you")
        stamina = 1.0; exhausted = false; pitch = 0.0; a = Math.random() * 6.283
        remember(lv)
        // Level 0: you land somewhere in the endless maze, a few minutes' walk from the (unique) Manila Room.
        // Other levels drop you near the origin so players can regroup.
        val sp = levelSpec(lv).spawn
        val p = if (sp != null) findFree(sp[0], sp[1]) else if (lv == 0) { val ang0 = Math.random() * 6.283; val d0 = 60 + Math.random() * 200
                               findFree(Manila.CX + 4.5 + cos(ang0) * d0, Manila.CY + 4.5 + sin(ang0) * d0) }
                else findFree(4 + Math.random() * 3, 4 + Math.random() * 3)
        x = p.first; y = p.second
        spawnEntities()
        if (lv == LV_WARRIOR) spawnTrial()
    }

    private fun spawnEntities() {
        entities.clear(); presence = 0.0
        val df = DIFFS[Settings.diff]; val ld = levelDef(level); val cast = ld.cast
        val n = if (cast.isEmpty()) ld.eCount else cast.size // entity-free levels stay entity-free
        var group: Entity? = null // Facelings outside Level 11 come in groups
        repeat(if (n == 0) 0 else max(1, (n * df.count).roundToInt())) { i ->
            val type = if (cast.isEmpty()) ld.eType else cast[i % cast.size]
            if (level == LV_CITY && type == E_SMILER) { val s = spawnInBuilding(); if (s != null) { entities.add(s); return@repeat } }
            if (type == E_FACELING && level != LV_CITY && group != null) {
                val a2 = Math.random() * 6.283; val r2 = 1.5 + Math.random() * 1.5; val q2 = findFree(group!!.x + cos(a2) * r2, group!!.y + sin(a2) * r2)
                entities.add(Entity(q2.first, q2.second, type)); return@repeat
            }
            val ang = Math.random() * 6.283; val d = (11 + Math.random() * 8) * df.spawn
            val q = findFree(x + cos(ang) * d, y + sin(ang) * d)
            val e = Entity(q.first, q.second, type); entities.add(e)
            if (type == E_FACELING) group = e
        }
    }

    /** Level 11: a Smiler waits in a dark building (hollow, no lamps, never a stocked one: those are for the Hounds' guests). */
    private fun spawnInBuilding(): Entity? {
        for (i in 0 until 300) {
            val cx = floor(x + (Math.random() - 0.5) * 90).toInt(); val cy = floor(y + (Math.random() - 0.5) * 90).toInt()
            val f = City.hollowAt(cx, cy) ?: continue
            if (!City.dark(cx shr 4, cy shr 4) || hypot(cx + 0.5 - x, cy + 0.5 - y) < 14) continue
            return Entity(cx + 0.5, cy + 0.5, 2).also { it.home = f }
        }
        return null
    }

    // shortest route over open cells (4-neighbour BFS, 97x97 window around the start): the next cell to walk to, or null
    private val bfsPrev = IntArray(97 * 97); private val bfsQ = IntArray(97 * 97)
    private val bdx = intArrayOf(1, -1, 0, 0); private val bdy = intArrayOf(0, 0, 1, -1)
    private fun route(sx: Int, sy: Int, gx: Int, gy: Int): IntArray? {
        val r = 48; val sz = 97
        if (abs(gx - sx) > r || abs(gy - sy) > r) return null
        java.util.Arrays.fill(bfsPrev, -2)
        val ox = sx - r; val oy = sy - r; val start = r * sz + r; val goal = (gy - oy) * sz + (gx - ox)
        var qh = 0; var qt = 0
        bfsPrev[start] = -1; bfsQ[qt++] = start
        while (qh < qt) {
            val c = bfsQ[qh++]; if (c == goal) break
            val cx = c % sz; val cy = c / sz
            for (k in 0..3) {
                val nx = cx + bdx[k]; val ny = cy + bdy[k]
                if (nx < 0 || ny < 0 || nx >= sz || ny >= sz) continue
                val ni = ny * sz + nx
                if (bfsPrev[ni] != -2 || isWall(level, nx + ox, ny + oy)) continue
                bfsPrev[ni] = c; bfsQ[qt++] = ni
            }
        }
        if (bfsPrev[goal] == -2) return null
        var c = goal
        while (bfsPrev[c] != start && bfsPrev[c] != -1) c = bfsPrev[c]
        return intArrayOf(c % sz + ox, c / sz + oy)
    }

    /** Level 11 Effect, for Hounds and Facelings that have not been provoked. Returns true when it handled the entity. */
    private fun calmBehaviour(e: Entity, d: Double, ex: Double, ey: Double, dt: Double): Boolean {
        var speed = 0.0
        if (e.type == 4) { // Faceling: walks up to you and tags along; after a while it loses interest
            if (e.followT < CityRules.FACELING_FOLLOW_SECONDS && d < 18) {
                e.followT += dt; e.dir = atan2(ey, ex)
                if (d > 2.6) speed = if (d > 6) 2.6 else 2.0
            } else {
                e.dir += (Math.random() - 0.5) * dt * 2; speed = 0.6
                if (e.followT >= CityRules.FACELING_FOLLOW_SECONDS && d < 6) { e.dir = atan2(-ey, -ex); speed = 1.0 }
            }
        } else { // Hound: leads you to the nearest stocked building, waits when you fall behind
            if (d < CityRules.HOUND_GUIDE_RANGE) {
                e.goalT -= dt
                if (e.goalT <= 0) { e.goalT = 3.0; val t = City.stockedNear(e.x, e.y); e.hasGoal = t != null; if (t != null) { e.gx = t[0]; e.gy = t[1] } }
                if (!e.hasGoal) { e.dir += (Math.random() - 0.5) * dt * 3; speed = 0.8 }
                else if (hypot(e.gx + 0.5 - e.x, e.gy + 0.5 - e.y) < 2.5 || d > CityRules.HOUND_WAIT_DISTANCE) e.dir = atan2(ey, ex) // arrived, or waiting for you
                else {
                    e.routeT -= dt
                    if (e.routeT <= 0 || !e.hasWp) { e.routeT = 0.8; val w = route(floor(e.x).toInt(), floor(e.y).toInt(), e.gx, e.gy); e.hasWp = w != null; if (w != null) { e.wx = w[0]; e.wy = w[1] } }
                    if (e.hasWp) { e.dir = atan2(e.wy + 0.5 - e.y, e.wx + 0.5 - e.x); speed = 1.9 }
                }
            } else { e.dir += (Math.random() - 0.5) * dt * 3; speed = 0.8 }
        }
        if (speed > 0) {
            val r = move(level, e.x, e.y, cos(e.dir), sin(e.dir), speed * dt)
            if (r.first == e.x && r.second == e.y) e.dir += 1.6
            e.x = r.first; e.y = r.second
        }
        return true
    }

    fun newGame(lv: Int = 0) { hardcoreRun = Settings.hardcore; playSec = 0.0; hunger = 1.0; rations = DIFFS[Settings.diff].kitRations; water = DIFFS[Settings.diff].kitWater; starveT = 0.0; enter(lv) }

    fun snapshot(id: String) = SaveData(id, System.currentTimeMillis(), level, x, y, a, pitch, stamina, playSec, hunger, rations, water, lobbyT, Settings.diff)

    /** Resume a save: same level and spot; entities start fresh, 11+ cells away. */
    fun restore(s: SaveData) {
        level = if (levelExists(s.level)) s.level else 0; levelT = 0.0; dead = false; deadT = 0.0; danger = 0.0; noclip = 0.0
        arrival = "YOU CAME BACK"; stamina = s.stamina.coerceIn(0.0, 1.0); exhausted = false
        a = s.a; pitch = s.pitch.coerceIn(-1.0, 1.0); playSec = s.playSec
        Settings.putDiff(s.diff); hardcoreRun = Settings.hardcore // a save carries its difficulty
        resetLevelState(); hunger = s.hunger.coerceIn(0.0, 1.0); rations = s.rations.coerceIn(0, 9); water = s.water.coerceIn(0, 9); lobbyT = s.lobbyT.coerceAtLeast(0.0); starveT = 0.0
        remember(level)
        val p = findFree(s.x, s.y); x = p.first; y = p.second
        spawnEntities()
    }

    /** fwd/str in -1..1 (analog), turn in rad/s */
    fun update(dt: Double, fwd: Double, str: Double, turn: Double, sprint: Boolean) {
        levelT += dt; playSec += dt; flash = max(0.0, flash - dt / 1.2); noticeT = max(0.0, noticeT - dt)
        grace = max(0.0, grace - dt)
        if (dead) { deadT += dt; if (deadT > 3.5) afterDeath(); return }
        val dif = DIFFS[Settings.diff]
        if (eatReq) { eatReq = false; if (rations > 0) { rations--; hunger = min(1.0, hunger + 0.35); say("YOU ATE A RATION") } }
        if (drinkReq) { drinkReq = false; if (water > 0) { water--; hunger = min(1.0, hunger + 0.08); lobbyT = 0.0; halluc = 0.0; ghosts.clear(); say("ALMOND WATER  ·  your head clears") } }
        a += turn * dt
        pitch = pitch.coerceIn(-1.0, 1.0)
        var f = fwd; var s = str
        val len = hypot(f, s)
        if (len > 1) { f /= len; s /= len }
        val moving = len > 0.1
        val run = sprint && moving && !exhausted
        // food: full to empty in about 18 minutes of walking; hungry = slower recovery, starving = slower and, eventually, fatal. Explore has no hunger (stamina still applies).
        if (dif.explore || level == LV_GRAVE) hunger = 1.0 else hunger = max(0.0, hunger - (0.0009 + (if (run) 0.0012 else 0.0)) * dif.hunger * dt)
        starveT = if (hunger <= 0.0) starveT + dt else 0.0
        if (starveT > 75) { dead = true; deadT = 0.0; deathMsg = "YOU STARVED"; return }
        if (hunger < 0.2 && !warnHungry) { warnHungry = true; say("YOU ARE HUNGRY  ·  find food") } else if (hunger > 0.4) warnHungry = false
        if (run) { stamina -= 0.10 * dif.drain * dt; if (stamina <= 0) { stamina = 0.0; exhausted = true } }
        else { stamina = min(1.0, stamina + 0.20 * dif.regen * (if (hunger < 0.2) 0.6 else 1.0) * dt); if (exhausted && stamina > 0.3) exhausted = false }
        val speed = (if (run) 4.2 else 2.3) * (if (hunger <= 0.0) 0.85 else 1.0)
        move(level, x, y, cos(a) * f - sin(a) * s, sin(a) * f + cos(a) * s, speed * dt).let { x = it.first; y = it.second }
        if (moving) bob += dt * (if (run) 12.0 else 7.5)
        bobAmp += ((if (!moving) 0.0 else if (run) 1.0 else 0.6) - bobAmp) * min(1.0, dt * 8)

        // noclip: keep pushing into an unstable wall to slip through to the next level
        var pushing = false
        if (moving) {
            val mxn = cos(a) * f - sin(a) * s; val myn = sin(a) * f + cos(a) * s; val ml = hypot(mxn, myn)
            if (ml > 0.01) {
                val cx = floor(x + mxn / ml * 0.34).toInt(); val cy = floor(y + myn / ml * 0.34).toInt()
                val k = noclipIndex(level, cx, cy)
                pushing = k >= 0
                if (pushing) ncK = k
            }
        }
        noclip = if (pushing) min(1.0, noclip + dt / 2.2) else max(0.0, noclip - dt * 1.2)
        if (noclip >= 1.0) { enter(noclipTarget(), "YOU NOCLIPPED THROUGH THE WALL"); flash = 1.0; return }

        near = (NOCLIPS[level] ?: emptyList()).minOfOrNull { hypot(it[0] + 0.5 - x, it[1] + 0.5 - y) }.let { if (it == null) 0.0 else (1 - it / 22).coerceIn(0.0, 1.0) }
        val inM = level == 0 && Manila.inside(x, y)
        manilaNear = if (level == 0 && !inM) (1 - hypot(Manila.CX + 4.5 - x, Manila.CY + 4.5 - y) / 45).coerceIn(0.0, 1.0) else 0.0
        manilaT = if (inM) manilaT + dt else 0.0

        // The Lobby gets to you: stay in its open halls too long and you start seeing things. Almond water or the Manila Room clears your head.
        if (dif.explore) lobbyT = 0.0 else if (level == 0) lobbyT = if (inM) max(0.0, lobbyT - dt * 2) else lobbyT + dt
        halluc += (((lobbyT - 240) / 300).coerceIn(0.0, 1.0) - halluc) * min(1.0, dt * 0.5)
        if (halluc > 0.08 && !warnHallu) { warnHallu = true; say("YOUR EYES PLAY TRICKS  ·  almond water or the Manila Room helps") } else if (halluc < 0.02) warnHallu = false
        updateGhosts(dt)
        updateRooms(dt)
        if (level == LV_GRAVE && !warnGrave && levelT > 9.0) { warnGrave = true; say("RESTS HERE THE CHAMPION  ·  HIS WORK IS DONE  ·  YOU ARE SAVED") }
        if (level == LV_WARRIOR) updateTrial(dt)

        var dg = 0.0; var pr = 0.0
        val cityFx = levelDef(level).calm < 1.0 // Level 11 Effect
        for (e in entities) {
            val ex = x - e.x; val ey = y - e.y; val d = hypot(ex, ey).coerceAtLeast(0.01)
            if (dif.explore && isMonster(e.type)) { // Explore: every monster is friendly. It wanders, and tags along at a polite distance
                val follow = d < 12 && d > 3.5
                if (follow) e.dir = atan2(ey, ex) + sin(levelT * 0.7 + e.x) * 0.5 else e.dir += (Math.random() - 0.5) * dt * 2
                val r = move(level, e.x, e.y, cos(e.dir), sin(e.dir), (if (follow) 1.4 else 0.6) * dt)
                if (r.first == e.x && r.second == e.y) e.dir += 1.6
                e.x = r.first; e.y = r.second; e.chase = false; e.passive = true
                continue
            }
            // Level 11 Effect: Hounds and Facelings stay calm. Smilers are not affected.
            // Provocation is switched off for now (they never turn hostile). To bring it back: add "&& !e.provoked" to the condition,
            // uncomment the three lines, and wrap the last line in "else { ... }" so a provoked entity falls through to the hostile code below.
            if (cityFx && (e.type == 1 || e.type == 4)) {
                // val crowd = d < 1.4 || (e.type == 1 && run && d < 6)   // crowding it, or sprinting right past a Hound
                // e.irr = if (crowd) e.irr + dt else max(0.0, e.irr - dt * 0.3)
                // if (e.irr > CityRules.PROVOKE_SECONDS) { e.provoked = true; say("IT DID NOT LIKE THAT") }
                e.passive = true; calmBehaviour(e, d, ex, ey, dt); continue
            }
            e.passive = false
            val smilerHome = e.home
            val awake = e.type != 2 || smilerHome == null || (floor(x).toInt() in smilerHome[0]..smilerHome[2] && floor(y).toInt() in smilerHome[1]..smilerHome[3]) // a building's Smiler sleeps until you come in
            if (awake && !isHarmless(e.type)) pr = max(pr, 1 - d / 12)
            val sight = dif.sight
            if (e.type == 3) { // Skin-Stealer: walks like a player, patiently closes in
                if (d < 16 * sight && los(level, e.x, e.y, x, y)) e.chase = true else if (d > 22 * sight) e.chase = false
                if (e.chase) e.dir = atan2(ey, ex) else e.dir += (Math.random() - 0.5) * dt * 2
                val r = move(level, e.x, e.y, cos(e.dir), sin(e.dir), (if (e.chase) 2.0 else 0.8) * dif.speed * dt)
                if (r.first == e.x && r.second == e.y) e.dir += 1.6
                e.x = r.first; e.y = r.second
                if (e.chase) dg = max(dg, (1 - d / 6).coerceAtLeast(0.0))
            } else if (e.type == 1) { // Hound: wanders, hunts you on sight, collides with walls
                if (d < 10 * sight && los(level, e.x, e.y, x, y)) e.chase = true else if (d > 16 * sight) e.chase = false
                if (e.chase) e.dir = atan2(ey, ex) else e.dir += (Math.random() - 0.5) * dt * 3
                val r = move(level, e.x, e.y, cos(e.dir), sin(e.dir), (if (e.chase) 3.4 else 1.0) * dif.speed * dt)
                if (!e.chase && r.first == e.x && r.second == e.y) e.dir += 1.6
                e.x = r.first; e.y = r.second
                if (e.chase) dg = max(dg, 1 - d / (10 * sight))
            } else if (e.type == 4) { // Faceling: drifts around, ignores you until you crowd it (a provoked one hunts you)
                if (d < (if (e.provoked) 12.0 else 3.5) && los(level, e.x, e.y, x, y)) e.chase = true else if (d > 14) e.chase = false
                if (e.chase) e.dir = atan2(ey, ex) else e.dir += (Math.random() - 0.5) * dt * 2
                val r = move(level, e.x, e.y, cos(e.dir), sin(e.dir), (if (e.chase) 2.6 else 0.7) * dif.speed * dt)
                if (!e.chase && r.first == e.x && r.second == e.y) e.dir += 1.6
                e.x = r.first; e.y = r.second
                if (e.chase) dg = max(dg, (1 - d / 8).coerceAtLeast(0.0))
            } else if (e.type == E_DEATHMOTH) { // Deathmoth: flutters about, usually gentle
                e.dir += (Math.random() - 0.5) * dt * 7
                val r = move(level, e.x, e.y, cos(e.dir), sin(e.dir), 1.3 * dt)
                if (r.first == e.x && r.second == e.y) e.dir += 2.0
                e.x = r.first; e.y = r.second
            } else if (e.type == E_CLUMP) { // Clump: a lumbering mass that hunts whatever it senses; very dangerous
                if (d < 14 * sight && los(level, e.x, e.y, x, y)) e.chase = true else if (d > 22 * sight) e.chase = false
                if (e.chase) e.dir = atan2(ey, ex) else e.dir += (Math.random() - 0.5) * dt * 2
                val r = move(level, e.x, e.y, cos(e.dir), sin(e.dir), (if (e.chase) 3.0 else 0.9) * dif.speed * dt)
                if (!e.chase && r.first == e.x && r.second == e.y) e.dir += 1.6
                e.x = r.first; e.y = r.second
                if (e.chase) dg = max(dg, 1 - d / 14)
            } else if (e.type == E_TARANTULA) { // Tarantula: lurks in the cave and hunts whatever comes in
                if (d < 9 * sight && los(level, e.x, e.y, x, y)) e.chase = true else if (d > 14 * sight) e.chase = false
                if (e.chase) e.dir = atan2(ey, ex) else e.dir += (Math.random() - 0.5) * dt * 3
                val r = move(level, e.x, e.y, cos(e.dir), sin(e.dir), (if (e.chase) 2.7 else 0.8) * dif.speed * dt)
                if (!e.chase && r.first == e.x && r.second == e.y) e.dir += 1.6
                e.x = r.first; e.y = r.second
                if (e.chase) dg = max(dg, 1 - d / 9)
            } else if (e.type == E_WARRIOR) { // The Warrior stands at the foot of the volcano and watches
                if (d < 9 && !warnTrial) { warnTrial = true; say("THE WARRIOR  ·  FACE YOUR FEAR") }
            } else if (e.type == E_JERRY) { // Jerry sits in his room and watches; the cult around him chants
                if (d < 7 && !warnJerry) { warnJerry = true; say("JERRY AND HIS CULT") }
            } else if (e.type == E_CULTIST) {
            } else if (awake) { // Smiler: only moves while you are NOT looking at it, drifts through walls (a building's Smiler stays inside it)
                var df = (atan2(e.y - y, e.x - x) - a) % (2 * PI)
                if (df > PI) df -= 2 * PI; if (df < -PI) df += 2 * PI
                val seen = abs(df) < 0.8 && los(level, x, y, e.x, e.y)
                if (!seen) { e.x += ex / d * 1.9 * dif.speed * dt; e.y += ey / d * 1.9 * dif.speed * dt } else dg = max(dg, 1 - d / 14)
                if (smilerHome != null) { e.x = e.x.coerceIn(smilerHome[0] + 1.0, smilerHome[2].toDouble()); e.y = e.y.coerceIn(smilerHome[1] + 1.0, smilerHome[3].toDouble()) }
            }
            if (awake && grace <= 0.0 && d < dif.catchR && isLethal(e)) { dead = true; deadT = 0.0; deathMsg = "YOU WERE CAUGHT" }
        }
        danger = dg.coerceIn(0.0, 1.0)
        presence += (pr - presence) * min(1.0, dt * 3)

        // pick up supplies by walking over them (bag holds 9 of each)
        val cx0 = floor(x).toInt(); val cy0 = floor(y).toInt()
        if (cx0 != scanX || cy0 != scanY) rescan(cx0, cy0)
        for (sp in supplies) if (!dif.explore && hypot(sp.x + 0.5 - x, sp.y + 0.5 - y) < 0.75 && (if (sp.type == 1) rations else water) < 9) {
            taken.add(cellKey(sp.x, sp.y)); if (sp.type == 1) rations++ else water++
            say(if (sp.type == 1) "+1 RATION" else "+1 ALMOND WATER"); scanX = Int.MIN_VALUE
        }
    }

    /** After the death animation: the trial pulls you out of danger on Class 3+ levels (sometimes), or you simply start over. */
    private fun afterDeath() {
        if (hardcoreRun && level != LV_GRAVE) { origin = null; enter(LV_GRAVE, "YOU DIED"); return } // one life: the Grave has no exit
        if (level == LV_WARRIOR) { returnFromTrial("YOU FAILED THE TRIAL"); return }
        if (!Settings.explore && levelDef(level).danger >= 3 && Math.random() < 0.4) { enterTrial(); return }
        hunger = max(hunger, 0.5); starveT = 0.0; enter(level)
    }
    private fun enterTrial() {
        origin = Origin(level, x, y, a, pitch, entities.map { it.copy() })
        enter(LV_WARRIOR, "THE WARRIOR TAKES YOU OUT OF DANGER")
        say("A TRIAL AWAITS  ·  CLIMB THE VOLCANO, ENTER THE LAKE")
    }
    /** Refusing or failing the trial sends you back to the danger you came from (Level 0 if you never came from one). */
    private fun returnFromTrial(msg: String) {
        val o = origin; origin = null
        if (o == null) { enter(LV_THRESHOLD, msg); return }
        enter(o.level, msg)
        x = o.x; y = o.y; a = o.a; pitch = o.pitch
        entities.clear(); entities.addAll(o.ents.map { it.copy() })
        hunger = max(hunger, 0.5); starveT = 0.0; grace = 2.5
        say("BACK WHERE YOU FELL  ·  RUN")
    }
    /** The lake in the crater: a random safe level, usually Level 1 or Level 11. */
    private fun completeTrial() {
        origin = null
        val safe = LEVELS.filter { it.safe && !it.unlisted && it.id != LV_WARRIOR }.map { it.id }
        val r = Math.random()
        val dest = if (r < 0.45) 1 else if (r < 0.90) LV_CITY else safe[(Math.random() * safe.size).toInt()]
        enter(dest, "YOU PASSED THE TRIAL")
    }
    private fun spawnTrial() {
        entities.add(Entity(2.5, 22.5, E_WARRIOR))
        val n = max(3, (5 * DIFFS[Settings.diff].count).roundToInt())
        for (k in 0 until n) { val p = Island.tunnelPoint(4.0 + (Island.TUNNEL_LENGTH - 6) * (k + 0.5) / n); entities.add(Entity(p[0], p[1], E_TARANTULA)) }
    }
    private fun updateTrial(dt: Double) {
        val cx = floor(x).toInt(); val cy = floor(y).toInt(); val r = Island.rad(cx, cy)
        if (r > Island.SEA) { returnFromTrial("YOU TURNED YOUR BACK ON THE TRIAL"); return }
        if (r < Island.LAKE) { completeTrial(); return }
        if (!Island.tunnel(cx, cy)) return
        trialT += dt
        if (trialT > 14 && (trialT - 14) % 16 < dt && entities.count { it.type == E_TARANTULA } < 8) { // the Warrior reworks the cave: another spider ahead of you
            if (!warnReworked) { warnReworked = true; say("THE WARRIOR REWORKS THE CAVE AROUND YOU") }
            for (i in 0 until 20) { val p = Island.tunnelPoint(Math.random() * Island.TUNNEL_LENGTH)
                if (hypot(p[0] - x, p[1] - y) in 7.0..13.0) { entities.add(Entity(p[0], p[1], E_TARANTULA)); break } }
        }
    }

    /** Level 2: when a cult room comes within 40 cells, Jerry and six cultists appear in it (once per visit). */
    private fun updateRooms(dt: Double) {
        if (level != LV_UTILITY) return
        roomT -= dt; if (roomT > 0) return; roomT = 1.0
        val sx0 = Math.floorDiv(floor(x).toInt(), JERRY_SECTOR); val sy0 = Math.floorDiv(floor(y).toInt(), JERRY_SECTOR)
        for (sy in sy0 - 1..sy0 + 1) for (sx in sx0 - 1..sx0 + 1) {
            val c = Level02AbandonedUtilityHalls.jerryRoom(sx, sy) ?: continue
            if (cellKey(sx, sy) in roomsDone || hypot(c[0] + 0.5 - x, c[1] + 0.5 - y) > 40) continue
            if (entities.any { it.type == E_JERRY && hypot(it.x - c[0] - 0.5, it.y - c[1] - 0.5) < 3 }) { roomsDone.add(cellKey(sx, sy)); continue }
            roomsDone.add(cellKey(sx, sy))
            entities.add(Entity(c[0] + 0.5, c[1] + 0.5, E_JERRY))
            for (k in 0 until 6) { val a = k * PI / 3; entities.add(Entity(c[0] + 0.5 + 3 * cos(a), c[1] + 0.5 + 3 * sin(a), E_CULTIST)) }
        }
    }

    private fun updateGhosts(dt: Double) {
        for (gh in ghosts) {
            val d = hypot(x - gh.x, y - gh.y).coerceAtLeast(0.01)
            gh.life -= dt
            if (gh.life <= 0 || d < 2.5) { ghosts.remove(gh); if (d < 2.5) flash = max(flash, 0.3); continue } // it was never there
            gh.x += (x - gh.x) / d * 0.9 * dt; gh.y += (y - gh.y) / d * 0.9 * dt
        }
        if (halluc > 0.1 && ghosts.size < 1 + (halluc * 3).toInt()) {
            ghostCd -= dt
            if (ghostCd <= 0) {
                ghostCd = 9 - 7 * halluc + Math.random() * 3
                val ang = a + (Math.random() - 0.5) * 1.4; val dd = 6 + Math.random() * 8
                val q = findFree(x + cos(ang) * dd, y + sin(ang) * dd)
                ghosts.add(Ghost(q.first, q.second, intArrayOf(0, 2, 3)[(Math.random() * 3).toInt()], 4 + Math.random() * 4))
            }
        }
    }
}

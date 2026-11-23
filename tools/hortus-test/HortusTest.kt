// HortusTest.kt - checks Level 414's mechanics: the gates and vines in other levels, going in, wishing to leave, the path out,
// the Ivlania, and saving. Build and run (from the repo root):
//   kotlinc app/src/main/java/core/*.kt app/src/main/java/core/levels/*.kt tools/hortus-test/HortusTest.kt -include-runtime -d hortus-test.jar && java -jar hortus-test.jar
import com.backrooms.game.*
import java.io.File
import kotlin.math.*

var fails = 0
fun check(ok: Boolean, what: String) { println("  %-82s %s".format(what, if (ok) "PASS" else "FAIL")); if (!ok) fails++ }
private fun tick(g: Game, seconds: Double, fwd: Double = 0.0, dt: Double = 0.1) { repeat((seconds / dt).toInt()) { g.update(dt, fwd, 0.0, 0.0, false) } }

fun main() {
    println("1. the way in: gates and glowing vines")
    val perLevel = HashMap<Int, Int>(); var badCells = 0; var shortTrails = 0; var gates = 0; var minTrail = 99
    for (seed in 1..6) { useWorld(seed * 7919)
        for (lv in listOf(0, 1, 2, 3, 4, 5, 37, 75, 182, 201)) {
            val all = Garden.near(lv, 0, 0, 200)
            val gs = all.filter { it.type == Garden.T_GATE }; val vs = all.filter { it.type == Garden.T_VINE }
            gates += gs.size; perLevel[lv] = (perLevel[lv] ?: 0) + gs.size
            for (o in all) if (isWall(lv, o.x, o.y)) badCells++
            for (gt in gs) { val trail = Garden.near(lv, gt.x, gt.y, 45).count { it.type == Garden.T_VINE }; minTrail = min(minTrail, trail); if (trail < 5) shortTrails++ } // the whole trail, wherever the gate stands
        } }
    check(gates > 20, "gates exist across worlds and levels ($gates in 6 worlds x 10 levels)")
    check(badCells == 0, "no gate or vine stands inside a wall")
    check(shortTrails == 0, "every gate has a trail of at least 5 vines (shortest: $minTrail)")
    check(Garden.near(6, 0, 0, 200).isEmpty() && Garden.near(LV_HORTUS, 0, 0, 200).isEmpty(), "no vines where there is no sun (Level 6) or in the garden itself")
    useWorld(31337); val a1 = Garden.near(2, 0, 0, 200).map { it.x * 100000 + it.y }; val a2 = Garden.near(2, 0, 0, 200).map { it.x * 100000 + it.y }
    useWorld(31338); val b1 = Garden.near(2, 0, 0, 200).map { it.x * 100000 + it.y }
    check(a1 == a2 && a1 != b1, "the same world always has the same gates, another world has others")

    println("2. walking into a gate leads to Level 414")
    var entered = 0; var tried = 0
    for (lv in listOf(0, 1, 2, 3, 4, 5, 37, 75, 182, 201)) { for (seed in 1..8) { useWorld(seed * 104729)
        val gate = Garden.near(lv, 0, 0, 200).firstOrNull { it.type == Garden.T_GATE } ?: continue
        val g = Game(); g.enter(lv); g.x = gate.x + 0.5; g.y = gate.y + 0.5; tried++
        g.update(0.016, 0.0, 0.0, 0.0, false)
        if (g.level == LV_HORTUS) entered++
        break } }
    check(tried >= 8 && entered == tried, "standing in the gate of each level takes you to Level 414 ($entered of $tried levels)")

    println("3. wishing to leave opens a path of flowers")
    useWorld(4242)
    val g = Game(); g.enter(LV_HORTUS); g.entities.clear()
    check(g.level == LV_HORTUS && Hortus.path == null, "arriving in the garden: no path yet")
    tick(g, 2.5, fwd = 1.0); tick(g, 2.0)   // walking about does not count as wishing
    check(Hortus.path == null, "walking about does not open a path")
    tick(g, 5.0)
    val p = Hortus.path
    check(p != null, "standing still for 4 seconds opens a path")
    if (p != null) {
        check(p.cells.size >= 12 && p.cells.all { !isWall(LV_HORTUS, it[0], it[1]) }, "the path is ${p.cells.size} steps over open ground")
        check(p.cells.zipWithNext().all { (a, b) -> abs(a[0] - b[0]) + abs(a[1] - b[1]) == 1 }, "the path is unbroken")
        check(Garden.EXITS.any { it.level == p.exit.level } && levelSpec(p.exit.level).def.id == p.exit.level, "it leads to a real level (${p.exit.level}, ${p.exit.flower})")
        val sp = ArrayList<DoubleArray>(); p.sprites(p.cells[p.cells.size / 2][0] + 0.5, p.cells[p.cells.size / 2][1] + 0.5, sp)
        check(sp.count { it[2].toInt() == S_FLOWER + p.index } >= 8, "flowers of the right kind line the path (${sp.count { it[2].toInt() == S_FLOWER + p.index }} nearby)")
        g.x = p.end[0] + 0.5; g.y = p.end[1] + 0.5; g.update(0.016, 0.0, 0.0, 0.0, false)
        check(g.level == p.exit.level && Hortus.path == null, "reaching its end takes you to level ${g.level}")
    }
    // wandering far from the path lets the wish fade
    val g2 = Game(); g2.enter(LV_HORTUS); g2.entities.clear(); tick(g2, 5.0); val p2 = Hortus.path
    g2.x += 200.0; g2.update(0.016, 0.0, 0.0, 0.0, false)
    check(p2 != null && Hortus.path == null, "wandering far away lets the path fade")
    // every destination exists and has its own flower
    check(Garden.EXITS.map { it.flower }.toSet().size == Garden.EXITS.size && Garden.EXITS.size == 5 && S_FLOWER + Garden.EXITS.size - 1 < S_CUBE, "5 destinations, 5 different flowers, sprite codes do not collide")
    check(Garden.EXITS.all { levelSpec(it.level).def.id == it.level && levelSpec(it.level).def.safe && it.level != LV_HORTUS } && Garden.EXITS.first { it.flower == "DANDELIONS" }.level == 11, "every path leads to a safe level; Dandelions lead to Level 11, as on the level's page")

    println("4. the Ivlania")
    useWorld(777); val g3 = Game(); g3.enter(LV_HORTUS)
    val iv = g3.entities.filter { it.type == E_IVLANIA }
    check(iv.size >= 6 && iv.size == g3.entities.size, "${iv.size} Ivlania live here and nothing else")
    check(iv.all { free(LV_HORTUS, it.x, it.y) } && iv.all { it.home != null }, "they stand on open ground and know their community")
    val near = iv[0]; g3.x = near.x + 1.0; g3.y = near.y; tick(g3, 60.0)
    check(!g3.dead, "standing among them for a minute: nothing attacks you")
    check(g3.entities.all { free(LV_HORTUS, it.x, it.y) && hypot(it.x - it.home!![0], it.y - it.home!![1]) < 14 }, "after a minute they are still on open ground, near home")
    check(isHarmless(E_IVLANIA) && !isMonster(E_IVLANIA), "the game counts them as harmless")

    println("5. the garden in saves")
    val dir = File.createTempFile("hort", "").let { it.delete(); it.mkdirs(); it }; Store.init(dir)
    useWorld(555); val g4 = Game(); g4.enter(LV_HORTUS); Store.write(g4.snapshot("h1")); useWorld(1)
    val s = Store.list().first { it.id == "h1" }; val g5 = Game(); g5.restore(s)
    check(g5.level == LV_HORTUS && worldSeed == 555, "a save made in the garden loads back into it, in the same world")
    check(LEVELS.any { it.id == LV_HORTUS && !it.unlisted }, "Level 414 is in the level list")

    println("\n" + if (fails == 0) "ALL PASSED" else "$fails FAILED")
    System.exit(if (fails == 0) 0 else 1)
}

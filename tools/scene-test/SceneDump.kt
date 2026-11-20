// SceneDump.kt - for every case: the CPU frame (the reference), and the scene data the GPU shader draws it from.
import com.backrooms.game.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

private fun le(n: Int) = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)

fun main(args: Array<String>) {
    val dir = File(args[0]); dir.mkdirs()
    var n = 0; val skipped = ArrayList<String>()
    for (mkCase in allCases()) {
        val c = mkCase()
        // two Renderers in the same starting state: one draws the reference frame, the other builds the scene
        val ref = Renderer(FRAME_W, FRAME_H, 1); val gpu = Renderer(FRAME_W, FRAME_H, 1)
        val px = IntArray(ref.W * ref.H); ref.render(px, c.g, c.t, c.others, c.hud)
        val sd = SceneData()
        if (!gpu.buildScene(c.g, c.t, c.others, c.hud, sd)) { skipped.add(c.name); continue }
        val a = le(px.size * 4); a.asIntBuffer().put(px); File(dir, "${c.name}.cpu.raw").writeBytes(a.array())
        val f = le(sd.f.size * 4); f.asFloatBuffer().put(sd.f); File(dir, "${c.name}.scene.f32").writeBytes(f.array())
        val t = le(sd.tex.size * 4); t.asIntBuffer().put(sd.tex); File(dir, "${c.name}.tex.u32").writeBytes(t.array())
        n++
    }
    File(dir, "meta.txt").writeText("$FRAME_W $FRAME_H\n")
    println("wrote $n cases to $dir" + if (skipped.isNotEmpty()) " (open-world, skipped: $skipped)" else "")
}

// CpuDump.kt - renders every case with the CPU renderer only (works on a Renderer without buildScene).
import com.backrooms.game.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

fun main(args: Array<String>) {
    val dir = File(args[0]); dir.mkdirs()
    for (mkCase in allCases()) {
        val c = mkCase()
        val r = Renderer(FRAME_W, FRAME_H, 1); val px = IntArray(r.W * r.H)
        r.render(px, c.g, c.t, c.others, c.hud)
        val bb = ByteBuffer.allocate(px.size * 4).order(ByteOrder.LITTLE_ENDIAN); bb.asIntBuffer().put(px)
        File(dir, "${c.name}.cpu.raw").writeBytes(bb.array())
    }
    println("wrote CPU frames to $dir")
}

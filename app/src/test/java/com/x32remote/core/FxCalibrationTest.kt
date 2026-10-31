package com.x32remote.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 效果库标定表（assets/fx_calibration.json）的解码与语义。
 *
 * 数据由 X32Simulator/tools/export_fx_calibration.py 从 fxlib.py 生成，禁止手改。
 * 这里直读 app/src/main/assets 下的源文件（而不是复制一份到 test resources）——
 * 有副本就会有漂移，而"三端标定不一致"正是本项目踩过的坑。
 *
 * 与 iOS FxCalibrationTests 逐条同语义。
 */
class FxCalibrationTest {

    private val lib: FxLib by lazy {
        // Gradle JVM 单测的 workingDir 是 app/
        val f = File("src/main/assets/fx_calibration.json")
        assertTrue("标定表不在预期位置：${f.absolutePath}", f.isFile)
        FxLib.parse(f.readText(Charsets.UTF_8))
    }

    // ---------------------------------------------------------- 库规模

    @Test
    fun `library has full 61 algos and 886 params`() {
        assertEquals(61, lib.algos.size)
        assertEquals(886, lib.algos.values.sumOf { it.n })
        assertEquals(9, lib.catLabel.size)
        assertEquals(34, lib.enum58.size)          // FX 5-8 可装的轻量子集
    }

    @Test
    fun `param count matches spec list length for every algo`() {
        for ((name, a) in lib.algos) {
            assertEquals("$name 的 n 与参数表长度不符", a.n, a.params.size)
        }
    }

    @Test
    fun `par numbers are contiguous starting at 1`() {
        for ((name, a) in lib.algos) {
            a.params.forEachIndexed { i, p ->
                assertEquals("$name 第 $i 项 par 号应连续", i + 1, p.p)
            }
        }
    }

    @Test
    fun `old type names are aliased to current ones`() {
        assertEquals("PLAT", lib.alias["PLATE"])
        assertEquals("CHAM", lib.alias["CHAMBER"])
        assertEquals("VREV", lib.alias["VINTAGE"])
    }

    // ---------------------------------------------------------- 关键映射
    // 旧标定表把 HALL 的 Pre Delay 与 Decay 搞反了: 真台 par01 是预延迟(ms),
    // par02 才是混响时间(s)。这两条是踩过坑的实证, 不许再翻回去。

    @Test
    fun `hall pre delay and decay are not swapped`() {
        val hall = lib.algo("HALL") ?: error("缺 HALL")
        assertEquals("大厅混响", hall.label)
        assertEquals(12, hall.n)
        assertEquals("reverb", hall.cat)

        val p1 = lib.paramSpec("HALL", "pre_delay") ?: error("缺 pre_delay")
        assertEquals(1, p1.p)
        assertEquals("预延迟", p1.label)
        assertEquals("ms", p1.unit)

        val p2 = lib.paramSpec("HALL", "decay") ?: error("缺 decay")
        assertEquals(2, p2.p)
        assertEquals("混响时间", p2.label)
        assertEquals("s", p2.unit)
    }

    @Test
    fun `semantic aliases point at the right par numbers`() {
        val hall = lib.algo("HALL") ?: error("缺 HALL")
        assertEquals(2, hall.time)        // 混响时间 → par02
        assertEquals(1, hall.predelay)    // 预延迟   → par01
        assertEquals(4, hall.damp)        // 阻尼     → par04
        assertEquals("s", hall.tunit)
    }

    @Test
    fun `geq is a 31 band eq`() {
        val geq = lib.algo("GEQ") ?: error("缺 GEQ")
        assertEquals("立体声图示 EQ", geq.label)
        assertEquals("eq", geq.cat)
        assertEquals(32, geq.n)
    }

    // ---------------------------------------------------------- 槽位约束

    @Test
    fun `heavy algos only fit slots 1 to 4`() {
        assertEquals(27, lib.algos.values.count { it.heavy })
        assertTrue("HALL 是 heavy", lib.algo("HALL")?.heavy == true)

        assertFalse(lib.slotAllows(5, "HALL"))
        assertFalse(lib.slotAllows(8, "HALL"))
        assertTrue(lib.slotAllows(1, "HALL"))
        assertTrue(lib.slotAllows(4, "HALL"))
    }

    @Test
    fun `light algos fit every slot`() {
        for (name in lib.enum58) {
            val a = lib.algo(name) ?: error("enum58 里的 $name 不在库里")
            assertFalse("$name 是轻量子集却标了 heavy", a.heavy)
            for (slot in 1..8) {
                assertTrue("$name 应能装进 FX$slot", lib.slotAllows(slot, name))
            }
        }
    }

    @Test
    fun `unknown algo never fits`() {
        assertFalse(lib.slotAllows(1, "NO_SUCH_ALGO"))
        assertNull(lib.algo("NO_SUCH_ALGO"))
        assertNull(lib.paramSpec("NO_SUCH_ALGO", "decay"))
    }

    // ---------------------------------------------------------- field → par

    @Test
    fun `fieldToPar resolves all three paths`() {
        assertEquals(2, lib.fieldToPar("HALL", "decay"))       // 语义 field
        assertEquals(7, lib.fieldToPar("HALL", "par07"))       // parNN 直址
        assertEquals(2, lib.fieldToPar("HALL", "time"))        // 语义别名
        assertEquals(1, lib.fieldToPar("HALL", "predelay"))
        assertEquals(4, lib.fieldToPar("HALL", "damp"))
    }

    @Test
    fun `fieldToPar rejects nonsense`() {
        assertNull(lib.fieldToPar("HALL", "no_such_field"))
        assertNull(lib.fieldToPar("HALL", "par99"))            // 越界 par 不算数
        assertNull(lib.fieldToPar("NO_SUCH_ALGO", "decay"))
    }

    // ---------------------------------------------------------- 钳制
    // 契约: 非有限值(NaN/±inf)一律先落 0 再钳进量程 —— 与服务端 fx_par_clamp、
    // iOS FxParamSpec.clamp 同一个答案。三端不一致会让同一个脏值产生不同台面。

    @Test
    fun `clamp keeps in range values untouched`() {
        val decay = lib.paramSpec("HALL", "decay") ?: error("缺 decay")
        assertEquals(3.5, decay.clamp(3.5), 1e-9)
        assertEquals(5.0, decay.clamp(10.0), 1e-9)
        assertEquals(0.2, decay.clamp(-1.0), 1e-9)
    }

    @Test
    fun `clamp drops non finite values to zero then clamps`() {
        val decay = lib.paramSpec("HALL", "decay") ?: error("缺 decay")
        // decay 量程 0.2~5.0: 0 落在量程外 → 钳到 lo
        assertEquals(0.2, decay.clamp(Double.NaN), 1e-9)
        assertEquals(0.2, decay.clamp(Double.POSITIVE_INFINITY), 1e-9)
        assertEquals(0.2, decay.clamp(Double.NEGATIVE_INFINITY), 1e-9)
    }

    @Test
    fun `enum params clamp to integer option index`() {
        var checked = 0
        for ((name, a) in lib.algos) {
            for (p in a.params) {
                if (!p.isEnum) continue
                val opts = p.opts ?: error("$name/${p.field} 是枚举却没选项")
                assertEquals("$name/${p.field}", 0.0, p.lo, 1e-9)
                assertEquals("$name/${p.field}", (opts.size - 1).toDouble(), p.hi, 1e-9)
                assertEquals("$name/${p.field}", 1.0, p.clamp(1.4), 1e-9)   // 四舍五入
                checked++
            }
        }
        assertTrue("库里应该有枚举参数", checked > 0)
    }

    // ---------------------------------------------------------- 显示

    @Test
    fun `numeric display carries unit`() {
        val decay = lib.paramSpec("HALL", "decay") ?: error("缺 decay")
        assertEquals("2.50 s", decay.display(2.5))
    }

    @Test
    fun `enum display uses chinese option name`() {
        val spec = lib.algos.values.asSequence()
            .flatMap { it.params.asSequence() }
            .firstOrNull { it.isEnum && (it.opts?.size ?: 0) >= 2 }
        assertNotNull("库里应该有带选项的枚举参数", spec)
        spec!!
        val first = spec.opts!![0]
        assertEquals(first, spec.display(0.0))
    }

    @Test
    fun `decimals shrink as span grows`() {
        // 大量程（频率/预延迟）不显示小数，小量程才给精度 —— 否则滑杆上的数字会抖
        val wide = lib.paramSpec("HALL", "pre_delay") ?: error("缺 pre_delay")  // 0~200 ms → span 200
        assertEquals(0, wide.decimals)

        val mid = lib.paramSpec("HALL", "size") ?: error("缺 size")             // 2~100 → span 98
        assertEquals(1, mid.decimals)

        val narrow = lib.paramSpec("HALL", "decay") ?: error("缺 decay")        // 0.2~5 → span 4.8
        assertEquals(2, narrow.decimals)
    }

    // ---------------------------------------------------------- 分组

    @Test
    fun `category grouping covers every algo exactly once`() {
        val grouped = lib.algosByCategory().flatMap { it.third }
        assertEquals(lib.algos.size, grouped.size)
        assertEquals(lib.algos.keys.sorted(), grouped.sorted())
    }

    @Test
    fun `category labels are chinese`() {
        for ((cat, label, names) in lib.algosByCategory()) {
            assertTrue("$cat 应有中文标签", label.any { it.code > 0x2000 })
            assertTrue("$cat 不应为空组", names.isNotEmpty())
        }
    }
}

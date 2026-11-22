package io.github.Earth1283.quickStart.platform

import org.bukkit.Material
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SafeTeleportTest {
    private val air = BlockSample.passable(Material.AIR)

    @Test
    fun `flat solid ground with clear air above is safe`() {
        val issue = check(0.5, 64.0, 0.5) { bx, by, bz ->
            if (by <= 63) BlockSample.fullCube(Material.GRASS_BLOCK, bx, by, bz) else air
        }
        assertNull(issue)
    }

    @Test
    fun `partial support blocks like slabs and dirt paths are safe`() {
        val slabIssue = check(0.5, 64.5, 0.5) { bx, by, bz ->
            when {
                by == 64 -> BlockSample(Material.STONE_SLAB, listOf(BlockBox(bx.toDouble(), 64.0, bz.toDouble(), bx + 1.0, 64.5, bz + 1.0)))
                by < 64 -> BlockSample.fullCube(Material.STONE, bx, by, bz)
                else -> air
            }
        }
        assertNull(slabIssue)

        val pathIssue = check(0.5, 63.9375, 0.5) { bx, by, bz ->
            when {
                by == 63 -> BlockSample(Material.DIRT_PATH, listOf(BlockBox(bx.toDouble(), 63.0, bz.toDouble(), bx + 1.0, 63.9375, bz + 1.0)))
                by < 63 -> BlockSample.fullCube(Material.DIRT, bx, by, bz)
                else -> air
            }
        }
        assertNull(pathIssue)
    }

    @Test
    fun `water and climbables provide safe landing`() {
        val waterIssue = check(0.5, 62.0, 0.5) { _, by, _ ->
            if (by <= 62) BlockSample.passable(Material.WATER) else air
        }
        assertNull(waterIssue)

        val ladderIssue = check(0.5, 100.0, 0.5) { _, by, _ ->
            if (by == 100) BlockSample.passable(Material.LADDER) else air
        }
        assertNull(ladderIssue)
    }

    @Test
    fun `suffocation and low ceiling are rejected as obstructed`() {
        val headBlocked = check(0.5, 64.0, 0.5) { bx, by, bz ->
            when (by) {
                63 -> BlockSample.fullCube(Material.STONE, bx, by, bz)
                65 -> BlockSample.fullCube(Material.STONE, bx, by, bz)
                else -> air
            }
        }
        assertEquals(SafetyIssue.OBSTRUCTED, headBlocked)

        val feetBlocked = check(0.5, 64.0, 0.5) { bx, by, bz ->
            if (by <= 64) BlockSample.fullCube(Material.STONE, bx, by, bz) else air
        }
        assertEquals(SafetyIssue.OBSTRUCTED, feetBlocked)
    }

    @Test
    fun `hazards at feet head or support are rejected`() {
        for (hazard in listOf(Material.LAVA, Material.FIRE, Material.MAGMA_BLOCK, Material.CACTUS, Material.NETHER_PORTAL)) {
            val underfoot = check(0.5, 64.0, 0.5) { bx, by, bz ->
                when (by) {
                    63 -> if (hazard == Material.MAGMA_BLOCK || hazard == Material.CACTUS) {
                        BlockSample.fullCube(hazard, bx, by, bz)
                    } else BlockSample.passable(hazard)
                    else -> air
                }
            }
            assertEquals(SafetyIssue.HAZARD, underfoot, "Expected hazard for $hazard")
        }
    }

    @Test
    fun `lethal drops and void are rejected unless flying`() {
        val dropIssue = check(0.5, 70.0, 0.5) { bx, by, bz ->
            if (by <= 60) BlockSample.fullCube(Material.STONE, bx, by, bz) else air
        }
        assertEquals(SafetyIssue.UNSAFE_DROP, dropIssue)

        val flyingIssue = check(0.5, 70.0, 0.5, allowMidAir = true) { _, _, _ -> air }
        assertNull(flyingIssue)

        val smallDropIssue = check(0.5, 66.0, 0.5) { bx, by, bz ->
            if (by <= 63) BlockSample.fullCube(Material.STONE, bx, by, bz) else air
        }
        assertNull(smallDropIssue)
    }

    @Test
    fun `out of world height or outside border is rejected`() {
        assertEquals(SafetyIssue.OUT_OF_BOUNDS, check(0.5, -65.0, 0.5) { _, _, _ -> air })
        assertEquals(SafetyIssue.OUT_OF_BOUNDS, check(0.5, 319.0, 0.5) { _, _, _ -> air })
        assertEquals(SafetyIssue.OUT_OF_BOUNDS, check(0.5, 64.0, 0.5, withinBorder = false) { bx, by, bz ->
            if (by <= 63) BlockSample.fullCube(Material.STONE, bx, by, bz) else air
        })
    }

    private fun check(
        x: Double,
        y: Double,
        z: Double,
        minHeight: Int = -64,
        maxHeight: Int = 320,
        withinBorder: Boolean = true,
        allowMidAir: Boolean = false,
        blockAt: (Int, Int, Int) -> BlockSample,
    ): SafetyIssue? = SafeTeleport.evaluateSafety(x, y, z, minHeight, maxHeight, withinBorder, allowMidAir, blockAt)
}

package io.github.Earth1283.quickStart.platform

import kotlin.math.floor
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block

internal enum class SafetyIssue { OUT_OF_BOUNDS, HAZARD, OBSTRUCTED, UNSAFE_DROP }

internal data class BlockBox(
    val minX: Double,
    val minY: Double,
    val minZ: Double,
    val maxX: Double,
    val maxY: Double,
    val maxZ: Double,
) {
    fun overlaps(other: BlockBox): Boolean =
        minX < other.maxX && maxX > other.minX &&
            minY < other.maxY && maxY > other.minY &&
            minZ < other.maxZ && maxZ > other.minZ
}

internal data class BlockSample(
    val material: Material,
    val boxes: List<BlockBox>,
) {
    companion object {
        fun fullCube(material: Material, bx: Int, by: Int, bz: Int) = BlockSample(
            material,
            listOf(BlockBox(bx.toDouble(), by.toDouble(), bz.toDouble(), bx + 1.0, by + 1.0, bz + 1.0)),
        )

        fun passable(material: Material) = BlockSample(material, emptyList())
    }
}

internal object SafeTeleport {
    private const val PLAYER_HALF_WIDTH = 0.29
    private const val PLAYER_HEIGHT = 1.8
    private const val VERTICAL_EPSILON = 0.01
    private const val MAX_SAFE_DROP = 3

    private val hazardousMaterials = setOf(
        Material.LAVA,
        Material.FIRE,
        Material.SOUL_FIRE,
        Material.CAMPFIRE,
        Material.SOUL_CAMPFIRE,
        Material.MAGMA_BLOCK,
        Material.CACTUS,
        Material.SWEET_BERRY_BUSH,
        Material.WITHER_ROSE,
        Material.POINTED_DRIPSTONE,
        Material.POWDER_SNOW,
        Material.NETHER_PORTAL,
        Material.END_PORTAL,
        Material.END_GATEWAY,
    )

    private val climbableMaterials = setOf(
        Material.LADDER,
        Material.VINE,
        Material.TWISTING_VINES,
        Material.TWISTING_VINES_PLANT,
        Material.WEEPING_VINES,
        Material.WEEPING_VINES_PLANT,
        Material.CAVE_VINES,
        Material.CAVE_VINES_PLANT,
        Material.SCAFFOLDING,
    )

    fun evaluate(location: Location, allowMidAir: Boolean = false): SafetyIssue? {
        val world = location.world ?: return SafetyIssue.OUT_OF_BOUNDS
        val withinBorder = world.worldBorder.isInside(location)
        return evaluateSafety(
            x = location.x,
            y = location.y,
            z = location.z,
            minHeight = world.minHeight,
            maxHeight = world.maxHeight,
            withinBorder = withinBorder,
            allowMidAir = allowMidAir,
        ) { bx, by, bz -> sample(world.getBlockAt(bx, by, bz)) }
    }

    internal fun evaluateSafety(
        x: Double,
        y: Double,
        z: Double,
        minHeight: Int,
        maxHeight: Int,
        withinBorder: Boolean,
        allowMidAir: Boolean,
        blockAt: (bx: Int, by: Int, bz: Int) -> BlockSample,
    ): SafetyIssue? {
        if (!withinBorder || y < minHeight.toDouble() || y + PLAYER_HEIGHT > maxHeight.toDouble()) {
            return SafetyIssue.OUT_OF_BOUNDS
        }

        val playerBox = BlockBox(
            minX = x - PLAYER_HALF_WIDTH,
            minY = y + VERTICAL_EPSILON,
            minZ = z - PLAYER_HALF_WIDTH,
            maxX = x + PLAYER_HALF_WIDTH,
            maxY = y + PLAYER_HEIGHT - VERTICAL_EPSILON,
            maxZ = z + PLAYER_HALF_WIDTH,
        )
        val minBx = floor(playerBox.minX).toInt()
        val maxBx = floor(playerBox.maxX).toInt()
        val minBy = floor(playerBox.minY).toInt()
        val maxBy = floor(playerBox.maxY).toInt()
        val minBz = floor(playerBox.minZ).toInt()
        val maxBz = floor(playerBox.maxZ).toInt()

        var supportedInVolume = false
        for (by in minBy..maxBy) {
            for (bx in minBx..maxBx) {
                for (bz in minBz..maxBz) {
                    val sample = blockAt(bx, by, bz)
                    if (sample.material in hazardousMaterials) return SafetyIssue.HAZARD
                    if (sample.material == Material.WATER || sample.material in climbableMaterials) {
                        supportedInVolume = true
                    }
                    if (sample.boxes.any { it.overlaps(playerBox) }) return SafetyIssue.OBSTRUCTED
                }
            }
        }

        if (allowMidAir || supportedInVolume) return null

        val startY = floor(y - VERTICAL_EPSILON).toInt()
        val minSupportY = maxOf(minHeight, startY - MAX_SAFE_DROP)
        for (by in startY downTo minSupportY) {
            var hasSolidSupport = false
            var hasWaterSupport = false
            for (bx in minBx..maxBx) {
                for (bz in minBz..maxBz) {
                    val sample = blockAt(bx, by, bz)
                    if (sample.material in hazardousMaterials) return SafetyIssue.HAZARD
                    if (sample.material == Material.WATER) hasWaterSupport = true
                    if (sample.boxes.any { box ->
                            box.minX < playerBox.maxX && box.maxX > playerBox.minX &&
                                box.minZ < playerBox.maxZ && box.maxZ > playerBox.minZ &&
                                box.maxY <= y + VERTICAL_EPSILON
                        }
                    ) {
                        hasSolidSupport = true
                    }
                }
            }
            if (hasSolidSupport || hasWaterSupport) return null
        }
        return SafetyIssue.UNSAFE_DROP
    }

    private fun sample(block: Block): BlockSample {
        val bx = block.x.toDouble()
        val by = block.y.toDouble()
        val bz = block.z.toDouble()
        val boxes = block.collisionShape.boundingBoxes.map { box ->
            BlockBox(bx + box.minX, by + box.minY, bz + box.minZ, bx + box.maxX, by + box.maxY, bz + box.maxZ)
        }
        return BlockSample(block.type, boxes)
    }
}

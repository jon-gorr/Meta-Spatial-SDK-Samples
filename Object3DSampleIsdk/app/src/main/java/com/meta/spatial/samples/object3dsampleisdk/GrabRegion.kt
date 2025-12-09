/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.spatial.samples.object3dsampleisdk

/**
 * Helper object for creating common grab region configurations.
 *
 * Grab regions use normalized coordinates (0-1) where:
 * - (0, 0) = bottom-left of the panel
 * - (1, 1) = top-right of the panel
 *
 * Usage:
 * ```kotlin
 * // Grab from top 10% of panel (title bar style)
 * GrabRegion.top(0.1f).applyTo(grabComponent)
 *
 * // Or use extension function
 * GrabComponent().apply { setRegion(GrabRegion.top(0.1f)) }
 *
 * // Or create directly
 * GrabRegion.top().toGrabComponent()
 * ```
 */
object GrabRegion {

    /**
     * Default size for edge regions (10% of panel dimension).
     */
    const val DEFAULT_EDGE_SIZE = 0.1f

    /**
     * Represents a normalized grab region.
     */
    data class Region(
        val minX: Float,
        val maxX: Float,
        val minY: Float,
        val maxY: Float
    ) {
        /**
         * Applies this region to an existing GrabComponent.
         */
        fun applyTo(component: GrabComponent) {
            component.regionMinX = minX
            component.regionMaxX = maxX
            component.regionMinY = minY
            component.regionMaxY = maxY
        }

        /**
         * Creates a new GrabComponent with this region.
         */
        fun toGrabComponent(): GrabComponent {
            return GrabComponent().apply {
                regionMinX = minX
                regionMaxX = maxX
                regionMinY = minY
                regionMaxY = maxY
            }
        }

        /**
         * Combines this region with another, creating a region that covers both.
         */
        fun union(other: Region): Region {
            return Region(
                minX = minOf(minX, other.minX),
                maxX = maxOf(maxX, other.maxX),
                minY = minOf(minY, other.minY),
                maxY = maxOf(maxY, other.maxY)
            )
        }
    }

    /**
     * Creates a grab region covering the entire panel.
     */
    fun full(): Region = Region(0f, 1f, 0f, 1f)

    /**
     * Creates a grab region at the top of the panel.
     *
     * @param size The height of the region as a fraction of panel height (0-1). Default is 10%.
     */
    fun top(size: Float = DEFAULT_EDGE_SIZE): Region {
        return Region(
            minX = 0f,
            maxX = 1f,
            minY = 1f - size,
            maxY = 1f
        )
    }

    /**
     * Creates a grab region at the bottom of the panel.
     *
     * @param size The height of the region as a fraction of panel height (0-1). Default is 10%.
     */
    fun bottom(size: Float = DEFAULT_EDGE_SIZE): Region {
        return Region(
            minX = 0f,
            maxX = 1f,
            minY = 0f,
            maxY = size
        )
    }

    /**
     * Creates a grab region at the left of the panel.
     *
     * @param size The width of the region as a fraction of panel width (0-1). Default is 10%.
     */
    fun left(size: Float = DEFAULT_EDGE_SIZE): Region {
        return Region(
            minX = 0f,
            maxX = size,
            minY = 0f,
            maxY = 1f
        )
    }

    /**
     * Creates a grab region at the right of the panel.
     *
     * @param size The width of the region as a fraction of panel width (0-1). Default is 10%.
     */
    fun right(size: Float = DEFAULT_EDGE_SIZE): Region {
        return Region(
            minX = 1f - size,
            maxX = 1f,
            minY = 0f,
            maxY = 1f
        )
    }

    /**
     * Creates a grab region as a border around the entire panel.
     *
     * @param size The width of the border as a fraction of panel dimensions (0-1). Default is 10%.
     */
    fun border(size: Float = DEFAULT_EDGE_SIZE): Region {
        // For a border, we actually want to allow grabbing anywhere except the center
        // This is approximated by using the full region, but ideally you'd use multiple regions
        // or a different check. For simplicity, this returns top + bottom combined.
        return top(size).union(bottom(size)).union(left(size)).union(right(size))
    }

    /**
     * Creates a custom grab region with explicit normalized coordinates.
     *
     * @param minX Left edge (0 = left, 1 = right)
     * @param maxX Right edge (0 = left, 1 = right)
     * @param minY Bottom edge (0 = bottom, 1 = top)
     * @param maxY Top edge (0 = bottom, 1 = top)
     */
    fun custom(minX: Float, maxX: Float, minY: Float, maxY: Float): Region {
        return Region(minX, maxX, minY, maxY)
    }

    /**
     * Creates a grab region for the top-left corner.
     *
     * @param width The width as a fraction of panel width (0-1). Default is 10%.
     * @param height The height as a fraction of panel height (0-1). Default is 10%.
     */
    fun topLeft(width: Float = DEFAULT_EDGE_SIZE, height: Float = DEFAULT_EDGE_SIZE): Region {
        return Region(
            minX = 0f,
            maxX = width,
            minY = 1f - height,
            maxY = 1f
        )
    }

    /**
     * Creates a grab region for the top-right corner.
     *
     * @param width The width as a fraction of panel width (0-1). Default is 10%.
     * @param height The height as a fraction of panel height (0-1). Default is 10%.
     */
    fun topRight(width: Float = DEFAULT_EDGE_SIZE, height: Float = DEFAULT_EDGE_SIZE): Region {
        return Region(
            minX = 1f - width,
            maxX = 1f,
            minY = 1f - height,
            maxY = 1f
        )
    }

    /**
     * Creates a grab region for the bottom-left corner.
     *
     * @param width The width as a fraction of panel width (0-1). Default is 10%.
     * @param height The height as a fraction of panel height (0-1). Default is 10%.
     */
    fun bottomLeft(width: Float = DEFAULT_EDGE_SIZE, height: Float = DEFAULT_EDGE_SIZE): Region {
        return Region(
            minX = 0f,
            maxX = width,
            minY = 0f,
            maxY = height
        )
    }

    /**
     * Creates a grab region for the bottom-right corner.
     *
     * @param width The width as a fraction of panel width (0-1). Default is 10%.
     * @param height The height as a fraction of panel height (0-1). Default is 10%.
     */
    fun bottomRight(width: Float = DEFAULT_EDGE_SIZE, height: Float = DEFAULT_EDGE_SIZE): Region {
        return Region(
            minX = 1f - width,
            maxX = 1f,
            minY = 0f,
            maxY = height
        )
    }
}

/**
 * Extension function to set a grab region on a GrabComponent.
 */
fun GrabComponent.setRegion(region: GrabRegion.Region) {
    region.applyTo(this)
}

/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.spatial.samples.object3dsampleisdk

import android.animation.ValueAnimator
import android.view.animation.DecelerateInterpolator
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Query
import com.meta.spatial.core.SystemBase
import com.meta.spatial.core.Vector3
import com.meta.spatial.runtime.ButtonBits
import com.meta.spatial.runtime.HitInfo
import com.meta.spatial.runtime.InputListener
import com.meta.spatial.runtime.PanelSceneObject
import com.meta.spatial.runtime.SceneObject
import com.meta.spatial.toolkit.Controller
import com.meta.spatial.toolkit.Grabbable
import com.meta.spatial.toolkit.Panel
import com.meta.spatial.toolkit.SceneObjectSystem
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.TransformParent
import com.meta.spatial.toolkit.getAbsoluteTransform

/**
 * Defines a rectangular grab region on a panel in local space.
 * Coordinates are relative to the panel's local space, where:
 * - (0, 0) is typically the center of the panel
 * - x extends horizontally, y extends vertically
 *
 * @property minX The minimum x coordinate of the grab region
 * @property maxX The maximum x coordinate of the grab region
 * @property minY The minimum y coordinate of the grab region
 * @property maxY The maximum y coordinate of the grab region
 */
data class GrabRegion(
    val minX: Float,
    val maxX: Float,
    val minY: Float,
    val maxY: Float
) {
    companion object {
        /**
         * Creates a grab region at the top of a panel.
         * Useful for a "title bar" style grab handle.
         *
         * @param panelWidth The total width of the panel in meters
         * @param panelHeight The total height of the panel in meters
         * @param handleHeight The height of the grab handle area in meters
         */
        fun topHandle(panelWidth: Float, panelHeight: Float, handleHeight: Float = 0.05f): GrabRegion {
            val halfWidth = panelWidth / 2f
            val halfHeight = panelHeight / 2f
            return GrabRegion(
                minX = -halfWidth,
                maxX = halfWidth,
                minY = halfHeight - handleHeight,
                maxY = halfHeight
            )
        }

        /**
         * Creates a grab region at the bottom of a panel.
         */
        fun bottomHandle(panelWidth: Float, panelHeight: Float, handleHeight: Float = 0.05f): GrabRegion {
            val halfWidth = panelWidth / 2f
            val halfHeight = panelHeight / 2f
            return GrabRegion(
                minX = -halfWidth,
                maxX = halfWidth,
                minY = -halfHeight,
                maxY = -halfHeight + handleHeight
            )
        }

        /**
         * Creates a grab region as edges around the entire panel.
         */
        fun edges(panelWidth: Float, panelHeight: Float, edgeWidth: Float = 0.02f): GrabRegion {
            // Note: This creates a single region, for full edges you'd want multiple regions
            // or a different containment check
            val halfWidth = panelWidth / 2f
            val halfHeight = panelHeight / 2f
            return GrabRegion(
                minX = -halfWidth,
                maxX = halfWidth,
                minY = halfHeight - edgeWidth,
                maxY = halfHeight
            )
        }
    }

    /**
     * Checks if a local point is within this grab region.
     */
    fun contains(localPoint: Vector3): Boolean {
        return localPoint.x >= minX && localPoint.x <= maxX &&
               localPoint.y >= minY && localPoint.y <= maxY
    }
}

/**
 * Holds information about an active grab operation.
 */
private data class GrabInfo(
    val inputSource: Entity,
    val grabbedEntity: Entity,
    val grabbedDistance: Float,
    val grabbedLocalOffset: Vector3,
    val grabbedFacing: Float,
)

/**
 * Holds information about hover state for animation.
 */
private data class HoverInfo(
    val entity: Entity,
    var isHovered: Boolean,
    var isAnimating: Boolean,
    var currentScale: Vector3,
    var targetScale: Vector3,
)

/**
 * A custom grab system that allows grabbing panels only from pre-specified regions.
 *
 * Features:
 * - Region-based grabbing: Only allows grabbing when the hit point is within a defined region
 * - Other areas remain interactive: Buttons and other UI elements outside the grab region
 *   can still be clicked/interacted with without initiating a grab
 * - Hover animations: Panels expand when hovered over the grab region and shrink when the
 *   pointer leaves
 * - Optimized for hands + raycast with pinch input modality (Gaze and Pinch)
 *
 * Usage:
 * 1. Add this system to your systemManager
 * 2. Register grab regions for entities using [registerGrabRegion]
 * 3. Entities must have both [Grabbable] and [Panel] components
 */
class MyGrabSystem : SystemBase() {

    /**
     * The buttons that trigger a grab. Default is squeeze/grip buttons.
     * You can also add trigger buttons for pinch support:
     * ```
     * grabButtons = ButtonBits.ButtonSqueezeR or ButtonBits.ButtonSqueezeL or
     *               ButtonBits.ButtonTriggerR or ButtonBits.ButtonTriggerL
     * ```
     */
    var grabButtons: Int = ButtonBits.ButtonSqueezeR or ButtonBits.ButtonSqueezeL or
                           ButtonBits.ButtonTriggerR or ButtonBits.ButtonTriggerL

    /**
     * Scale multiplier for hover expansion animation.
     */
    var hoverScaleMultiplier: Float = 1.05f

    /**
     * Duration of hover animations in milliseconds.
     */
    var hoverAnimationDurationMs: Long = 200L

    // Internal state
    private var lastTime = System.currentTimeMillis()
    private val grabbingInfo = HashMap<Long, GrabInfo>()
    private val entitiesWithListener = HashSet<Entity>()
    private val entityGrabRegions = HashMap<Long, GrabRegion>()
    private val hoverStates = HashMap<Long, HoverInfo>()
    private val originalScales = HashMap<Long, Vector3>()

    /**
     * Registers a grab region for an entity.
     * The entity must have both [Grabbable] and [Panel] components.
     *
     * @param entity The entity to register
     * @param region The grab region in local panel coordinates
     */
    fun registerGrabRegion(entity: Entity, region: GrabRegion) {
        entityGrabRegions[entity.id] = region
    }

    /**
     * Unregisters a grab region for an entity.
     */
    fun unregisterGrabRegion(entity: Entity) {
        entityGrabRegions.remove(entity.id)
    }

    /**
     * Gets the grab region for an entity, if registered.
     */
    fun getGrabRegion(entity: Entity): GrabRegion? {
        return entityGrabRegions[entity.id]
    }

    private fun getHeadPose(): Pose {
        return getScene().getViewerPose()
    }

    /**
     * Converts a world-space hit point to local panel coordinates.
     */
    private fun worldToLocal(hitPoint: Vector3, entityTransform: Pose): Vector3 {
        return entityTransform.inverse() * hitPoint
    }

    /**
     * Checks if a hit point is within the grab region for an entity.
     */
    private fun isHitInGrabRegion(hitInfo: HitInfo, entity: Entity): Boolean {
        val region = entityGrabRegions[entity.id] ?: return true // If no region defined, allow grab anywhere
        val entityTransform = getAbsoluteTransform(entity)
        val localHitPoint = worldToLocal(hitInfo.point, entityTransform)

        return region.contains(localHitPoint)
    }

    /**
     * Animates the scale of an entity for hover feedback.
     */
    private fun animateScale(entity: Entity, fromScale: Vector3, toScale: Vector3) {
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = hoverAnimationDurationMs
            interpolator = DecelerateInterpolator()
            addUpdateListener { animation ->
                val progress = animation.animatedValue as Float
                val newScale = Vector3(
                    fromScale.x + (toScale.x - fromScale.x) * progress,
                    fromScale.y + (toScale.y - fromScale.y) * progress,
                    fromScale.z + (toScale.z - fromScale.z) * progress
                )

                // Only update if entity still exists and is valid
                if (entity.isValid()) {
                    try {
                        entity.setComponent(com.meta.spatial.toolkit.Scale(newScale))
                        hoverStates[entity.id]?.currentScale = newScale
                    } catch (e: Exception) {
                        // Entity may have been destroyed during animation
                    }
                }
            }
            addListener(object : android.animation.Animator.AnimatorListener {
                override fun onAnimationStart(animation: android.animation.Animator) {
                    hoverStates[entity.id]?.isAnimating = true
                }
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    hoverStates[entity.id]?.isAnimating = false
                }
                override fun onAnimationCancel(animation: android.animation.Animator) {
                    hoverStates[entity.id]?.isAnimating = false
                }
                override fun onAnimationRepeat(animation: android.animation.Animator) {}
            })
            start()
        }
    }

    /**
     * Handles hover enter - expands the grab region visually.
     */
    private fun onHoverEnter(entity: Entity, hitInfo: HitInfo) {
        if (!isHitInGrabRegion(hitInfo, entity)) return

        val hoverInfo = hoverStates[entity.id]
        if (hoverInfo != null && hoverInfo.isHovered) return // Already hovered

        // Store original scale if not already stored
        if (!originalScales.containsKey(entity.id)) {
            try {
                val scaleComponent = entity.tryGetComponent<com.meta.spatial.toolkit.Scale>()
                originalScales[entity.id] = scaleComponent?.scale ?: Vector3(1f, 1f, 1f)
            } catch (e: Exception) {
                originalScales[entity.id] = Vector3(1f, 1f, 1f)
            }
        }

        val originalScale = originalScales[entity.id] ?: Vector3(1f, 1f, 1f)
        val expandedScale = originalScale * hoverScaleMultiplier
        val currentScale = hoverStates[entity.id]?.currentScale ?: originalScale

        hoverStates[entity.id] = HoverInfo(
            entity = entity,
            isHovered = true,
            isAnimating = false,
            currentScale = currentScale,
            targetScale = expandedScale
        )

        animateScale(entity, currentScale, expandedScale)
    }

    /**
     * Handles hover exit - shrinks the grab region back to original size.
     */
    private fun onHoverExit(entity: Entity) {
        val hoverInfo = hoverStates[entity.id] ?: return
        if (!hoverInfo.isHovered) return // Already not hovered

        val originalScale = originalScales[entity.id] ?: Vector3(1f, 1f, 1f)
        val currentScale = hoverInfo.currentScale

        hoverStates[entity.id] = HoverInfo(
            entity = entity,
            isHovered = false,
            isAnimating = false,
            currentScale = currentScale,
            targetScale = originalScale
        )

        animateScale(entity, currentScale, originalScale)
    }

    private fun getFacing(so: SceneObject, grabbedTransform: Pose): Float {
        if (so is PanelSceneObject) {
            // For panels, we want to always face the user, aka. the opposite of the panel normal
            return -1f
        }
        // Assess which side of the object the viewer is
        val headPose = getHeadPose()
        val forward = grabbedTransform.q * Vector3(0f, 0f, 1f)
        val side = (headPose.t - grabbedTransform.t).dot(forward)
        return if (side < 0f) -1f else 1f
    }

    private fun smoothOver(dt: Float, convergenceFraction: Float): Float {
        val smoothTime = 1f / 60f
        return 1f - Math.pow(1f - convergenceFraction.toDouble(), (dt / smoothTime).toDouble()).toFloat()
    }

    /**
     * Finds new entities with Grabbable and Panel components and sets up input listeners.
     */
    private fun findNewObjects() {
        // Query for entities that have both Grabbable and Panel components
        val q = Query.where {
            (changed(Grabbable.id, Panel.id)) and has(Grabbable.id) and has(Panel.id)
        }

        for (entity in q.eval()) {
            if (entitiesWithListener.contains(entity)) {
                continue
            }

            val completable = systemManager.findSystem<SceneObjectSystem>().getSceneObject(entity) ?: continue

            completable.thenAccept { sceneObject ->
                entitiesWithListener.add(entity)

                sceneObject.addInputListener(object : InputListener {
                    private var lastHoverInRegion = false

                    override fun onInput(
                        receiver: SceneObject,
                        hitInfo: HitInfo,
                        sourceOfInput: Entity,
                        changed: Int,
                        clicked: Int,
                        downTime: Long
                    ): Boolean {
                        val receiverEntity = receiver.entity ?: return false

                        // Check if hit is in grab region for hover state
                        val isInGrabRegion = isHitInGrabRegion(hitInfo, receiverEntity)

                        // Handle hover state transitions
                        if (isInGrabRegion && !lastHoverInRegion) {
                            onHoverEnter(receiverEntity, hitInfo)
                        } else if (!isInGrabRegion && lastHoverInRegion) {
                            onHoverExit(receiverEntity)
                        }
                        lastHoverInRegion = isInGrabRegion

                        // Check for grab button press
                        val anyButtonDown: Int = changed and clicked
                        if ((anyButtonDown and grabButtons) != 0) {
                            // Check if already grabbing something
                            if (grabbingInfo.containsKey(sourceOfInput.id)) {
                                return false
                            }

                            // Check if grabbable is enabled
                            val grabbable = receiverEntity.tryGetComponent<Grabbable>()
                            if (grabbable == null || !grabbable.enabled) {
                                return false
                            }

                            // CRITICAL: Check if hit is in the grab region
                            if (!isHitInGrabRegion(hitInfo, receiverEntity)) {
                                // Not in grab region - allow click-through for buttons etc.
                                return false
                            }

                            // Verify source has controller component
                            if (!sourceOfInput.hasComponent<Controller>()) {
                                return false
                            }

                            val grabbedTransform = getAbsoluteTransform(receiverEntity)

                            // Store grab info
                            grabbingInfo[sourceOfInput.id] = GrabInfo(
                                inputSource = sourceOfInput,
                                grabbedEntity = receiverEntity,
                                grabbedDistance = hitInfo.distance,
                                grabbedLocalOffset = grabbedTransform.inverse() * hitInfo.point,
                                grabbedFacing = getFacing(sceneObject, grabbedTransform)
                            )

                            // Mark as grabbed
                            grabbable.isGrabbed = true
                            receiverEntity.setComponent(grabbable)

                            return true // Consume the input
                        }

                        return false
                    }

                    override fun stopInput(receiver: SceneObject, sourceOfInput: Entity, downTime: Long) {
                        val receiverEntity = receiver.entity ?: return

                        // Reset hover state when input stops
                        if (lastHoverInRegion) {
                            onHoverExit(receiverEntity)
                            lastHoverInRegion = false
                        }
                    }
                })
            }
        }
    }

    /**
     * Processes active grabs, updating positions and handling releases.
     */
    private fun processGrabbable(dt: Float) {
        if (grabbingInfo.isEmpty()) return

        val headPose = getHeadPose()

        val toRemove = mutableListOf<Long>()

        grabbingInfo.forEach { (sourceId, info) ->
            val controller = info.inputSource.tryGetComponent<Controller>()
            if (controller == null) {
                toRemove.add(sourceId)
                return@forEach
            }

            // Check for button release
            val anyButtonReleased: Int = controller.changedButtons and (controller.buttonState.inv())
            if ((anyButtonReleased and grabButtons) != 0) {
                toRemove.add(sourceId)

                // Mark as not grabbed
                val grabbable = info.grabbedEntity.tryGetComponent<Grabbable>()
                if (grabbable != null) {
                    grabbable.isGrabbed = false
                    info.grabbedEntity.setComponent(grabbable)
                    grabbable.recycle()
                }

                controller.recycle()
                return@forEach
            }

            // Update grabbed object position
            val transform = info.inputSource.getComponent<Transform>()
            val newTranslation = transform.transform * Vector3(0f, 0f, info.grabbedDistance)

            var grabbedTransform = getAbsoluteTransform(info.grabbedEntity)
            val grabbable = info.grabbedEntity.tryGetComponent<Grabbable>()

            // Calculate rotation to face user
            val lookDirection = headPose.t - grabbedTransform.t
            val nextRotation = Quaternion.lookRotationAroundY(lookDirection * info.grabbedFacing)

            val worldOffset = nextRotation * info.grabbedLocalOffset

            // Smooth interpolation
            val interpolationRate = 0.15f
            grabbedTransform = grabbedTransform.lerp(
                Pose(t = newTranslation - worldOffset, q = nextRotation),
                smoothOver(dt, interpolationRate)
            )

            // Apply height constraints if set
            if (grabbable != null) {
                grabbedTransform.t.y = grabbedTransform.t.y.coerceIn(grabbable.minHeight, grabbable.maxHeight)
            }

            // Handle parented entities
            if (info.grabbedEntity.hasComponent<TransformParent>()) {
                val transformParentComponent = info.grabbedEntity.getComponent<TransformParent>()
                val parent = transformParentComponent.entity
                val parentTransform = getAbsoluteTransform(parent)
                grabbedTransform = parentTransform.inverse() * grabbedTransform
                transformParentComponent.recycle()
            }

            info.grabbedEntity.setComponent(Transform(grabbedTransform))

            if (grabbable != null && !grabbable.isGrabbed) {
                grabbable.isGrabbed = true
                info.grabbedEntity.setComponent(grabbable)
            }

            controller.recycle()
            transform.recycle()
            grabbable?.recycle()
        }

        // Remove completed grabs
        toRemove.forEach { grabbingInfo.remove(it) }
    }

    override fun execute() {
        val currentTime = System.currentTimeMillis()
        val dt = ((currentTime - lastTime) / 1000f).coerceAtMost(0.1f)
        lastTime = currentTime

        findNewObjects()
        processGrabbable(dt)
    }

    override fun delete(entity: Entity) {
        entitiesWithListener.remove(entity)
        entityGrabRegions.remove(entity.id)
        hoverStates.remove(entity.id)
        originalScales.remove(entity.id)

        // Remove any grabs involving this entity
        grabbingInfo.entries.removeIf { it.value.grabbedEntity.id == entity.id }
    }
}

/**
 * Extension function to multiply a Vector3 by a scalar.
 */
private operator fun Vector3.times(scalar: Float): Vector3 {
    return Vector3(this.x * scalar, this.y * scalar, this.z * scalar)
}

/**
 * Extension function to check if an entity is valid.
 */
private fun Entity.isValid(): Boolean {
    return this.id != 0L
}

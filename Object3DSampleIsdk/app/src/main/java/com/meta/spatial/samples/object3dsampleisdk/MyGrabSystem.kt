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
import com.meta.spatial.core.Vector2
import com.meta.spatial.core.Vector3
import com.meta.spatial.runtime.ButtonBits
import com.meta.spatial.runtime.HitInfo
import com.meta.spatial.runtime.InputListener
import com.meta.spatial.runtime.PanelDimensionsOverrides
import com.meta.spatial.runtime.PanelSceneObject
import com.meta.spatial.runtime.SceneObject
import com.meta.spatial.toolkit.Controller
import com.meta.spatial.toolkit.Panel
import com.meta.spatial.toolkit.SceneObjectSystem
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.TransformParent
import com.meta.spatial.toolkit.getAbsoluteTransform

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
 * A custom grab system that allows grabbing panels only from regions defined in their GrabComponent.
 *
 * Features:
 * - Region-based grabbing: Only allows grabbing when the hit point is within the region
 *   defined in the entity's [GrabComponent] (regionMinX, regionMaxX, regionMinY, regionMaxY)
 * - Other areas remain interactive: Buttons and other UI elements outside the grab region
 *   can still be clicked/interacted with without initiating a grab
 * - Hover animations: Panels expand when hovered over the grab region and shrink when the
 *   pointer leaves
 * - Optimized for hands + raycast with pinch input modality (Gaze and Pinch)
 *
 * Usage:
 * 1. Add this system to your systemManager
 * 2. Configure the grab region directly on the [GrabComponent]:
 *    - regionMinX, regionMaxX: horizontal bounds in local coordinates
 *    - regionMinY, regionMaxY: vertical bounds in local coordinates
 * 3. Entities must have both [GrabComponent] and [Panel] components
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
    private val hoverStates = HashMap<Long, HoverInfo>()
    private val originalScales = HashMap<Long, Vector3>()

    private fun getHeadPose(): Pose {
        return getScene().getViewerPose()
    }

    /**
     * Converts a world-space point to local panel coordinates.
     */
    private fun worldToLocal(hitPoint: Vector3, entityTransform: Pose): Vector3 {
        return entityTransform.inverse() * hitPoint
    }

    /**
     * Converts normalized coordinates (0-1) to local panel coordinates.
     *
     * @param normalizedX The normalized X coordinate (0 = left, 1 = right)
     * @param normalizedY The normalized Y coordinate (0 = bottom, 1 = top)
     * @param panelWidth The panel width in meters
     * @param panelHeight The panel height in meters
     * @return The local coordinate with (0,0) at center
     */
    private fun normalizedToLocal(
        normalizedX: Float,
        normalizedY: Float,
        panelWidth: Float,
        panelHeight: Float
    ): Pair<Float, Float> {
        // Convert from (0,0)=bottom-left, (1,1)=top-right to centered coordinates
        val localX = (normalizedX - 0.5f) * panelWidth
        val localY = (normalizedY - 0.5f) * panelHeight
        return Pair(localX, localY)
    }

    /**
     * Gets the panel dimensions from the PanelSceneObject's config.
     * Returns null if dimensions cannot be determined.
     */
    private fun getPanelDimensions(entity: Entity, sceneObject: SceneObject?): Pair<Float, Float>? {
        if (sceneObject is PanelSceneObject) {
            val dimensions: Vector2? = PanelDimensionsOverrides.get(entity)
            if (dimensions != null) {
                return Pair(dimensions.x, dimensions.y)
            }
            return Pair(1f, 1f)
        }
        return null
    }

    /**
     * Checks if a hit point is within the grab region defined in the entity's GrabComponent.
     * The GrabComponent uses normalized coordinates (0-1) which are converted to local space.
     */
    private fun isHitInGrabRegion(hitInfo: HitInfo, entity: Entity, sceneObject: SceneObject?): Boolean {
        val grabComponent = entity.tryGetComponent<GrabComponent>() ?: return true
        val entityTransform = getAbsoluteTransform(entity)
        val localHitPoint = worldToLocal(hitInfo.point, entityTransform)

        // Get panel dimensions for coordinate conversion
        val dimensions = getPanelDimensions(entity, sceneObject)
        if (dimensions == null) {
            grabComponent.recycle()
            return true // Can't determine dimensions, allow grab
        }

        val (panelWidth, panelHeight) = dimensions

        // Convert normalized region bounds to local coordinates
        val (minX, minY) = normalizedToLocal(
            grabComponent.regionMinX,
            grabComponent.regionMinY,
            panelWidth,
            panelHeight
        )
        val (maxX, maxY) = normalizedToLocal(
            grabComponent.regionMaxX,
            grabComponent.regionMaxY,
            panelWidth,
            panelHeight
        )
        val isInRegion = localHitPoint.x >= minX &&
                         localHitPoint.x <= maxX &&
                         localHitPoint.y >= minY &&
                         localHitPoint.y <= maxY

        grabComponent.recycle()
        return isInRegion
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
    private fun onHoverEnter(entity: Entity, hitInfo: HitInfo, sceneObject: SceneObject?) {
        if (!isHitInGrabRegion(hitInfo, entity, sceneObject)) return

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
     * Finds new entities with GrabComponent and Panel components and sets up input listeners.
     */
    private fun findNewObjects() {
        // Query for entities that have both GrabComponent and Panel components
        val q = Query.where {
            (changed(GrabComponent.id, Panel.id)) and has(GrabComponent.id) and has(Panel.id)
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
                        val isInGrabRegion = isHitInGrabRegion(hitInfo, receiverEntity, receiver)

                        // Handle hover state transitions
                        if (isInGrabRegion && !lastHoverInRegion) {
                            onHoverEnter(receiverEntity, hitInfo, receiver)
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
                            if (!receiverEntity.hasComponent<GrabComponent>()) {
                                return false
                            }

                            // CRITICAL: Check if hit is in the grab region
                            if (!isHitInGrabRegion(hitInfo, receiverEntity, receiver)) {
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
                val grabbable = info.grabbedEntity.tryGetComponent<GrabComponent>()
                grabbable?.recycle()
                controller.recycle()
                return@forEach
            }

            // Update grabbed object position
            val transform = info.inputSource.getComponent<Transform>()
            val newTranslation = transform.transform * Vector3(0f, 0f, info.grabbedDistance)

            var grabbedTransform = getAbsoluteTransform(info.grabbedEntity)

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

            // Handle parented entities
            if (info.grabbedEntity.hasComponent<TransformParent>()) {
                val transformParentComponent = info.grabbedEntity.getComponent<TransformParent>()
                val parent = transformParentComponent.entity
                val parentTransform = getAbsoluteTransform(parent)
                grabbedTransform = parentTransform.inverse() * grabbedTransform
                transformParentComponent.recycle()
            }

            info.grabbedEntity.setComponent(Transform(grabbedTransform))

            controller.recycle()
            transform.recycle()
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

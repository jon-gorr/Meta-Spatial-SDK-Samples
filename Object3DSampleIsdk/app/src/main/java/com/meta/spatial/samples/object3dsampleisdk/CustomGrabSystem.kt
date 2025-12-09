// (c) Meta Platforms, Inc. and affiliates. Confidential and proprietary.

package com.meta.spatial.toolkit

import android.graphics.Color
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
import com.meta.spatial.runtime.PanelShapeType
import com.meta.spatial.runtime.SceneObject
import com.meta.spatial.runtime.panel.shapeType
import com.meta.spatial.samples.object3dsampleisdk.GrabComponent
import com.meta.spatial.samples.object3dsampleisdk.GrabbableType

private class GrabInfo(
    val grabbedEntity: Entity,
    val grabbedDistance: Float,
    val grabbedLocalOffset: Vector3,
    val grabbedFacing: Float,
)

/**
 * GrabbableSystem is a System that allows for grabbing of objects in the scene. It is responsible
 * for detecting when an object is grabbed and then updating the object's position and rotation to
 * match the grabber's position and rotation. It also handles the release of the object when the
 * grabber releases it.
 *
 * This System comes default with Meta Spatial SDK and operates on entities with [Grabbable]
 * components. It interacts with [Controller] components to detect grab inputs and can affect
 * [Followable] components by disabling them during grabs.
 */
class CustomGrabSystem() : SystemBase() {

    var active = true

    /**
     * The default is just grabbing with the grip buttons for controllers. You can change the grab buttons by
     * accessing the grabButtons variable of the GrabbableSystem:
     *
     * Example:
     * ```
     * systemManager.findSystem<GrabbableSystem>().grabButtons = (ButtonBits.ButtonSqueezeR or
     * ButtonBits.ButtonSqueezeL or ButtonBits.ButtonX)
     * ```
     */
    public var grabButtons: Int = ButtonBits.ButtonSqueezeR or ButtonBits.ButtonSqueezeL

    /**
     * Hand grab buttons for hand tracking input (pinch gestures).
     * ButtonA maps to right hand pinch, ButtonY maps to left hand pinch.
     * These are used when the controller type is HAND.
     */
    public var handGrabButtons: Int = ButtonBits.ButtonA or ButtonBits.ButtonY

    /**
     * Enable debug drawing of grab regions.
     * When enabled, draws a rectangle outline showing the grabbable area on each panel.
     */
    public var debugDrawEnabled: Boolean = true

    /**
     * Color for debug grab region drawing.
     * Default is cyan for good visibility.
     */
    public var debugDrawColor: Int = Color.CYAN
    private var lastTime = System.currentTimeMillis()
    private val grabbingInfo_ = HashMap<Entity, GrabInfo>()
    private val grabbedEntityToGrabber_ = HashMap<Entity, Entity>()
    private val parentMap_ = HashMap<Entity, Entity>()
    private var entitiesWithListener = HashSet<Entity>()

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
     * Supports both regular rectangular regions and border mode.
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
        val isBorderMode = grabComponent.isBorderMode

        val isInRegion: Boolean

        if (isBorderMode) {
            // In border mode, regionMinX/MaxX/MinY/MaxY represent border widths from each edge
            // The hit is valid if it's within any of the border strips
            val leftBorder = grabComponent.regionMinX
            val rightBorder = grabComponent.regionMaxX
            val bottomBorder = grabComponent.regionMinY
            val topBorder = grabComponent.regionMaxY

            // Convert border widths to local coordinates
            val leftEdge = -panelWidth / 2f
            val rightEdge = panelWidth / 2f
            val bottomEdge = -panelHeight / 2f
            val topEdge = panelHeight / 2f

            val leftBorderLocal = leftEdge + (leftBorder * panelWidth)
            val rightBorderLocal = rightEdge - (rightBorder * panelWidth)
            val bottomBorderLocal = bottomEdge + (bottomBorder * panelHeight)
            val topBorderLocal = topEdge - (topBorder * panelHeight)

            // Check if hit is within any of the four border strips
            val inLeftBorder = localHitPoint.x >= leftEdge && localHitPoint.x <= leftBorderLocal
            val inRightBorder = localHitPoint.x >= rightBorderLocal && localHitPoint.x <= rightEdge
            val inBottomBorder = localHitPoint.y >= bottomEdge && localHitPoint.y <= bottomBorderLocal
            val inTopBorder = localHitPoint.y >= topBorderLocal && localHitPoint.y <= topEdge

            // Point is in border if it's within the panel bounds AND in any border strip
            val inPanelBounds = localHitPoint.x >= leftEdge && localHitPoint.x <= rightEdge &&
                                localHitPoint.y >= bottomEdge && localHitPoint.y <= topEdge

            isInRegion = inPanelBounds && (inLeftBorder || inRightBorder || inBottomBorder || inTopBorder)
        } else {
            // Standard rectangular region mode
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
            isInRegion = localHitPoint.x >= minX &&
                         localHitPoint.x <= maxX &&
                         localHitPoint.y >= minY &&
                         localHitPoint.y <= maxY
        }

        grabComponent.recycle()
        return isInRegion
    }

    /**
     * Draws the grab region as a debug rectangle on the panel.
     * The rectangle is drawn slightly in front of the panel to be visible.
     * Supports both regular rectangular regions and border mode.
     */
    private fun drawDebugGrabRegion(entity: Entity) {
        val grabComponent = entity.tryGetComponent<GrabComponent>() ?: return
        val entityTransform = getAbsoluteTransform(entity)

        // Get panel dimensions
        val sceneObject = systemManager.findSystem<SceneObjectSystem>().getSceneObject(entity)
        var panelWidth = 1f
        var panelHeight = 1f

        sceneObject?.thenAccept { so ->
            val dimensions = getPanelDimensions(entity, so)
            if (dimensions != null) {
                panelWidth = dimensions.first
                panelHeight = dimensions.second
            }
        }

        // Offset slightly in front of the panel (along negative Z in panel local space)
        val zOffset = -0.001f
        val color = Color.valueOf(debugDrawColor)

        if (grabComponent.isBorderMode) {
            // Draw border mode - four separate rectangles for each edge
            val leftBorder = grabComponent.regionMinX
            val rightBorder = grabComponent.regionMaxX
            val bottomBorder = grabComponent.regionMinY
            val topBorder = grabComponent.regionMaxY

            val leftEdge = -panelWidth / 2f
            val rightEdge = panelWidth / 2f
            val bottomEdge = -panelHeight / 2f
            val topEdge = panelHeight / 2f

            val leftBorderLocal = leftEdge + (leftBorder * panelWidth)
            val rightBorderLocal = rightEdge - (rightBorder * panelWidth)
            val bottomBorderLocal = bottomEdge + (bottomBorder * panelHeight)
            val topBorderLocal = topEdge - (topBorder * panelHeight)

            // Draw left border strip
            drawRectangle(entityTransform, leftEdge, bottomEdge, leftBorderLocal, topEdge, zOffset, color)

            // Draw right border strip
            drawRectangle(entityTransform, rightBorderLocal, bottomEdge, rightEdge, topEdge, zOffset, color)

            // Draw bottom border strip (between left and right borders)
            drawRectangle(entityTransform, leftBorderLocal, bottomEdge, rightBorderLocal, bottomBorderLocal, zOffset, color)

            // Draw top border strip (between left and right borders)
            drawRectangle(entityTransform, leftBorderLocal, topBorderLocal, rightBorderLocal, topEdge, zOffset, color)

        } else {
            // Standard rectangular region mode
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

            drawRectangle(entityTransform, minX, minY, maxX, maxY, zOffset, color)
        }

        grabComponent.recycle()
    }

    /**
     * Helper function to draw a rectangle outline in world space.
     */
    private fun drawRectangle(
        entityTransform: Pose,
        minX: Float,
        minY: Float,
        maxX: Float,
        maxY: Float,
        zOffset: Float,
        color: Color
    ) {
        val bottomLeft = Vector3(minX, minY, zOffset)
        val bottomRight = Vector3(maxX, minY, zOffset)
        val topRight = Vector3(maxX, maxY, zOffset)
        val topLeft = Vector3(minX, maxY, zOffset)

        val worldBottomLeft = entityTransform * bottomLeft
        val worldBottomRight = entityTransform * bottomRight
        val worldTopRight = entityTransform * topRight
        val worldTopLeft = entityTransform * topLeft

        getScene().drawDebugLine(worldBottomLeft, worldBottomRight, color, 1)
        getScene().drawDebugLine(worldBottomRight, worldTopRight, color, 1)
        getScene().drawDebugLine(worldTopRight, worldTopLeft, color, 1)
        getScene().drawDebugLine(worldTopLeft, worldBottomLeft, color, 1)
    }

    /**
     * Draws debug grab regions for all entities with GrabComponent.
     */
    private fun drawDebugGrabRegions() {
        if (!debugDrawEnabled) return

        val q = Query.where { has(GrabComponent.id) }
        for (entity in q.eval()) {
            drawDebugGrabRegion(entity)
        }
    }

    private fun findNewObjects() {
        val meshQuery = Query.where { (changed(Panel.id) or changed(Mesh.id)) and has(GrabComponent.id) }
        for (entity in meshQuery.eval()) {
            entitiesWithListener.remove(entity)
        }

        val q =
            Query.where {
                (changed(GrabComponent.id, Panel.id) or changed(GrabComponent.id, Mesh.id)) and has(GrabComponent.id)
            }
        for (entity in q.eval()) {
            val systemObject =
                systemManager.findSystem<SceneObjectSystem>().getSceneObject(entity) ?: continue
            if (entitiesWithListener.contains(entity)) {
                continue
            }
            systemObject.thenAccept { so ->
                entitiesWithListener.add(entity)
                so.addInputListener(
                    object : InputListener {
                        override fun onInput(
                            receiver: SceneObject,
                            hitInfo: HitInfo,
                            sourceOfInput: Entity,
                            changed: Int,
                            clicked: Int,
                            downTime: Long,
                        ): Boolean {
                            if (!sourceOfInput.hasComponent<Controller>()) {
                                return false
                            }
                            val controller = sourceOfInput.getComponent<Controller>()
                            val activeGrabButtons = if (controller.type == ControllerType.HAND) {
                                handGrabButtons
                            } else {
                                grabButtons
                            }
                            controller.recycle()

                            val anyButtonDown: Int = changed and clicked
                            if ((anyButtonDown and activeGrabButtons) != 0) {
                                val grabbed = grabbingInfo_[sourceOfInput]
                                if (grabbed == null) {
                                    val grabbable = entity.getComponent<GrabComponent>()
                                    if (!grabbable.enabled) {
                                        grabbable.recycle()
                                        return false
                                    }
                                    grabbable.recycle()

                                    // Check if hit is within the grab region
                                    if (!isHitInGrabRegion(hitInfo, entity, so)) {
                                        // Not in grab region - allow click-through for buttons etc.
                                        return false
                                    }

                                    val grabbedTransform = getAbsoluteTransform(entity)
                                    grabbingInfo_[sourceOfInput] =
                                        GrabInfo(
                                            entity,
                                            hitInfo.distance,
                                            grabbedTransform.inverse() * hitInfo.point,
                                            getFacing(so, grabbedTransform),
                                        )
                                    grabbedEntityToGrabber_[entity] = sourceOfInput
                                }
                            }
                            return false
                        }

                        override fun stopInput(receiver: SceneObject, sourceOfInput: Entity, downTime: Long) =
                            Unit
                    }
                )
            }
        }
    }

    private fun getFacing(so: SceneObject, grabbedTransform: Pose): Float {
        if (so is PanelSceneObject) {
            // For panels, we want to always face the user, aka. the opposite of the panel normal
            return -1f
        }
        // Assess which side of the panel the viewer is
        // our goal is not to rotate the object more than needed
        // this may become a Grabbable object configuration
        val headPose = getHeadPose()
        val forward = grabbedTransform.q * Vector3(0f, 0f, 1f)
        val side = (headPose.t - grabbedTransform.t).dot(forward)
        return if (side < 0f) -1f else 1f
    }

    private fun smoothOver(dt: Float, convergenceFraction: Float): Float {
        // standardize frame rate for interpolation
        val smoothTime = 1f / 60f
        return 1f -
                Math.pow(1f - convergenceFraction.toDouble(), (dt / smoothTime).toDouble()).toFloat()
    }

    private fun restoreParent(grabbedEntity: Entity) {
        val parent = parentMap_[grabbedEntity] ?: return
        parentMap_.remove(grabbedEntity)
        reparentChildInWorldCoordinates(parent, grabbedEntity)
    }

    private fun getSceneObjectCenter(entity: Entity, grabbedTransform: Pose): Vector3 {
        var position = grabbedTransform.t
        systemManager.findSystem<SceneObjectSystem>().getSceneObject(entity)?.thenAccept { so ->
            if (so is PanelSceneObject) {
                val panel = so as PanelSceneObject
                if (panel.shapeType == PanelShapeType.CYLINDER) {
                    // For cylinder panels, the grab point is the center of the panel, not the center of the
                    // cylinder. To get the center of the panel, we need to move the grab point up by the
                    // radius of the cylinder.
                    val shapeConfig = panel.getPanelShapeConfig()
                    if (shapeConfig == null) {
                        throw IllegalStateException("Cylinder panel entity $entity has no PanelShapeConfig")
                    }
                    val radius = shapeConfig.radiusForCylinderOrSphere
                    position = position + grabbedTransform.forward() * radius
                }
            }
        }
        return position
    }

    private fun processGrabbable(dt: Float) {
        if (grabbingInfo_.isEmpty()) {
            return
        }

        val headPose = getHeadPose()

        grabbingInfo_.forEach { entry ->
            val grabber = entry.key

            val controller = grabber.tryGetComponent<Controller>() ?: return
            val activeGrabButtons = if (controller.type == ControllerType.HAND) {
                handGrabButtons
            } else {
                grabButtons
            }
            val anyButtonReleased: Int = controller.changedButtons and (controller.buttonState.inv())
            if ((anyButtonReleased and activeGrabButtons) != 0) {
                grabbingInfo_.remove(entry.key)
                val info = entry.value
                grabbedEntityToGrabber_.remove(info.grabbedEntity!!)
                val grabbable = info.grabbedEntity.getComponent<GrabComponent>()
                grabbable.isGrabbed = false
                info.grabbedEntity.setComponent(grabbable)
                grabbable.recycle()
                return
            }

            val info = entry.value
            val transform = grabber.getComponent<Transform>()
            val newTranslation = transform.transform * Vector3(0f, 0f, info.grabbedDistance)
            var grabbedTransform = getAbsoluteTransform(info.grabbedEntity!!)
            val grabbable = info.grabbedEntity!!.getComponent<GrabComponent>()

            val lookDirection = headPose.t - getSceneObjectCenter(info.grabbedEntity!!, grabbedTransform)
            val nextRotation =
                when (grabbable.type) {
                    GrabbableType.FACE -> {
                        Quaternion.lookRotation(lookDirection * info.grabbedFacing)
                    }
                    GrabbableType.PIVOT_Y -> {
                        Quaternion.lookRotationAroundY(lookDirection * info.grabbedFacing)
                    }
                }

            val worldOffset = nextRotation * info.grabbedLocalOffset
            // 0.15f is an interpolation rate found to be smooth enough but not too floaty
            val interpolationRate = 0.15f
            grabbedTransform =
                grabbedTransform.lerp(
                    Pose(t = newTranslation - worldOffset, q = nextRotation),
                    smoothOver(dt, interpolationRate),
                )
            grabbedTransform.t.y =
                Math.max(grabbable.minHeight, Math.min(grabbable.maxHeight, grabbedTransform.t.y))
            if (info.grabbedEntity!!.hasComponent<TransformParent>()) {
                val transformParentComponent = info.grabbedEntity!!.getComponent<TransformParent>()
                val parent = transformParentComponent.entity
                val parentTransform = getAbsoluteTransform(parent)
                grabbedTransform = parentTransform.inverse() * grabbedTransform
                transformParentComponent.recycle()
            }
            info.grabbedEntity!!.setComponent(Transform(grabbedTransform))
            if (!grabbable.isGrabbed) {
                grabbable.isGrabbed = true
                info.grabbedEntity!!.setComponent(grabbable)
            }
            controller.recycle()
            transform.recycle()
            grabbable.recycle()
        }
    }

    override fun execute() {
        if (!active) {
            return
        }

        val currentTime = System.currentTimeMillis()
        // clamp the max dt if the interpolation is too large
        val dt = Math.min((currentTime - lastTime) / 1000f, 0.1f)
        findNewObjects()
        processGrabbable(dt)
        drawDebugGrabRegions()
        lastTime = currentTime
    }

    override fun delete(entity: Entity) {
        entitiesWithListener.remove(entity)
        parentMap_.remove(entity)
        grabbedEntityToGrabber_.remove(entity)?.let { grabbingInfo_.remove(it) }
    }
}

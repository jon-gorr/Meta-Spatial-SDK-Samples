// (c) Meta Platforms, Inc. and affiliates. Confidential and proprietary.

package com.meta.spatial.toolkit

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
import com.meta.spatial.runtime.PanelShapeType
import com.meta.spatial.runtime.SceneObject
import com.meta.spatial.runtime.panel.shapeType
import com.meta.spatial.samples.object3dsampleisdk.GrabComponent
import com.meta.spatial.samples.object3dsampleisdk.GrabbableType
import com.meta.spatial.toolkit.ControllerType
import com.meta.spatial.toolkit.GrabbableType.*

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
class MetaGrabbableSystem() : SystemBase() {

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
    private var lastTime = System.currentTimeMillis()
    private val grabbingInfo_ = HashMap<Entity, GrabInfo>()
    private val grabbedEntityToGrabber_ = HashMap<Entity, Entity>()
    private val parentMap_ = HashMap<Entity, Entity>()
    private var entitiesWithListener = HashSet<Entity>()

    private fun getHeadPose(): Pose {
        return getScene().getViewerPose()
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
        lastTime = currentTime
    }

    override fun delete(entity: Entity) {
        entitiesWithListener.remove(entity)
        parentMap_.remove(entity)
        grabbedEntityToGrabber_.remove(entity)?.let { grabbingInfo_.remove(it) }
    }
}

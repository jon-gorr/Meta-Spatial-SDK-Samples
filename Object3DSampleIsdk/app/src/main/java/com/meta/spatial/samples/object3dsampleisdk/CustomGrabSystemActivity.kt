/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.spatial.samples.object3dsampleisdk

import android.os.Bundle
import androidx.compose.ui.platform.ComposeView
import androidx.core.net.toUri
import com.meta.spatial.castinputforward.CastInputForwardFeature
import com.meta.spatial.compose.ComposeFeature
import com.meta.spatial.compose.ComposeViewPanelRegistration
import com.meta.spatial.core.BuildConfig
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.SpatialFeature
import com.meta.spatial.core.Vector2
import com.meta.spatial.core.Vector3
import com.meta.spatial.datamodelinspector.DataModelInspectorFeature
import com.meta.spatial.isdk.IsdkGrabbable
import com.meta.spatial.isdk.IsdkInputListenerSystem
import com.meta.spatial.physics.Physics
import com.meta.spatial.physics.PhysicsFeature
import com.meta.spatial.physics.PhysicsState
import com.meta.spatial.physics.PhysicsWorldBounds
import com.meta.spatial.runtime.HitInfo
import com.meta.spatial.runtime.InputListener
import com.meta.spatial.runtime.PointerEventType
import com.meta.spatial.runtime.ReferenceSpace
import com.meta.spatial.runtime.SceneMaterial
import com.meta.spatial.runtime.SceneObject
import com.meta.spatial.runtime.SemanticType
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.toolkit.DpPerMeterDisplayOptions
import com.meta.spatial.toolkit.GLXFInfo
import com.meta.spatial.toolkit.LayoutXMLPanelRegistration
import com.meta.spatial.toolkit.Material
import com.meta.spatial.toolkit.Mesh
import com.meta.spatial.toolkit.MeshCollision
import com.meta.spatial.toolkit.CustomGrabSystem
import com.meta.spatial.toolkit.Panel
import com.meta.spatial.toolkit.PanelDimensions
import com.meta.spatial.toolkit.PanelRegistration
import com.meta.spatial.toolkit.PanelStyleOptions
import com.meta.spatial.toolkit.QuadShapeOptions
import com.meta.spatial.toolkit.Scale
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.UIPanelSettings
import com.meta.spatial.vr.VRFeature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class CustomGrabSystemActivity : AppSystemActivity() {

    private val activityScope = CoroutineScope(Dispatchers.Main)
    private var gltfxEntity: Entity? = null
    val panelDimensions: Vector2 = Vector2(0.45f, .66f)
    private val physicsStates = HashMap<Entity, PhysicsState>()
    private var robot: Entity? = null
    private var drone: Entity? = null
    private var plant: Entity? = null
    private var deskLamp: Entity? = null
    private var easyChair: Entity? = null
    private var sculpture: Entity? = null
    private var skybox: Entity? = null

    override fun registerFeatures(): List<SpatialFeature> {
        val features =
            mutableListOf<SpatialFeature>(
                PhysicsFeature(
                    spatial,
                    useGrabbablePhysics = false,
                    worldBounds = PhysicsWorldBounds(minY = -100.0f),
                ),
                VRFeature(this),
                ComposeFeature(),
            )
        if (BuildConfig.DEBUG) {
            features.add(CastInputForwardFeature(this))
            features.add(DataModelInspectorFeature(spatial, this.componentManager))
        }
        return features
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Register our custom grab system that supports region-based grabbing
        // This must be done before loading any entities that use it
        systemManager.registerSystem(CustomGrabSystem())
        componentManager.registerComponent<GrabComponent>(GrabComponent.Companion)

        loadGLXF { composition ->
            robot = composition.getNodeByName("robot").entity
            drone = composition.getNodeByName("drone").entity
            plant = composition.getNodeByName("plant").entity
            deskLamp = composition.getNodeByName("deskLamp").entity
            easyChair = composition.getNodeByName("easyChair").entity
            sculpture = composition.getNodeByName("sculpture").entity

            // set the environment to unlit
            val environmentEntity: Entity? = composition.getNodeByName("Environment").entity
            val environmentMesh = environmentEntity?.getComponent<Mesh>()
            environmentMesh?.defaultShaderOverride = SceneMaterial.UNLIT_SHADER
            environmentEntity?.setComponent(environmentMesh!!)
        }
    }

    override fun onSceneReady() {
        super.onSceneReady()

        // set the reference space to enable recentering
        scene.setReferenceSpace(ReferenceSpace.LOCAL_FLOOR)

        scene.setLightingEnvironment(
            ambientColor = Vector3(0f),
            sunColor = Vector3(7.0f, 7.0f, 7.0f),
            sunDirection = -Vector3(1.0f, 3.0f, -2.0f),
            environmentIntensity = 0.3f,
        )
        scene.updateIBLEnvironment("environment.env")

        scene.setViewOrigin(0f, 0.0f, 0.0f, 0.0f)

        skybox =
            Entity.create(
                listOf(
                    Mesh("mesh://skybox".toUri(), hittable = MeshCollision.NoCollision),
                    Material().apply {
                        baseTextureAndroidResourceId = R.drawable.skydome
                        unlit = true
                    },
                    Transform(Pose(Vector3(x = 0f, y = 0f, z = 0f))),
                )
            )

        // Panels with grabbable regions
        // TODO: there is no raycast blocking; therefore clicks and drags pass through grab regions

        // there is a grabbable handle on the right of the panel
        val leftPanel = Entity.create(
            listOf(
                PanelDimensions(panelDimensions),
                Panel(R.id.scroll_panel),
                Transform(Pose(Vector3(x = -.5f, y = 1f, z = 1f))),
                GrabRegion.left(0.1f).toGrabComponent()
            )
        )

        // there is a grabbable handle on the right of the panel
        val rightPanel = Entity.create(
            listOf(
                PanelDimensions(panelDimensions),
                Panel(R.id.scroll_panel),
                Transform(Pose(Vector3(x = 0f, y = 1f, z = 1f))),
                GrabRegion.right(0.1f).toGrabComponent()
            )
        )

        // there is a grabbable handle on the top of the panel
        val topPanel = Entity.create(
            listOf(
                PanelDimensions(panelDimensions),
                Panel(R.id.scroll_panel),
                Transform(Pose(Vector3(x = .5f, y = 1f, z = 1f))),
                GrabRegion.top(0.1f).toGrabComponent()
            )
        )

        // there is a grabbable handle on the bottom of the panel
        val bottomPanel = Entity.create(
            listOf(
                PanelDimensions(panelDimensions),
                Panel(R.id.scroll_panel),
                Transform(Pose(Vector3(x = 1f, y = 1f, z = 1f))),
                GrabRegion.bottom(0.1f).toGrabComponent()
            )
        )

        // there is a grabbable handle around the edge of the panel
        val borderPanel = Entity.create(
            listOf(
                PanelDimensions(panelDimensions),
                Panel(R.id.scroll_panel),
                Transform(Pose(Vector3(x = 0f, y = 2f, z = 1f))),
                GrabRegion.border(0.1f).toGrabComponent()
            )
        )

        // places a grabbable region in the center of the panel proportional to the panel dimensions
        val centerPanel = Entity.create(
            listOf(
                PanelDimensions(panelDimensions),
                Panel(R.id.scroll_panel),
                Transform(Pose(Vector3(x = -.5f, y = 2f, z = 1f))),
                GrabRegion.center(0.5f).toGrabComponent()
            )
        )

        // places a custom grabbable area at the defined min and max x and y
        val customPanel = Entity.create(
            listOf(
                PanelDimensions(panelDimensions),
                Panel(R.id.scroll_panel),
                Transform(Pose(Vector3(x = .5f, y = 2f, z = 1f))),
                GrabRegion.custom(0f, .5f, 0f, .5f).toGrabComponent()
            )
        )

        // uncomment to see the physics debug lines
        spatial.enablePhysicsDebugLines(true)
    }

    override fun registerPanels(): List<PanelRegistration> {
        return listOf(
            ComposeViewPanelRegistration(
                R.id.library_panel,
                composeViewCreator = { _, context ->
                    ComposeView(context).apply {
                        setContent {
                            ObjectLibraryPanel(
                                robot!!,
                                drone!!,
                                plant!!,
                                deskLamp!!,
                                easyChair!!,
                                sculpture!!,
                            )
                        }
                    }
                },
                settingsCreator = {
                    UIPanelSettings(
                        shape = QuadShapeOptions(width = PANEL_WIDTH, height = PANEL_HEIGHT),
                        style = PanelStyleOptions(themeResourceId = R.style.PanelAppThemeTransparent),
                        display = DpPerMeterDisplayOptions(),
                    )
                },
            ),
            LayoutXMLPanelRegistration(
                R.id.scroll_panel,
                layoutIdCreator = { R.layout.scrolling },
                settingsCreator = {
                    UIPanelSettings(shape = QuadShapeOptions(width = 0.3375f, height = 0.6f))
                },
            ),
        )
    }

    private fun loadGLXF(onLoaded: ((GLXFInfo) -> Unit) = {}): Job {
        gltfxEntity = Entity.create()
        return activityScope.launch {
            glXFManager.inflateGLXF(
                "apk:///scenes/Composition.glxf".toUri(),
                rootEntity = gltfxEntity!!,
                onLoaded = onLoaded,
            )
        }
    }
}

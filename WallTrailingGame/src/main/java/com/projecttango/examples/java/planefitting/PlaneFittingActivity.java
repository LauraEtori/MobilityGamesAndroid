/*
 * Copyright 2014 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.projecttango.examples.java.planefitting;

import com.google.atap.tangoservice.Tango;
import com.google.atap.tangoservice.Tango.OnTangoUpdateListener;
import com.google.atap.tangoservice.TangoCameraIntrinsics;
import com.google.atap.tangoservice.TangoConfig;
import com.google.atap.tangoservice.TangoCoordinateFramePair;
import com.google.atap.tangoservice.TangoErrorException;
import com.google.atap.tangoservice.TangoEvent;
import com.google.atap.tangoservice.TangoException;
import com.google.atap.tangoservice.TangoInvalidException;
import com.google.atap.tangoservice.TangoOutOfDateException;
import com.google.atap.tangoservice.TangoPointCloudData;
import com.google.atap.tangoservice.TangoPoseData;
import com.google.atap.tangoservice.TangoXyzIjData;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.hardware.display.DisplayManager;
import android.media.midi.MidiReceiver;
import android.opengl.Matrix;
import android.os.Bundle;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import android.view.Display;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Toast;
import android.view.*;
import android.content.Intent;

import org.rajawali3d.scene.ASceneFrameCallback;
import org.rajawali3d.view.SurfaceView;

import java.io.IOException;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import com.projecttango.tangosupport.TangoPointCloudManager;
import com.projecttango.tangosupport.TangoSupport;
import com.projecttango.tangosupport.TangoSupport.IntersectionPointPlaneModelPair;

import static android.opengl.Matrix.multiplyMV;
import static android.opengl.Matrix.transposeM;


/**
 * An example showing how to use the Tango APIs to create an augmented reality application
 * that uses depth perception to detect flat surfaces in the real world.
 * This example displays a cube in space. When the user clicks the screen, the cube is placed
 * flush with the surface detected with the depth camera in the position clicked.
 * <p/>
 * This example uses Rajawali for the OpenGL rendering. This includes the color camera image in the
 * background and the cube with instructions positioned in space or in the last surface detected.
 * This part is implemented in the {@code AugmentedRealityRenderer} class, like a regular Rajawali
 * application.
 * <p/>
 * This example focuses on using the depth sensor data to detect a plane and position it on the
 * corresponding position in the 3D OpenGL space.
 * <p/>
 * For more details on the augmented reality effects, including color camera texture rendering,
 * see java_augmented_reality_example or java_hello_video_example.
 * <p/>
 * Note that it is important to include the KEY_BOOLEAN_LOWLATENCYIMUINTEGRATION
 * configuration parameter in order to achieve best results synchronizing the
 * Rajawali virtual world with the RGB camera.
 */
public class PlaneFittingActivity extends Activity {
    private static final String TAG = PlaneFittingActivity.class.getSimpleName();
    private static final int INVALID_TEXTURE_ID = 0;

    private static final String CAMERA_PERMISSION = Manifest.permission.CAMERA;
    private static final int CAMERA_PERMISSION_CODE = 0;

    private SurfaceView mSurfaceView;
    private PlaneFittingRenderer mRenderer;
    private TangoPointCloudManager mPointCloudManager;
    private Tango mTango;
    private TangoConfig mConfig;
    private boolean mIsConnected = false;
    private double mCameraPoseTimestamp = 0;

    // Texture rendering related fields
    // NOTE: Naming indicates which thread is in charge of updating this variable.
    private int mConnectedTextureIdGlThread = INVALID_TEXTURE_ID;
    private AtomicBoolean mIsFrameAvailableTangoThread = new AtomicBoolean(false);
    private double mRgbTimestampGlThread;

    private int mDisplayRotation;

    private float[] mDepthTPlane;
    private double mPlanePlacedTimestamp;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mSurfaceView = new SurfaceView(this);
        mRenderer = new PlaneFittingRenderer(this);
        setContentView(mSurfaceView);
        mSurfaceView.setSurfaceRenderer(mRenderer);
        mSurfaceView.setOnTouchListener(new OnSwipeTouchListener(this) {
            @Override
            public void onSwipeRight() {
                Log.w(TAG, "SWIPED RIGHT");
                Intent i =  new Intent(my_context, ConfigActivity.class);
                startActivity(i);
            }

            @Override
            public void onSwipeLeft() {
                Log.w(TAG, "SWIPED LEFT");
                Intent i =  new Intent(my_context, ConfigActivity.class);
                startActivity(i);
            }
        });
        mPointCloudManager = new TangoPointCloudManager();

        DisplayManager displayManager = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        if (displayManager != null) {
            displayManager.registerDisplayListener(new DisplayManager.DisplayListener() {
                @Override
                public void onDisplayAdded(int displayId) {
                }

                @Override
                public void onDisplayChanged(int displayId) {
                    synchronized (this) {
                        setDisplayRotation();
                    }
                }

                @Override
                public void onDisplayRemoved(int displayId) {
                }
            }, null);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();

        // Check and request camera permission at run time.
        if (checkAndRequestPermissions()) {
            bindTangoService();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();

        // Synchronize against disconnecting while the service is being used in the OpenGL thread or
        // in the UI thread.
        synchronized (this) {
            try {
                mRenderer.getCurrentScene().clearFrameCallbacks();
                if (mTango != null) {
                    mTango.disconnectCamera(TangoCameraIntrinsics.TANGO_CAMERA_COLOR);
                    mTango.disconnect();
                }
                // We need to invalidate the connected texture ID so that we cause a
                // re-connection in the OpenGL thread after resume.
                mConnectedTextureIdGlThread = INVALID_TEXTURE_ID;
                mIsConnected = false;
            } catch (TangoErrorException e) {
                Log.e(TAG, getString(R.string.exception_tango_error), e);
            }
        }
    }

    /**
     * Initialize Tango Service as a normal Android Service.
     */
    private void bindTangoService() {
        // Initialize Tango Service as a normal Android Service. Since we call mTango.disconnect()
        // in onPause, this will unbind Tango Service, so every time onResume gets called we
        // should create a new Tango object.
        mTango = new Tango(PlaneFittingActivity.this, new Runnable() {
            // Pass in a Runnable to be called from UI thread when Tango is ready; this Runnable
            // will be running on a new thread.
            // When Tango is ready, we can call Tango functions safely here only when there are no
            // UI thread changes involved.
            @Override
            public void run() {
                // Synchronize against disconnecting while the service is being used in the OpenGL
                // thread or in the UI thread.
                synchronized (PlaneFittingActivity.this) {
                    try {
                        mConfig = setupTangoConfig(mTango);
                        mTango.connect(mConfig);
                        startupTango();
                        TangoSupport.initialize(mTango);
                        connectRenderer();
                        mIsConnected = true;
                        setDisplayRotation();
                    } catch (TangoOutOfDateException e) {
                        Log.e(TAG, getString(R.string.exception_out_of_date), e);
                        showsToastAndFinishOnUiThread(R.string.exception_out_of_date);
                    } catch (TangoErrorException e) {
                        Log.e(TAG, getString(R.string.exception_tango_error), e);
                        showsToastAndFinishOnUiThread(R.string.exception_tango_error);
                    } catch (TangoInvalidException e) {
                        Log.e(TAG, getString(R.string.exception_tango_invalid), e);
                        showsToastAndFinishOnUiThread(R.string.exception_tango_invalid);
                    }
                }
            }
        });
    }

    /**
     * Sets up the Tango configuration object. Make sure mTango object is initialized before
     * making this call.
     */
    private TangoConfig setupTangoConfig(Tango tango) {
        // Use default configuration for Tango Service (motion tracking), plus low latency
        // IMU integration, color camera, depth and drift correction.
        TangoConfig config = tango.getConfig(TangoConfig.CONFIG_TYPE_DEFAULT);
        // NOTE: Low latency integration is necessary to achieve a precise alignment of
        // virtual objects with the RGB image and produce a good AR effect.
        config.putBoolean(TangoConfig.KEY_BOOLEAN_LOWLATENCYIMUINTEGRATION, true);
        config.putBoolean(TangoConfig.KEY_BOOLEAN_COLORCAMERA, true);
        config.putBoolean(TangoConfig.KEY_BOOLEAN_DEPTH, true);
        config.putInt(TangoConfig.KEY_INT_DEPTH_MODE, TangoConfig.TANGO_DEPTH_MODE_POINT_CLOUD);
        // Drift correction allows motion tracking to recover after it loses tracking.
        // The drift-corrected pose is available through the frame pair with
        // base frame AREA_DESCRIPTION and target frame DEVICE.
        config.putBoolean(TangoConfig.KEY_BOOLEAN_DRIFT_CORRECTION, true);

        return config;
    }

    /**
     * Set up the callback listeners for the Tango Service and obtain other parameters required
     * after Tango connection.
     * Listen to updates from the RGB camera and Point Cloud.
     */
    private void startupTango() {
        // No need to add any coordinate frame pairs since we are not
        // using pose data. So just initialize.
        ArrayList<TangoCoordinateFramePair> framePairs = new ArrayList<TangoCoordinateFramePair>();
        mTango.connectListener(framePairs, new OnTangoUpdateListener() {
            @Override
            public void onPoseAvailable(TangoPoseData pose) {
                // We are not using OnPoseAvailable for this app.
            }

            @Override
            public void onFrameAvailable(int cameraId) {
                // Check if the frame available is for the camera we want and update its frame
                // on the view.
                if (cameraId == TangoCameraIntrinsics.TANGO_CAMERA_COLOR) {
                    // Mark a camera frame as available for rendering in the OpenGL thread.
                    mIsFrameAvailableTangoThread.set(true);
                    mSurfaceView.requestRender();
//                    findWall();
                }
            }

            @Override
            public void onXyzIjAvailable(TangoXyzIjData xyzIj) {
                // We are not using onXyzIjAvailable for this app.
            }

            @Override
            public void onPointCloudAvailable(TangoPointCloudData pointCloud) {
                // Save the cloud and point data for later use.
                mPointCloudManager.updatePointCloud(pointCloud);
            }

            @Override
            public void onTangoEvent(TangoEvent event) {
                // We are not using OnPoseAvailable for this app.
            }
        });
    }

    /**
     * Connects the view and renderer to the color camara and callbacks.
     */
    private void connectRenderer() {
        // Register a Rajawali Scene Frame Callback to update the scene camera pose whenever a new
        // RGB frame is rendered.
        // (@see https://github.com/Rajawali/Rajawali/wiki/Scene-Frame-Callbacks)
        mRenderer.getCurrentScene().registerFrameCallback(new ASceneFrameCallback() {
            @Override
            public void onPreFrame(long sceneTime, double deltaTime) {
                // NOTE: This is called from the OpenGL render thread, after all the renderer
                // onRender callbacks have a chance to run and before scene objects are rendered
                // into the scene.

                try {
                    synchronized (PlaneFittingActivity.this) {
                        // Don't execute any tango API actions if we're not connected to the
                        // service.
                        if (!mIsConnected) {
                            return;
                        }

                        // Set up scene camera projection to match RGB camera intrinsics
                        if (!mRenderer.isSceneCameraConfigured()) {
                            TangoCameraIntrinsics intrinsics =
                                    TangoSupport.getCameraIntrinsicsBasedOnDisplayRotation(
                                            TangoCameraIntrinsics.TANGO_CAMERA_COLOR,
                                            mDisplayRotation);
                            mRenderer.setProjectionMatrix(
                                    projectionMatrixFromCameraIntrinsics(intrinsics));
                        }

                        // Connect the camera texture to the OpenGL Texture if necessary
                        // NOTE: When the OpenGL context is recycled, Rajawali may regenerate the
                        // texture with a different ID.
                        if (mConnectedTextureIdGlThread != mRenderer.getTextureId()) {
                            mTango.connectTextureId(TangoCameraIntrinsics.TANGO_CAMERA_COLOR,
                                    mRenderer.getTextureId());
                            mConnectedTextureIdGlThread = mRenderer.getTextureId();
                            Log.d(TAG, "connected to texture id: " + mRenderer.getTextureId());
                        }

                        // If there is a new RGB camera frame available, update the texture with
                        // it.
                        if (mIsFrameAvailableTangoThread.compareAndSet(true, false)) {
                            mRgbTimestampGlThread =
                                    mTango.updateTexture(TangoCameraIntrinsics.TANGO_CAMERA_COLOR);
                        }

                        if (mRgbTimestampGlThread > mCameraPoseTimestamp) {
                            // Calculate the camera color pose at the camera frame update time in
                            // OpenGL engine.
                            //
                            // When drift correction mode is enabled in config file, we need
                            // to query the device with respect to Area Description pose in
                            // order to use the drift-corrected pose.
                            //
                            // Note that if you don't want to use the drift-corrected pose, the
                            // normal device with respect to start of service pose is still
                            // available.
                            TangoPoseData lastFramePose = TangoSupport.getPoseAtTime(
                                    mRgbTimestampGlThread,
                                    TangoPoseData.COORDINATE_FRAME_AREA_DESCRIPTION,
                                    TangoPoseData.COORDINATE_FRAME_CAMERA_COLOR,
                                    TangoSupport.TANGO_SUPPORT_ENGINE_OPENGL,
                                    TangoSupport.TANGO_SUPPORT_ENGINE_OPENGL,
                                    mDisplayRotation);
                            if (lastFramePose.statusCode == TangoPoseData.POSE_VALID) {
                                // Update the camera pose from the renderer.
                                mRenderer.updateRenderCameraPose(lastFramePose);
                                mCameraPoseTimestamp = lastFramePose.timestamp;
                            } else {
                                // When the pose status is not valid, it indicates the tracking has
                                // been lost. In this case, we simply stop rendering.
                                //
                                // This is also the place to display UI to suggest that the user
                                // walk to recover tracking.
                                Log.w(TAG, "Can't get device pose at time: " +
                                        mRgbTimestampGlThread);
                            }

                            if (mDepthTPlane != null) {
                                // Update the position of the rendered cube to the pose of the
                                // detected plane. This update is made thread-safe by the renderer.
                                //
                                // To make sure drift corrected pose is applied to the virtual
                                // object we need to re-query the Area Description to Depth camera
                                // at the time when the corresponding plane fitting
                                // measurement was acquired.
                                TangoSupport.TangoMatrixTransformData openglTDepthArr =
                                        TangoSupport.getMatrixTransformAtTime(
                                                mPlanePlacedTimestamp,
                                                TangoPoseData.COORDINATE_FRAME_AREA_DESCRIPTION,
                                                TangoPoseData.COORDINATE_FRAME_CAMERA_DEPTH,
                                                TangoSupport.TANGO_SUPPORT_ENGINE_OPENGL,
                                                TangoSupport.TANGO_SUPPORT_ENGINE_TANGO,
                                                TangoSupport.ROTATION_IGNORED);

                                if (openglTDepthArr.statusCode == TangoPoseData.POSE_VALID) {
                                    mRenderer.updateObjectPose(openglTDepthArr.matrix,
                                            mDepthTPlane);
                                }
                            }
                        }
                    }
                    // Avoid crashing the application due to unhandled exceptions.
                } catch (TangoErrorException e) {
                    Log.e(TAG, "Tango API call error within the OpenGL render thread", e);
                } catch (Throwable t) {
                    Log.e(TAG, "Exception on the OpenGL thread", t);
                }
            }

            @Override
            public void onPreDraw(long sceneTime, double deltaTime) {

            }

            @Override
            public void onPostFrame(long sceneTime, double deltaTime) {

            }

            @Override
            public boolean callPreFrame() {
                return true;
            }
        });
    }

    /**
     * Use Tango camera intrinsics to calculate the projection Matrix for the Rajawali scene.
     */
    private static float[] projectionMatrixFromCameraIntrinsics(TangoCameraIntrinsics intrinsics) {
        // Uses frustumM to create a projection matrix taking into account calibrated camera
        // intrinsic parameter.
        // Reference: http://ksimek.github.io/2013/06/03/calibrated_cameras_in_opengl/
        float near = 0.1f;
        float far = 100;

        double cx = intrinsics.cx;
        double cy = intrinsics.cy;
        double width = intrinsics.width;
        double height = intrinsics.height;
        double fx = intrinsics.fx;
        double fy = intrinsics.fy;

        double xscale = near / fx;
        double yscale = near / fy;

        double xoffset = (cx - (width / 2.0)) * xscale;
        // Color camera's coordinates has y pointing downwards so we negate this term.
        double yoffset = -(cy - (height / 2.0)) * yscale;

        float m[] = new float[16];
        Matrix.frustumM(m, 0,
                (float) (xscale * -width / 2.0 - xoffset),
                (float) (xscale * width / 2.0 - xoffset),
                (float) (yscale * -height / 2.0 - yoffset),
                (float) (yscale * height / 2.0 - yoffset), near, far);
        return m;
    }

    public boolean findWall() {
        // Synchronize against concurrent access to the RGB timestamp in the OpenGL thread
        // and a possible service disconnection due to an onPause event.
        synchronized (this) {

            // Get X, Y, Z of position
            TangoPoseData odomPose = TangoSupport.getPoseAtTime(
                    mRgbTimestampGlThread,
                    TangoPoseData.COORDINATE_FRAME_START_OF_SERVICE,
                    TangoPoseData.COORDINATE_FRAME_DEVICE,
                    TangoSupport.TANGO_SUPPORT_ENGINE_TANGO,
                    TangoSupport.TANGO_SUPPORT_ENGINE_TANGO,
                    TangoSupport.ROTATION_IGNORED);
            if (odomPose.statusCode != TangoPoseData.POSE_VALID) {
                Log.d(TAG, "Could not get a valid pose from depth camera"
                        + "to color camera at time " + mRgbTimestampGlThread);
                return false;
            }

            TangoPointCloudData pointCloud = mPointCloudManager.getLatestPointCloud();

            // Matrix transforming depth frame to odom frame
            TangoSupport.TangoMatrixTransformData depthTodom = TangoSupport.getMatrixTransformAtTime(
                    mRgbTimestampGlThread,
                    TangoPoseData.COORDINATE_FRAME_CAMERA_DEPTH,
                    TangoPoseData.COORDINATE_FRAME_START_OF_SERVICE,
                    TangoSupport.TANGO_SUPPORT_ENGINE_TANGO,
                    TangoSupport.TANGO_SUPPORT_ENGINE_TANGO,
                    TangoSupport.ROTATION_IGNORED);

            // get largest plane
            int mostInliers = 0;
            int planesUsed = 0;
            float[] us = {0.5f, 0.4f, 0.6f, 0.2f, 0.8f};
            float[] vs = {0.5f, 0.4f, 0.6f, 0.2f, 0.8f};
            for (float u : us) {
                for (float v : vs) {
                    try {
                        IntersectionPointPlaneModelPair planeModel = doFitPlane(u, v, mRgbTimestampGlThread, pointCloud);

                        float[] planeInOdom = transformPlaneNormal(planeModel.planeModel, depthTodom);
                        double[] planeInOdomDouble = {
                                (double) planeInOdom[0],
                                (double) planeInOdom[1],
                                (double) planeInOdom[2],
                                (double) planeInOdom[3],
                        };
//                            Log.w(TAG, String.format("depth X: %f Y: %f Z: %f\n", planeModel.planeModel[0], planeModel.planeModel[1], planeModel.planeModel[2]));
//                            Log.w(TAG, String.format("odom  X: %f Y: %f Z: %f\n", planeInOdom[0], planeInOdom[1], planeInOdom[2]));

                        // Choose walls (not ceilings) + largest plane (the one with most inliers)
                        int nInliers = numInliers(pointCloud.points, planeModel.planeModel);
                        if ((Math.abs(planeInOdom[2]) < 0.04) && (nInliers > mostInliers)) {

                            mostInliers = nInliers;
                            mDepthTPlane = convertPlaneModelToMatrix(planeModel);
                            double newdist = planeDistance(planeInOdomDouble, odomPose.translation);
                            Log.w(TAG, "Distance to Wall: " + Double.toString(newdist));
                            planesUsed++;
                        }

                    } catch(TangoException t){
                        // Failed to fit plane.
                    } catch(SecurityException t){
                        Toast.makeText(getApplicationContext(),
                                R.string.failed_permissions,
                                Toast.LENGTH_SHORT).show();
                        Log.e(TAG, getString(R.string.failed_permissions), t);
                    }
                }

            }

            if (planesUsed == 0) {
                Toast.makeText(getApplicationContext(),
                        R.string.failed_measurement,
                        Toast.LENGTH_SHORT).show();
                Log.e(TAG, getString(R.string.failed_measurement));
                mDepthTPlane = null;
                return false;
            }
        }
        return true;
    }


    /**
     * Use the Tango Support Library with point cloud data to calculate the plane
     * of the world feature pointed at the location the camera is looking.
     * It returns the IntersectionPoint and the PlaneModel as a pair.
     */
    private IntersectionPointPlaneModelPair doFitPlane(float u, float v, double rgbTimestamp, TangoPointCloudData pointCloud) {

        if (pointCloud == null) {
            return null;
        }

        TangoPoseData depthToColorPose = TangoSupport.getPoseAtTime(
            rgbTimestamp,
            TangoPoseData.COORDINATE_FRAME_CAMERA_DEPTH,
            TangoPoseData.COORDINATE_FRAME_CAMERA_COLOR,
            TangoSupport.TANGO_SUPPORT_ENGINE_TANGO,
            TangoSupport.TANGO_SUPPORT_ENGINE_TANGO,
            TangoSupport.ROTATION_IGNORED);
        if (depthToColorPose.statusCode != TangoPoseData.POSE_VALID) {
            Log.d(TAG, "Could not get a valid pose from depth camera"
                + "to color camera at time " + rgbTimestamp);
            return null;
        }

        // Plane model is in depth camera space due to input poses.
        IntersectionPointPlaneModelPair intersectionPointPlaneModelPair =
                TangoSupport.fitPlaneModelNearPoint(pointCloud,
                        new double[] {0.0, 0.0, 0.0},
                        new double[] {0.0, 0.0, 0.0, 1.0},
                        u, v,
                        mDisplayRotation,
                        depthToColorPose.translation,
                        depthToColorPose.rotation);

        mPlanePlacedTimestamp = mRgbTimestampGlThread;
        return intersectionPointPlaneModelPair;
    }

    private float[] convertPlaneModelToMatrix(IntersectionPointPlaneModelPair planeModel) {
        // Note that depth camera's space is:
        // X - right
        // Y - down
        // Z - forward
        float[] up = new float[]{0, 1, 0, 0};
        float[] depthTPlane = matrixFromPointNormalUp(
                planeModel.intersectionPoint,
                planeModel.planeModel,
                up);
        return depthTPlane;
    }

    /**
     * Set the color camera background texture rotation and save the camera to display rotation.
     */
    private void setDisplayRotation() {
        Display display = getWindowManager().getDefaultDisplay();
        mDisplayRotation = display.getRotation();

        // We also need to update the camera texture UV coordinates. This must be run in the OpenGL
        // thread.
        mSurfaceView.queueEvent(new Runnable() {
            @Override
            public void run() {
                if (mIsConnected) {
                    mRenderer.updateColorCameraTextureUvGlThread(mDisplayRotation);
                }
            }
        });
    }

    /**
     * Calculates a transformation matrix based on a point, a normal and the up gravity vector.
     * The coordinate frame of the target transformation will be a right handed system with Z+ in
     * the direction of the normal and Y+ up.
     */
    private float[] matrixFromPointNormalUp(double[] point, double[] normal, float[] up) {
        float[] zAxis = new float[]{(float) normal[0], (float) normal[1], (float) normal[2]};
        normalize(zAxis);
        float[] xAxis = crossProduct(up, zAxis);
        normalize(xAxis);
        float[] yAxis = crossProduct(zAxis, xAxis);
        normalize(yAxis);
        float[] m = new float[16];
        Matrix.setIdentityM(m, 0);
        m[0] = xAxis[0];
        m[1] = xAxis[1];
        m[2] = xAxis[2];
        m[4] = yAxis[0];
        m[5] = yAxis[1];
        m[6] = yAxis[2];
        m[8] = zAxis[0];
        m[9] = zAxis[1];
        m[10] = zAxis[2];
        m[12] = (float) point[0];
        m[13] = (float) point[1];
        m[14] = (float) point[2];
        return m;
    }

    /**
     * Normalize a vector.
     */
    private void normalize(float[] v) {
        double norm = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        v[0] /= norm;
        v[1] /= norm;
        v[2] /= norm;
    }

    /**
     * Cross product between two vectors following the right-hand rule.
     */
    private float[] crossProduct(float[] v1, float[] v2) {
        float[] result = new float[3];
        result[0] = v1[1] * v2[2] - v2[1] * v1[2];
        result[1] = v1[2] * v2[0] - v2[2] * v1[0];
        result[2] = v1[0] * v2[1] - v2[0] * v1[1];
        return result;
    }

    /**
     * Finds the distance to a plane model in the odom reference frame to the phone's position
     * the Equation used is abs(a*x + b*y + c*z + d) / sqrt(a^2 + b^2 + c^2)
     * where a, b, c, and d are the plane_model in the form (ax+by+cz+d = 0)
     * and x, y, and z are the position of the phone
     */
    private double planeDistance(double[] normal, double[] xyz) {
        double result = (Math.abs(normal[0]*xyz[0] + normal[1]*xyz[1] + normal[2]*xyz[2] + normal[3]) /
                Math.sqrt(Math.pow(normal[0], 2) + Math.pow(normal[1], 2) + Math.pow(normal[2], 2)));
        return result;
    }

    /**
     * calculates p_twiddle = T_transpose * p
     * where T_tranpose is the transpose of the transform matrix from depth to odom
     * and p is [a,b,c,d] plane params, in the depth camera frame.
     */
    private float[] transformPlaneNormal(
            double[] depthNormal,
            TangoSupport.TangoMatrixTransformData depthTodom) {

        float[] depthNormalF = {
                (float) depthNormal[0],
                (float) depthNormal[1],
                (float) depthNormal[2],
                (float) depthNormal[3]
        };

        float[] depthTodomTranspose = new float[16];
        transposeM(depthTodomTranspose, 0, depthTodom.matrix, 0);

        float[] odomNormal = new float[4];
        multiplyMV(odomNormal, 0, depthTodomTranspose, 0, depthNormalF, 0);

        return odomNormal;
    }

    private int numInliers(FloatBuffer points, double[] normal) {
        int count = 0;
        int idx = 0;
        float[] quad = new float[4];
        while (points.hasRemaining()) {
            quad[idx] = points.get();
            idx++;

            // filled a quadruplet
            if (idx > 3) {
                // calculate if inlier
                double planeEq = normal[0]*quad[0] + normal[1]*quad[1] + normal[2]*quad[2] + normal[3];
                if (Math.abs(planeEq) < 0.001) {
                    count++;
                }
                idx = 0;
            }
        }
        return count;
    }

    /**
     * Check to see that we have the necessary permissions for this app; ask for them if we don't.
     *
     * @return True if we have the necessary permissions, false if we don't.
     */
    private boolean checkAndRequestPermissions() {
        if (!hasCameraPermission()) {
            requestCameraPermission();
            return false;
        }
        return true;
    }

    /**
     * Check to see that we have the necessary permissions for this app.
     */
    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(this, CAMERA_PERMISSION) ==
                PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Request the necessary permissions for this app.
     */
    private void requestCameraPermission() {
        if (ActivityCompat.shouldShowRequestPermissionRationale(this, CAMERA_PERMISSION)) {
            showRequestPermissionRationale();
        } else {
            ActivityCompat.requestPermissions(this, new String[]{CAMERA_PERMISSION},
                    CAMERA_PERMISSION_CODE);
        }
    }

    /**
     * If the user has declined the permission before, we have to explain that the app needs this
     * permission.
     */
    private void showRequestPermissionRationale() {
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setMessage("Wall Trailing Game requires camera permission")
                .setPositiveButton("Ok", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {
                        ActivityCompat.requestPermissions(PlaneFittingActivity.this,
                                new String[]{CAMERA_PERMISSION}, CAMERA_PERMISSION_CODE);
                    }
                })
                .create();
        dialog.show();
    }

    /**
     * Display toast on UI thread.
     *
     * @param resId The resource id of the string resource to use. Can be formatted text.
     */
    private void showsToastAndFinishOnUiThread(final int resId) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(PlaneFittingActivity.this,
                        getString(resId), Toast.LENGTH_LONG).show();
                finish();
            }
        });
    }

    /**
     * Result for requesting camera permission.
     */
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        if (hasCameraPermission()) {
            bindTangoService();
        } else {
            Toast.makeText(this, "Wall Trailing Game requires camera permission",
                    Toast.LENGTH_LONG).show();
        }
    }


//    /**
//     * MIDI "publisher"
//     * TODO(rlouie): refactor somewhere else
//     */
//    private void midiSend(byte[] buffer, int count, long timestamp) {
//        try {
//            // send event immediately
//            MidiReceiver receiver = mKeyboardReceiverSelector.getReceiver();
//            if (receiver != null) {
//                receiver.send(buffer, 0, count, timestamp);
//            }
//        } catch (IOException e) {
//            Log.e(TAG, "mKeyboardReceiverSelector.send() failed " + e);
//        }
//    }
}
